package youyou.monitor.proxy.core

import libXray.LibXray
import org.json.JSONObject
import youyou.monitor.logger.Log

/** 基于 libXray 的 Xray 内核。libXray 每个进程只有一个 Go 运行时，同一时刻只能运行一个实例。 */
class XrayCore : ProxyCore {

    @Synchronized
    override fun start(configJson: String): Result<Unit> {
        return try {
            call("stopXray")
            val resp = call("runXray", JSONObject().put("xrayJson", configJson))
            if (resp.optBoolean("success")) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(resp.optString("error", "runXray failed")))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    @Synchronized
    override fun stop() {
        try {
            call("stopXray")
        } catch (t: Throwable) {
            Log.w(TAG, "stopXray 失败: ${t.message}")
        }
    }

    @Synchronized
    override fun isRunning(): Boolean {
        return try {
            call("getXrayState").optJSONObject("data")?.optBoolean("running") == true
        } catch (t: Throwable) {
            false
        }
    }

    private fun call(method: String, payload: JSONObject? = null): JSONObject {
        val req = JSONObject()
            .put("apiVersion", LibXray.LibXrayAPIVersion.toInt())
            .put("method", method)
        if (payload != null) req.put("payload", payload)
        return JSONObject(LibXray.invoke(req.toString()))
    }

    private companion object {
        const val TAG = "XrayCore"
    }
}
