package youyou.monitor.proxy.core

/** 代理内核。目前只有 Xray 一种实现，协议细节完全由内核配置文件决定。 */
interface ProxyCore {
    /** 用完整的内核配置启动。失败时返回异常而不是抛出 */
    fun start(configJson: String): Result<Unit>

    fun stop()

    fun isRunning(): Boolean
}
