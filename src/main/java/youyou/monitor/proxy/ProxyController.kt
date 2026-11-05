package youyou.monitor.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import youyou.monitor.config.model.MonitorConfig
import youyou.monitor.config.model.ProxyConfig
import youyou.monitor.logger.Log
import youyou.monitor.proxy.core.CoreConfigPatcher
import youyou.monitor.proxy.core.CoreConfigStore
import youyou.monitor.proxy.core.ProxyCore
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 直连优先、失败后自动切到本地转发、直连恢复后自动切回的状态机。
 *
 * Direct：不重定向。离线持续 failThresholdSec 后取内核配置并启动内核，切到 Proxy；
 * Proxy：RustDesk 连服务器的目标换成 127.0.0.1（端口不变）-> 内核 dokodemo-door -> 隧道 -> 服务器。
 *        每隔 recoverProbeIntervalSec 用独立的 TCP 连接探测直连是否可达（不动 RustDesk 的注册，设备一直在线）；
 *        探测通过后每个 tick 继续探测，连续可达 recoverStableSec 才关闭内核回到 Direct。
 *        回到 Direct 后如果很快又不通（例如 TCP 通但 UDP 不通），探测间隔会指数退避。
 *
 * RustDesk 的任何配置都不改。没有可用的内核配置、内核启动失败都只会停留在 Direct（带退避），不影响直连。
 */
class ProxyController(
    private val host: ProxyHost,
    private val core: ProxyCore,
    private val store: CoreConfigStore,
    private val configProvider: suspend () -> MonitorConfig
) {
    private enum class State { DIRECT, PROXY }

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    private var state = State.DIRECT

    private var offlineSince = 0L
    private var lastProbeAt = 0L
    private var reachableSince = 0L
    private var retryNotBefore = 0L
    private var recoveredAt = 0L
    private var flapCount = 0
    private var probeHost = ""
    private var probePort = 0
    private var runningRaw: String? = null
    private var runningPorts: List<Int> = emptyList()
    private var proxyOfflineSince = 0L
    private var refreshNotBefore = 0L

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        job = s.launch {
            while (isActive) {
                try {
                    tick()
                } catch (t: Throwable) {
                    Log.w(TAG, "tick 异常: ${t.message}")
                }
                delay(TICK_MS)
            }
        }
    }

    @Synchronized
    fun stop() {
        scope?.cancel()
        scope = null
        job = null
        if (state != State.DIRECT) {
            host.setRedirect(false)
        }
        core.stop()
        state = State.DIRECT
    }

    private suspend fun tick() {
        val full = configProvider()
        val cfg = full.proxy
        val now = System.currentTimeMillis()

        if (!cfg.enabled || cfg.mode == ProxyConfig.MODE_OFF) {
            if (state != State.DIRECT) switchToDirect()
            return
        }

        when (state) {
            State.DIRECT -> tickDirect(full, now)
            State.PROXY -> tickProxy(full, now)
        }
    }

    private suspend fun tickDirect(full: MonitorConfig, now: Long) {
        val cfg = full.proxy
        // 用户自己配置了代理时不接管
        if (host.userProxy().isNotEmpty()) return
        if (!host.hasNetwork()) {
            offlineSince = 0
            return
        }

        val wantProxy = if (cfg.mode == ProxyConfig.MODE_ALWAYS) {
            true
        } else if (host.isOnline()) {
            offlineSince = 0
            false
        } else {
            if (offlineSince == 0L) offlineSince = now
            now - offlineSince >= cfg.failThresholdSec * 1000L
        }
        if (wantProxy && now >= retryNotBefore) {
            startProxy(full, now)
        }
    }

    private suspend fun tickProxy(full: MonitorConfig, now: Long) {
        val cfg = full.proxy
        if (!core.isRunning()) {
            Log.w(TAG, "内核已停止，回到直连")
            switchToDirect()
            retryNotBefore = now + RETRY_BACKOFF_MS
            return
        }
        if (host.userProxy().isNotEmpty()) {
            Log.i(TAG, "检测到用户自己设置了代理，关闭内置代理")
            switchToDirect()
            return
        }
        applyServerChange(full)
        checkProxyHealth(full, now)
        if (cfg.mode == ProxyConfig.MODE_ALWAYS) return
        // 还没开始连续可达计时时，按（可能退避过的）间隔探测；开始计时后每个 tick 都探测
        if (reachableSince == 0L && now - lastProbeAt < probeIntervalMs(cfg)) return

        if (!probeDirect()) {
            if (reachableSince != 0L) Log.i(TAG, "直连探测失败，继续使用代理")
            reachableSince = 0
            lastProbeAt = now
            return
        }
        if (reachableSince == 0L) {
            reachableSince = now
            Log.i(TAG, "直连可达，确认是否稳定")
        }
        if (now - reachableSince >= cfg.recoverStableSec * 1000L) {
            Log.i(TAG, "直连已恢复，关闭代理")
            switchToDirect()
            recoveredAt = now
        }
    }

    // 已经在用代理、内核也在跑，但 RustDesk 持续注册不上（节点换了、服务端挂了）：
    // 重新下载配置，内容和正在运行的不同就用新配置重启内核；相同则说明问题不在配置，不重启
    private suspend fun checkProxyHealth(full: MonitorConfig, now: Long) {
        if (host.isOnline() || !host.hasNetwork()) {
            proxyOfflineSince = 0
            return
        }
        if (proxyOfflineSince == 0L) proxyOfflineSince = now
        if (now - proxyOfflineSince < full.proxy.failThresholdSec * 1000L || now < refreshNotBefore) return
        refreshNotBefore = now + RETRY_BACKOFF_MS

        val fresh = store.refreshNow(full.proxy, full.webdavServers)
        if (fresh == null) {
            Log.w(TAG, "代理不可用，且无法获取新的内核配置")
            return
        }
        if (fresh == runningRaw) {
            Log.w(TAG, "代理不可用，内核配置没有更新")
            return
        }
        val patched = CoreConfigPatcher.patch(fresh, probeHost, full.proxy.forwardPorts).getOrElse {
            Log.w(TAG, "新的内核配置无效: ${it.message}")
            return
        }
        core.start(patched).onFailure {
            Log.w(TAG, "用新配置重启内核失败: ${it.message}")
            return
        }
        runningRaw = fresh
        proxyOfflineSince = 0
        Log.i(TAG, "内核配置已更新，已用新配置重启内核")
    }

    // 运行期间 ID 服务器或转发端口变了（用户改了服务器、远程同步了新配置），用当前配置重新生成并重启内核
    private fun applyServerChange(full: MonitorConfig) {
        val serverHost = host.serverHost()
        val ports = full.proxy.forwardPorts
        val raw = runningRaw
        if (raw == null || serverHost.isEmpty() || (serverHost == probeHost && ports == runningPorts)) return
        probeHost = serverHost
        runningPorts = ports
        val patched = CoreConfigPatcher.patch(raw, serverHost, ports).getOrElse {
            Log.w(TAG, "转发配置变化后生成内核配置失败: ${it.message}")
            return
        }
        core.start(patched).onFailure {
            Log.w(TAG, "转发配置变化后重启内核失败: ${it.message}")
            return
        }
        probePort = pickProbePort(ports)
        Log.i(TAG, "转发配置已变化，已重启内核: $serverHost $ports")
    }

    private fun pickProbePort(ports: List<Int>): Int =
        if (RENDEZVOUS_PORT in ports) RENDEZVOUS_PORT else ports.first()

    // 独立的 TCP 连接探测，不经过 RustDesk 也不经过隧道，不影响设备在 hbbs 上的注册
    private suspend fun probeDirect(): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { it.connect(InetSocketAddress(probeHost, probePort), PROBE_TIMEOUT_MS) }
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun probeIntervalMs(cfg: ProxyConfig): Long =
        cfg.recoverProbeIntervalSec * 1000L * (1 shl minOf(flapCount, MAX_BACKOFF_SHIFT))

    private suspend fun startProxy(full: MonitorConfig, now: Long) {
        val raw = store.load(full.proxy, full.webdavServers)
        if (raw == null) {
            Log.w(TAG, "没有可用的内核配置，保持直连")
            retryNotBefore = now + RETRY_BACKOFF_MS
            return
        }
        val serverHost = host.serverHost()
        if (serverHost.isEmpty()) {
            Log.w(TAG, "没有配置 ID 服务器，保持直连")
            retryNotBefore = now + RETRY_BACKOFF_MS
            return
        }
        val patched = CoreConfigPatcher.patch(raw, serverHost, full.proxy.forwardPorts).getOrElse {
            Log.w(TAG, "内核配置无效: ${it.message}")
            retryNotBefore = now + RETRY_BACKOFF_MS
            return
        }
        core.start(patched).onFailure {
            Log.w(TAG, "内核启动失败: ${it.message}")
            core.stop()
            retryNotBefore = now + RETRY_BACKOFF_MS
            return
        }

        host.setRedirect(true)
        state = State.PROXY
        lastProbeAt = now
        reachableSince = 0
        offlineSince = 0
        probeHost = serverHost
        runningRaw = raw
        proxyOfflineSince = 0
        refreshNotBefore = 0
        val ports = full.proxy.forwardPorts
        runningPorts = ports
        probePort = pickProbePort(ports)
        // 刚恢复直连不久又掉线，说明探测结果不可信（例如 TCP 通但 UDP 不通），拉长下次探测间隔
        flapCount = if (recoveredAt != 0L && now - recoveredAt < FLAP_WINDOW_MS) flapCount + 1 else 0
        Log.i(TAG, "已切换到代理，转发 $serverHost 的端口 ${full.proxy.forwardPorts}")
    }

    private fun switchToDirect() {
        host.setRedirect(false)
        core.stop()
        state = State.DIRECT
        offlineSince = 0
        reachableSince = 0
    }

    private companion object {
        const val TAG = "ProxyController"
        const val TICK_MS = 5000L
        const val RETRY_BACKOFF_MS = 60_000L
        const val RENDEZVOUS_PORT = 21116
        const val PROBE_TIMEOUT_MS = 3000
        const val FLAP_WINDOW_MS = 10 * 60_000L
        const val MAX_BACKOFF_SHIFT = 3
    }
}
