package com.anxin.guard

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 局域网远程控制。
 *
 * 手机与家属设备连同一个 WiFi 时，用浏览器访问 http://手机IP:8899/?key=xxxxxx
 * 即可查看状态、临时解锁、修改时长、启停守护。不需要任何服务器或账号。
 *
 * 只监听局域网端口，所有接口都必须携带正确密钥，否则返回 403。
 */
class RemoteControlServer(
    private val context: Context,
    private val prefs: Prefs,
    private val engine: GuardEngine,
    private val onResumeGuard: () -> Unit
) {

    private val running = AtomicBoolean(false)

    @Volatile
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread({
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(PORT))
                serverSocket = socket
                while (running.get()) {
                    val client = try {
                        socket.accept()
                    } catch (ignored: Throwable) {
                        break
                    }
                    Thread({ handle(client) }, "remote-client").start()
                }
            } catch (ignored: Throwable) {
                // 端口被占用或无网络：静默失败，不影响守护本身
            } finally {
                running.set(false)
            }
        }, "remote-control").start()
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (ignored: Throwable) {
        }
        serverSocket = null
    }

    /** 端口是否真的在监听，供服务定期自愈检查 */
    fun isRunning(): Boolean = running.get() && (serverSocket?.isBound == true)

    private fun handle(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return

                val target = parts[1]
                val mark = target.indexOf('?')
                val route = if (mark >= 0) target.substring(0, mark) else target
                val query = if (mark >= 0) target.substring(mark + 1) else ""
                val params = parseQuery(query)

                if (params["key"] != prefs.remoteKey) {
                    respond(
                        socket,
                        "403 Forbidden",
                        "application/json; charset=utf-8",
                        """{"ok":false,"error":"key"}"""
                    )
                    return
                }

                when (route) {
                    "/", "/index.html" -> respond(
                        socket,
                        "200 OK",
                        "text/html; charset=utf-8",
                        htmlPage()
                    )

                    "/api/status" -> respond(
                        socket,
                        "200 OK",
                        "application/json; charset=utf-8",
                        statusJson()
                    )

                    "/api/unlock" -> {
                        val minutes = params["minutes"]?.toIntOrNull()?.coerceIn(1, 240) ?: 30
                        unlock(minutes)
                        respond(socket, "200 OK", JSON, ok("已临时解锁 $minutes 分钟"))
                    }

                    "/api/set" -> {
                        val limit = params["limit"]?.toIntOrNull()
                        val cooldown = params["cooldown"]?.toIntOrNull()
                        var changed = ""
                        if (limit != null) {
                            prefs.sessionLimitMin = limit.coerceIn(10, 120)
                            changed += "上限 ${prefs.sessionLimitMin} 分钟 "
                        }
                        if (cooldown != null) {
                            prefs.cooldownMin = cooldown.coerceIn(5, 60)
                            changed += "休息 ${prefs.cooldownMin} 分钟"
                        }
                        guardAlive()
                        respond(
                            socket,
                            "200 OK",
                            JSON,
                            ok(if (changed.isBlank()) "没有需要修改的参数" else "已改为 $changed")
                        )
                    }

                    "/api/enable" -> {
                        prefs.enabled = true
                        onResumeGuard()
                        respond(socket, "200 OK", JSON, ok("守护已开启"))
                    }

                    "/api/disable" -> {
                        prefs.enabled = false
                        prefs.remoteUnlockUntil = 0L
                        guardAlive()
                        respond(socket, "200 OK", JSON, ok("守护已暂停"))
                    }

                    else -> respond(
                        socket,
                        "404 Not Found",
                        JSON,
                        """{"ok":false,"error":"not found"}"""
                    )
                }
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun unlock(minutes: Int) {
        val now = System.currentTimeMillis()
        prefs.restrictedUntil = 0L
        prefs.sessionSeconds = 0L
        prefs.lastTickAt = now
        prefs.remoteUnlockUntil = now + minutes * 60_000L
        guardAlive()
        onResumeGuard()
    }

    private fun guardAlive() {
        // 修改后触发一次 tick，让通知和界面尽快同步
        onResumeGuard()
    }

    private fun statusJson(): String {
        val snapshot = engine.snapshot()
        val now = System.currentTimeMillis()
        val remoteRemain = (prefs.remoteUnlockUntil - now).coerceAtLeast(0L) / 1000L
        return JSONObject()
            .put("ok", true)
            .put("enabled", snapshot.enabled)
            .put("restricted", snapshot.restricted)
            .put("sessionSeconds", snapshot.sessionSeconds)
            .put("limitMinutes", snapshot.limitSeconds / 60L)
            .put("cooldownMinutes", prefs.cooldownMin.toLong())
            .put("todaySeconds", snapshot.todaySeconds)
            .put("restrictedRemainSeconds", snapshot.restrictedRemainMs / 1000L)
            .put("remoteUnlockRemainSeconds", remoteRemain)
            .toString()
    }

    private fun ok(message: String): String =
        JSONObject().put("ok", true).put("message", message).toString()

    private fun respond(
        socket: Socket,
        status: String,
        contentType: String,
        body: String
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out: OutputStream = socket.getOutputStream()
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { pair ->
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2) {
                try {
                    URLDecoder.decode(kv[0], "UTF-8") to URLDecoder.decode(kv[1], "UTF-8")
                } catch (ignored: Throwable) {
                    null
                }
            } else {
                null
            }
        }.toMap()
    }

    private fun htmlPage(): String {
        val key = prefs.remoteKey
        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>手机守护 · 远程控制</title>
<style>
*{box-sizing:border-box}
body{margin:0;padding:16px;background:#12211F;color:#E3F0EC;font-family:-apple-system,"PingFang SC",sans-serif}
h1{font-size:20px;margin:0 0 4px}
.sub{font-size:12px;color:#8FA8A2;margin-bottom:16px}
.card{background:#1D3631;border-radius:16px;padding:16px;margin-bottom:12px}
.row{display:flex;justify-content:space-between;font-size:14px;padding:6px 0;border-bottom:1px solid #2A4740}
.row:last-child{border-bottom:none}
.row span:last-child{color:#FFD27A;font-weight:600}
button{width:100%;padding:12px;border:none;border-radius:12px;font-size:15px;font-weight:600;
background:#FFD27A;color:#12211F;margin-top:8px}
button.ghost{background:#2A4740;color:#E3F0EC}
button.warn{background:#5A2A2A;color:#FFC9C9}
.grid{display:grid;grid-template-columns:1fr 1fr 1fr;gap:8px}
.grid button{margin-top:0}
label{display:block;font-size:13px;color:#8FA8A2;margin:10px 0 4px}
input{width:100%;padding:10px;border-radius:10px;border:1px solid #2A4740;background:#12211F;color:#E3F0EC;font-size:15px}
.tip{font-size:12px;color:#7F9993;margin-top:10px;line-height:1.6}
</style>
</head>
<body>
<h1>手机守护</h1>
<div class="sub">局域网远程控制 · 同一 WiFi 可用</div>

<div class="card">
  <div class="row"><span>守护状态</span><span id="st-state">—</span></div>
  <div class="row"><span>本轮已用</span><span id="st-session">—</span></div>
  <div class="row"><span>今日累计</span><span id="st-today">—</span></div>
  <div class="row"><span>休息剩余</span><span id="st-rest">—</span></div>
  <div class="row"><span>远程解锁剩余</span><span id="st-remote">—</span></div>
</div>

<div class="card">
  <label>临时解锁（期间不锁屏）</label>
  <div class="grid">
    <button onclick="unlock(15)">15 分钟</button>
    <button onclick="unlock(30)">30 分钟</button>
    <button onclick="unlock(60)">60 分钟</button>
  </div>
</div>

<div class="card">
  <label>单次使用上限（分钟，10–120）</label>
  <input id="in-limit" type="number" min="10" max="120">
  <label>休息时长（分钟，5–60）</label>
  <input id="in-cooldown" type="number" min="5" max="60">
  <button onclick="save()">保存设置</button>
</div>

<div class="card">
  <button class="ghost" onclick="toggle(true)">开启守护</button>
  <button class="warn" onclick="toggle(false)">暂停守护</button>
  <div class="tip">暂停后会立即结束本轮锁定；远程解锁期间即使超过上限也不会锁屏，倒计时结束自动恢复。</div>
</div>

<script>
const KEY = "$key";
const fmt = s => {
  s = Math.max(0, Math.floor(s));
  const m = Math.floor(s / 60), r = s % 60;
  return m + " 分 " + String(r).padStart(2, "0") + " 秒";
};
async function api(path) {
  const r = await fetch(path + (path.includes("?") ? "&" : "?") + "key=" + KEY);
  return r.json();
}
async function refresh() {
  const d = await api("/api/status");
  document.getElementById("st-state").textContent =
    !d.enabled ? "已暂停" : (d.restricted ? "休息锁定中" : "守护中");
  document.getElementById("st-session").textContent =
    fmt(d.sessionSeconds) + " / " + d.limitMinutes + " 分";
  document.getElementById("st-today").textContent = fmt(d.todaySeconds);
  document.getElementById("st-rest").textContent =
    d.restricted ? fmt(d.restrictedRemainSeconds) : "—";
  document.getElementById("st-remote").textContent =
    d.remoteUnlockRemainSeconds > 0 ? fmt(d.remoteUnlockRemainSeconds) : "—";
  if (document.activeElement.id !== "in-limit") {
    document.getElementById("in-limit").value = d.limitMinutes;
  }
  if (document.activeElement.id !== "in-cooldown") {
    document.getElementById("in-cooldown").value = d.cooldownMinutes;
  }
}
async function unlock(m) {
  const d = await api("/api/unlock?minutes=" + m);
  alert(d.message || "已解锁");
  refresh();
}
async function save() {
  const limit = document.getElementById("in-limit").value;
  const cooldown = document.getElementById("in-cooldown").value;
  const d = await api("/api/set?limit=" + limit + "&cooldown=" + cooldown);
  alert(d.message || "已保存");
  refresh();
}
async function toggle(on) {
  const d = await api(on ? "/api/enable" : "/api/disable");
  alert(d.message || "已执行");
  refresh();
}
refresh();
setInterval(refresh, 3000);
</script>
</body>
</html>
        """.trimIndent()
    }

    companion object {
        const val PORT = 8899
        private const val JSON = "application/json; charset=utf-8"
    }
}
