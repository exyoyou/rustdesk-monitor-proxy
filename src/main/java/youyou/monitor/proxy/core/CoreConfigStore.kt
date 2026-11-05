package youyou.monitor.proxy.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import youyou.monitor.config.model.ProxyConfig
import youyou.monitor.config.model.WebDavServer
import youyou.monitor.logger.Log
import youyou.monitor.webdav.WebDavClient
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内核配置的来源。优先用上次校验通过的本地缓存（直接返回，不等网络），同时在后台从远程下载刷新缓存，
 * 新配置在下一次启动代理时生效；没有可用缓存（首次、或缓存校验不过）时才同步等下载。
 * 打包进 assets 的模板不含真实节点，只作结构参考，不会被加载。
 */
class CoreConfigStore(context: Context) {
    private val cacheFile = File(context.filesDir, "monitor_proxy/xray_core.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshing = AtomicBoolean(false)

    suspend fun load(cfg: ProxyConfig, servers: List<WebDavServer>): String? {
        val cached = loadCache(cfg)
        if (cached != null) {
            refreshInBackground(cfg, servers)
            return cached
        }
        return fetchRemote(cfg, servers)
    }

    private fun refreshInBackground(cfg: ProxyConfig, servers: List<WebDavServer>) {
        if (!refreshing.compareAndSet(false, true)) return
        scope.launch {
            try {
                fetchRemote(cfg, servers)
            } finally {
                refreshing.set(false)
            }
        }
    }

    // 立即从远程下载最新配置（校验通过则写入缓存），不用缓存。代理不健康时用来取换过的节点
    suspend fun refreshNow(cfg: ProxyConfig, servers: List<WebDavServer>): String? = fetchRemote(cfg, servers)

    // 下载并校验，通过则写入缓存并返回
    private suspend fun fetchRemote(cfg: ProxyConfig, servers: List<WebDavServer>): String? {
        val remote = download(cfg, servers) ?: return null
        if (!sha256Matches(remote, cfg.coreConfigSha256)) {
            Log.w(TAG, "内核配置 sha256 校验失败，丢弃下载内容")
            return null
        }
        saveCache(remote)
        return remote
    }

    private suspend fun download(cfg: ProxyConfig, servers: List<WebDavServer>): String? =
        withContext(Dispatchers.IO) {
            val location = cfg.coreConfigUrl.trim()
            if (location.isEmpty()) return@withContext null
            try {
                if (location.startsWith("http://") || location.startsWith("https://")) {
                    downloadHttp(location)
                } else {
                    downloadWebDav(location, servers)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "下载内核配置失败: ${t.message}")
                null
            }
        }

    private fun downloadHttp(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = HTTP_TIMEOUT_MS
            conn.readTimeout = HTTP_TIMEOUT_MS
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val bytes = conn.inputStream.use { it.readNBytesLimited(MAX_CONFIG_BYTES) }
            return bytes?.toString(Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun downloadWebDav(path: String, servers: List<WebDavServer>): String? {
        val trimmed = path.trim('/')
        val dir = trimmed.substringBeforeLast('/', "")
        val name = trimmed.substringAfterLast('/')
        for (server in servers) {
            if (server.url.isBlank()) continue
            val remoteDir = listOf(server.monitorDir.trim('/'), dir).filter { it.isNotEmpty() }.joinToString("/")
            WebDavClient.fromServer(server).use { client ->
                val bytes = client.downloadFile(remoteDir, name)
                if (bytes.isNotEmpty() && bytes.size <= MAX_CONFIG_BYTES) {
                    return bytes.toString(Charsets.UTF_8)
                }
            }
        }
        return null
    }

    private fun loadCache(cfg: ProxyConfig): String? {
        if (!cacheFile.isFile) return null
        val text = try {
            cacheFile.readText()
        } catch (t: Throwable) {
            return null
        }
        return text.takeIf { sha256Matches(it, cfg.coreConfigSha256) }
    }

    private fun saveCache(text: String) {
        try {
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.writeText(text)
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "保存内核配置缓存失败: ${t.message}")
        }
    }

    private fun sha256Matches(text: String, expected: String): Boolean {
        if (expected.isBlank()) return true
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.equals(expected.trim(), ignoreCase = true)
    }

    private fun java.io.InputStream.readNBytesLimited(limit: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) return null
        }
        return out.toByteArray()
    }

    private companion object {
        const val TAG = "CoreConfigStore"
        const val HTTP_TIMEOUT_MS = 8000
        const val MAX_CONFIG_BYTES = 1024 * 1024
    }
}
