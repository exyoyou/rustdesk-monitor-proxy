package youyou.monitor.proxy

/**
 * monitor-proxy 对宿主（RustDesk）的依赖。由 App 层实现，模块本身不依赖任何 RustDesk 代码。
 */
interface ProxyHost {
    /** RustDesk 是否已在 hbbs 注册成功（在线） */
    fun isOnline(): Boolean

    /** 用户在 RustDesk 里自己设置的代理，没有返回空串。有的话不接管 */
    fun userProxy(): String

    /** RustDesk 配置的 ID 服务器主机（不含端口），作为 Xray 转发的目标 */
    fun serverHost(): String

    /** 设备当前是否有可用的网络。没有网络时不启动代理、不计入离线时间 */
    fun hasNetwork(): Boolean = true

    /**
     * 把 RustDesk 连接服务器的目标换成 127.0.0.1（端口不变）或恢复，并让它重新连接。
     * 不改 RustDesk 的任何配置，界面显示的服务器地址不变。
     */
    fun setRedirect(enabled: Boolean)
}
