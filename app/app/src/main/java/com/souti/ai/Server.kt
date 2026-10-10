package com.souti.ai

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 答疑搜题的后端通路：登录态、额度、历史与收藏都在 fchat 服务器上，包里不再有任何 AI 凭据。
 *
 *  为什么这么改：APK 是可以被反编译的，DeepSeek 的 key 编在 BuildConfig 里等于把账单挂给全网。
 *  现在客户端只持有一枚短期 access 令牌 + 一枚 refresh 令牌，后端只对 QA_SEARCH_ALLOWED_USER_IDS
 *  里的工号（默认 anastasya=1、puppet=2）开放识别与追问，再叠上服务端"同账号 8 秒最小间隔 +
 *  每日 200 次"两道额度闸。
 *
 *  "登录一次以后不用再登"：fchat 的 access 只有 15 分钟，refresh 30 天且每次换新令牌都会重签
 *  一对（Refresh Token Rotation）。本地按 JWT 的 exp 提前 60 秒续期，401 时再补一次，
 *  所以只要 30 天内打开过一次 App 就一直在线，不需要也不应该存在"永久令牌"。
 *
 *  APP_TOKEN 是 fchat 网关的准入令牌（不带它后端一律伪装成 404），与 fchat 自家客户端同口径：
 *  它只是防扫描的门槛，不是额度凭据——真正的授权判定在服务端按工号做。 */
object Server {

    private const val BASE = "https://fchat.anastasya.top"
    private const val APP_TOKEN = "83e3b7c48823967d5cb92dc32950773f898fbb28bffa052d0f32062f44f19c9c"
    private const val TAG = "SoutiServer"
    private const val CONNECT_MS = 15_000
    private const val READ_MS = 120_000

    // ---------------- 会话 ----------------

    fun loggedIn(ctx: Context): Boolean = Session.refresh(ctx).isNotEmpty()

    fun account(ctx: Context): String = Session.account(ctx)

    fun logout(ctx: Context) = Session.clear(ctx)

    /** 登录并确认这个账号有搜题权限；没权限就地清掉会话，不让用户进到一半才发现。 */
    suspend fun login(ctx: Context, username: String, password: String): String =
        withContext(Dispatchers.IO) {
            val name = username.trim().lowercase()
            val res = JSONObject(rawCall("POST", "/login/", JSONObject()
                .put("username", name).put("password", password)
                .put("device", "智能答疑 · " + Build.MODEL), null))
            Session.save(ctx, name, res.getString("access_token"), res.getString("refresh_token"))
            val qa = JSONObject(rawCall("GET", "/qa/access/", null, Session.access(ctx)))
            if (!qa.optBoolean("enabled", false)) {
                Session.clear(ctx)
                throw IOException("这个账号没有答疑搜题的权限，请联系站主开通。")
            }
            qa.optString("username").ifEmpty { name }
        }

    /** 拿一枚还够用的 access；快到期就用 refresh 换一对新的回来。换不动就清会话（要重新登录）。 */
    private fun ensureAccess(ctx: Context): String? {
        val access = Session.access(ctx)
        if (access.isNotEmpty() && Session.exp(ctx) - System.currentTimeMillis() > 60_000) return access
        val refresh = Session.refresh(ctx)
        if (refresh.isEmpty()) return null
        return runCatching {
            val res = JSONObject(rawCall("POST", "/refresh/",
                JSONObject().put("refresh_token", refresh), null))
            Session.save(ctx, Session.account(ctx), res.getString("access_token"), res.getString("refresh_token"))
            res.getString("access_token")
        }.onFailure {
            Log.w(TAG, "续期失败，需要重新登录: ${it.message}")
            Session.clear(ctx)
        }.getOrNull()
    }

    // ---------------- 业务接口 ----------------

    /** 拍照识别：服务端把上游 SSE 原样透传，这里每收到一行 data: 就回调一次。 */
    suspend fun analyze(ctx: Context, base64Jpeg: String, onLine: suspend (String) -> Unit) =
        withContext(Dispatchers.IO) {
            stream(ctx, "/qa/analyze/", JSONObject().put("image", base64Jpeg), onLine)
        }

    suspend fun chat(ctx: Context, question: String, answer: String,
                     history: List<Pair<String, String>>, content: String): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("question", question).put("answer", answer).put("content", content)
                .put("history", JSONArray().apply {
                    history.forEach { put(JSONObject().put("role", it.first).put("content", it.second)) }
                })
            JSONObject(call(ctx, "POST", "/qa/chat/", body)).optString("content")
        }

    suspend fun get(ctx: Context, path: String): String =
        withContext(Dispatchers.IO) { call(ctx, "GET", path, null) }

    suspend fun postRecord(ctx: Context, path: String, subject: String,
                           question: String, answer: String): String =
        withContext(Dispatchers.IO) {
            call(ctx, "POST", path, JSONObject().put("subject", subject)
                .put("question", question).put("answer", answer))
        }

    suspend fun postIds(ctx: Context, path: String, ids: List<Long>): String =
        withContext(Dispatchers.IO) {
            call(ctx, "POST", path, JSONObject().put("ids", JSONArray(ids)))
        }

    suspend fun removeFavorite(ctx: Context, question: String): String =
        withContext(Dispatchers.IO) {
            call(ctx, "POST", "/qa/favorites/remove/", JSONObject().put("question", question))
        }

    // ---------------- HTTP 底座 ----------------

    private fun open(method: String, path: String, token: String?, stream: Boolean = false): HttpURLConnection {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = CONNECT_MS
        conn.readTimeout = READ_MS
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("x-app-token", APP_TOKEN)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", if (stream) "text/event-stream" else "application/json")
        if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
        return conn
    }

    private fun writeBody(conn: HttpURLConnection, body: JSONObject?) {
        if (body == null) return
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
    }

    private fun finish(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) throw HttpError(code, detail(code, text))
        return text
    }

    /** 带上状态码：401 要能认出来去续期重试，光看文案会漏（后端 401 的 detail 是英文的）。 */
    private class HttpError(val code: Int, message: String) : IOException(message)

    /** 后端把原因放在 detail 里（401/403/422/429 都是人话），原样转给用户比"请求失败"有用。 */
    private fun detail(code: Int, text: String): String {
        val d = runCatching { JSONObject(text).optString("detail") }.getOrDefault("")
        return when {
            d.isNotBlank() -> d
            code == 401 -> "登录已过期，请重新登录。"
            code == 403 -> "这个账号没有答疑搜题的权限。"
            code == 429 -> "操作太频繁，或今天的次数已用完。"
            else -> "服务器返回 $code"
        }
    }

    /** 一次性请求；access 中途过期（例如设备睡了很久）就续一次并重试，只重试一次。 */
    private fun call(ctx: Context, method: String, path: String, body: JSONObject?): String {
        val token = ensureAccess(ctx) ?: throw IOException("请先登录 fchat 账号。")
        try {
            return finish(open(method, path, token).also { writeBody(it, body) })
        } catch (e: HttpError) {
            if (e.code != 401) throw e
            val fresh = ensureAccess(ctx) ?: throw e
            return finish(open(method, path, fresh).also { writeBody(it, body) })
        }
    }

    private fun rawCall(method: String, path: String, body: JSONObject?, token: String?): String =
        finish(open(method, path, token).also { writeBody(it, body) })

    private suspend fun stream(ctx: Context, path: String, body: JSONObject,
                               onLine: suspend (String) -> Unit) {
        val token = ensureAccess(ctx) ?: throw IOException("请先登录 fchat 账号。")
        val conn = open("POST", path, token, stream = true)
        writeBody(conn, body)
        val code = conn.responseCode
        if (code !in 200..299) {
            val text = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            throw HttpError(code, detail(code, text))
        }
        conn.inputStream.bufferedReader().use { r ->
            while (true) {
                val line = r.readLine() ?: break
                if (line.startsWith("data:")) onLine(line)
            }
        }
        conn.disconnect()
    }
}
