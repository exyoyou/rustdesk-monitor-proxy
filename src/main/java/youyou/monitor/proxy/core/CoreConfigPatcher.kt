package youyou.monitor.proxy.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 把下载到的 Xray 配置改成"只给本应用用"的形态：
 * 入站整个替换成一组监听 127.0.0.1 的 dokodemo-door（TCP+UDP），每个转发端口一个，监听端口和目标端口相同，
 * 目标是 ID 服务器主机；日志降到 warning；在路由最前面加一条规则，把这些入站都交给配置文件里的代理出站。
 * 出站、dns 等保持配置文件原样，协议（VMess / VLESS + WS + TLS / Reality / ...）由配置文件决定。
 */
object CoreConfigPatcher {
    private const val LOCALHOST = "127.0.0.1"

    // RustDesk 自己会在本机占用这两个端口：21118 是「直接 IP 访问」的 TCP 监听，21119 是局域网发现的 UDP 监听。
    // 配置里写了也要跳过，否则内核绑定失败会导致整个代理启动不了
    private val RESERVED_LOCAL_PORTS = setOf(21118, 21119)
    private val DIRECT_PROTOCOLS = setOf("freedom", "blackhole", "dns", "loopback")

    fun patch(raw: String, serverHost: String, forwardPorts: List<Int>): Result<String> {
        return try {
            require(serverHost.isNotBlank()) { "server host is empty" }
            val ports = forwardPorts.filter { it in 1..65535 && it !in RESERVED_LOCAL_PORTS }.distinct()
            require(ports.isNotEmpty()) { "no usable forward ports" }
            val root = JSONObject(stripComments(raw))
            val outbounds = root.optJSONArray("outbounds")
                ?: return Result.failure(IllegalArgumentException("config has no outbounds"))
            val outboundTag = findProxyOutboundTag(root, outbounds)
                ?: return Result.failure(IllegalArgumentException("config has no proxy outbound"))

            val inbounds = JSONArray()
            val tags = JSONArray()
            for (port in ports) {
                val tag = "local-forward-$port"
                tags.put(tag)
                inbounds.put(
                    JSONObject()
                        .put("tag", tag)
                        .put("protocol", "dokodemo-door")
                        .put("listen", LOCALHOST)
                        .put("port", port)
                        .put(
                            "settings", JSONObject()
                                .put("address", serverHost)
                                .put("port", port)
                                .put("network", "tcp,udp")
                        )
                )
            }
            root.put("inbounds", inbounds)
            root.put("log", JSONObject().put("loglevel", "warning"))

            val routing = root.optJSONObject("routing") ?: JSONObject().also { root.put("routing", it) }
            val oldRules = routing.optJSONArray("rules")
            val rules = JSONArray().put(
                JSONObject()
                    .put("type", "field")
                    .put("inboundTag", tags)
                    .put("outboundTag", outboundTag)
            )
            if (oldRules != null) {
                for (i in 0 until oldRules.length()) rules.put(oldRules.get(i))
            }
            routing.put("rules", rules)
            Result.success(root.toString())
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    // 优先用配置文件里原有入站对应的路由规则指向的出站，其次是 tag 为 proxy 的出站，再其次是第一个非直连/非阻断出站
    private fun findProxyOutboundTag(root: JSONObject, outbounds: JSONArray): String? {
        val inboundTags = HashSet<String>()
        root.optJSONArray("inbounds")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("tag")?.takeIf { it.isNotEmpty() }?.let(inboundTags::add)
            }
        }
        val rules = root.optJSONObject("routing")?.optJSONArray("rules")
        if (rules != null) {
            for (i in 0 until rules.length()) {
                val rule = rules.optJSONObject(i) ?: continue
                val outTag = rule.optString("outboundTag")
                val inTags = rule.optJSONArray("inboundTag") ?: continue
                if (outTag.isEmpty()) continue
                for (j in 0 until inTags.length()) {
                    if (inTags.optString(j) in inboundTags) return outTag
                }
            }
        }
        var first: String? = null
        for (i in 0 until outbounds.length()) {
            val item = outbounds.optJSONObject(i) ?: continue
            val tag = item.optString("tag")
            if (tag == "proxy") return tag
            if (first == null && tag.isNotEmpty() && item.optString("protocol") !in DIRECT_PROTOCOLS) {
                first = tag
            }
        }
        return first
    }

    // 去掉行注释和块注释（字符串内部的不处理），方便在配置文件里写说明
    internal fun stripComments(src: String): String {
        val text = src.removePrefix("\uFEFF")
        val sb = StringBuilder(text.length)
        var i = 0
        var inString = false
        while (i < text.length) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else '\u0000'
            when {
                inString -> {
                    sb.append(c)
                    if (c == '\\' && i + 1 < text.length) {
                        sb.append(next)
                        i++
                    } else if (c == '"') {
                        inString = false
                    }
                    i++
                }
                c == '"' -> {
                    inString = true
                    sb.append(c)
                    i++
                }
                c == '/' && next == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                c == '/' && next == '*' -> {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i += 2
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }
}
