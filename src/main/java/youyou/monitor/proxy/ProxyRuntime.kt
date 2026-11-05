package youyou.monitor.proxy

import android.content.Context
import youyou.monitor.config.repository.ConfigRepositoryImpl
import youyou.monitor.proxy.core.CoreConfigStore
import youyou.monitor.proxy.core.XrayCore

/**
 * 模块入口。宿主只需要提供 [ProxyHost]，其余（配置读取、内核、状态机）都在模块内部。
 * 配置通过自己的 ConfigRepositoryImpl 实例每次 tick 前 reload，能及时拿到远程同步写入的新配置。
 */
object ProxyRuntime {
    private var controller: ProxyController? = null

    @Synchronized
    fun start(context: Context, host: ProxyHost) {
        if (controller != null) return
        val appContext = context.applicationContext
        val repo = ConfigRepositoryImpl(appContext)
        controller = ProxyController(
            host = host,
            core = XrayCore(),
            store = CoreConfigStore(appContext),
            configProvider = {
                repo.reload()
                repo.getCurrentConfig()
            }
        ).also { it.start() }
    }

    @Synchronized
    fun stop() {
        controller?.stop()
        controller = null
    }
}
