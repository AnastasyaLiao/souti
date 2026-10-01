package com.souti.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** DeepSeek OpenAI 兼容客户端。模型 deepseek-flash（V4.1-Flash，支持图片输入）。 */
object DeepSeekClient {

    private const val ENDPOINT = "https://api.deepseek.com/chat/completions"

    fun key(ctx: Context): String {
        val sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        return sp.getString("api_key", BuildConfig.DEEPSEEK_KEY) ?: BuildConfig.DEEPSEEK_KEY
    }

    fun setKey(ctx: Context, k: String) {
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putString("api_key", k).apply()
    }

    /** 公开仓库构建出来的包 Key 是空的（Key 不进版本库）。空 Key 直接发请求只会拿到一个
     *  看不懂的 HTTP 401，所以在这里换成一句能照着做的话。 */
    private fun requireKey(ctx: Context): String {
        val k = key(ctx)
        if (k.isBlank()) error("未配置 DeepSeek API Key。两种填法：构建时传 -PDEEPSEEK_API_KEY=你的Key" +
            "（或写进 local.properties），或者装上后执行 " +
            "adb shell run-as com.souti.ai 往 SharedPreferences cfg 的 api_key 写一次。详见 README。")
        return k
    }

    private suspend fun post(ctx: Context, body: JSONObject): String =
        withContext(Dispatchers.IO) {
            val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${requireKey(ctx)}")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) error("HTTP $code: ${text.take(300)}")
            val obj = JSONObject(text)
            obj.optJSONObject("usage")?.let { u ->
                Log.i("TokenDiag", "chat usage prompt=${u.optInt("prompt_tokens")} " +
                    "completion=${u.optInt("completion_tokens")} total=${u.optInt("total_tokens")}")
            }
            obj.getJSONArray("choices")
                .getJSONObject(0).getJSONObject("message").optString("content")
        }

    /** 拍照识别并解答：**流式**输出，边生成边回调，界面先见题干再见解答。
     *  用纯文本协议（学科：/题干：/解答：三处标记）而不是 JSON，
     *  JSON 的转义和括号会让逐字上屏变得难看且更难断句。 */
    suspend fun streamAnalyze(ctx: Context, base64Jpeg: String,
                              onDelta: (QAPair) -> Unit): QAPair {
        val sys = "你是中小学解题助手。看图片中的题目，按下面的纯文本格式输出，不要任何其他文字、不要 JSON、不要代码围栏：\n" +
            "学科：<只写一个学科名，从 语文/数学/英语/物理/化学/生物/政治/历史/地理/科学/社会 中选>\n" +
            "题干：<把图片里的题目提炼成完整通顺的题干原文，题号可省略；公式、化学式、数学符号一律用 LaTeX（行内 \$...\$）书写>\n" +
            "解答：\n" +
            "<用 Markdown 分步解答，数学/化学公式强制使用 LaTeX：行内 \$...\$，独立 \$\$...\$\$；最后用 **答案：** 给出结论>\n" +
            "要求：\n" +
            "1. 严格保持上面的行首标记「学科：」「题干：」「解答：」各占一行，解答正文从「解答：」的下一行开始。\n" +
            "2. 即使图片部分模糊，也要根据可辨认内容尽力提炼还原题干，禁止输出\u201c未识别到题干\u201d\u201c无法辨认\u201d之类的占位语。\n" +
            "3. 题干要一次写完再写「解答：」，不要来回穿插。\n" +
            "4. 解答必须是真正的 Markdown 排版：分步用有序列表 1. 2. 3.，逐项/各选项分析用 - **A**：… 这样的列表项（不要写成 A、B、C、 的纯文本罗列），" +
            "关键结论用 **粗体**，公式一律 LaTeX；总长控制在 350 字以内，不复述题干、不写引言和总结，最后一行单独写 **答案：**X。"
        val user = JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", "识别并解答这道题："))
            put(JSONObject().put("type", "image_url").put("image_url",
                JSONObject().put("url", "data:image/jpeg;base64,$base64Jpeg")))
        }
        val body = JSONObject().put("model", "deepseek-flash")
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", sys))
                .put(JSONObject().put("role", "user").put("content", user)))
            .put("temperature", 0.2).put("max_tokens", 1200).put("stream", true)
            // 让服务端在最后一个分片里回传 usage：不额外花钱，但每次识别的真实 token（含图片部分）
            // 就能落到 logcat（TokenDiag）里，之后调提示词/压缩比就有数可依，不用猜。
            .put("stream_options", JSONObject().put("include_usage", true))
            // 这台 flash 模型默认是思考模型：实测每个分片都带 reasoning_content，思考 token 白烧一倍。
            // thinking=disabled 实测生效（返回里不再有 reasoning_content），必须带上。
            .put("thinking", JSONObject().put("type", "disabled"))
        val acc = StringBuilder()
        withContext(Dispatchers.IO) {
            val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.setRequestProperty("Authorization", "Bearer ${requireKey(ctx)}")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                error("HTTP $code: ${err.take(300)}")
            }
            conn.inputStream.bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload == "[DONE]") break
                    val o = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                    o.optJSONObject("usage")?.let { u ->
                        Log.i("TokenDiag", "analyze usage prompt=${u.optInt("prompt_tokens")} " +
                            "completion=${u.optInt("completion_tokens")} total=${u.optInt("total_tokens")} " +
                            "detail=${u.optJSONObject("prompt_tokens_details")}")
                    }
                    // 必须用 opt("content") 判类型：思考模型的片断里 content 是 JSON null，
                    // Android 的 optString 会把 null 变成字符串 "null"，题干就被刷屏成 nullnullnull…
                    val delta = runCatching {
                        o.getJSONArray("choices")
                            .getJSONObject(0).optJSONObject("delta")?.opt("content") as? String
                    }.getOrNull()
                    if (!delta.isNullOrEmpty()) {
                        acc.append(delta)
                        withContext(Dispatchers.Main) { onDelta(parseStream(acc.toString())) }
                    }
                }
            }
            conn.disconnect()
        }
        return parseStream(acc.toString())
    }

    /** 从流式半成品文本里切出 学科/题干/解答；标记还没出现时，已到的文字先当题干上屏。 */
    private fun parseStream(raw: String): QAPair {
        // 协议外的开头（模型偶尔先说一句废话）一律丢掉，只从「学科」标记起认
        val i = raw.indexOf("学科")
        val body = if (i > 0) raw.substring(i) else raw
        val m = Regex("\n\\s*解答\\s*[：:]").find(body)
        val head = if (m != null) body.substring(0, m.range.first) else body
        val answer = if (m != null) body.substring(m.range.last + 1) else ""
        val subject = Regex("学科\\s*[：:]\\s*([^\n]+)").find(head)
            ?.groupValues?.get(1)?.trim()?.take(12) ?: "其他"
        val question = head.replace(Regex("(?m)^\\s*学科\\s*[：:].*$"), "")
            .replaceFirst(Regex("^\\s*题干\\s*[：:]?\\s*"), "").trim()
        return QAPair(subject, question, answer.trim())
    }

    /** 单题上下文追问。history: 之前的 user/assistant 轮。
     *
     *  token 口径（这台 flash 按量计费，多轮聊天最容易超支）：
     *  · 历史只带最近 8 条，更早的丢掉——问 AI 一页聊十几轮时，全量回传会让花费按轮数平方涨；
     *  · 历史里的 AI 回复截到 400 字：完整的参考解答本来就在 system 里，重复贴没意义；
     *  · system 里的解答截到 1200 字：350 字上限的正文加上 LaTeX 一般不会超过它，超过的是极少数长解析；
     *  · max_tokens 800→600：250 字的回答用不到 800，留出 LaTeX 余量即可，防止跑飞。 */
    suspend fun chat(ctx: Context, question: String, answer: String,
                     history: List<Pair<String, String>>, userMsg: String): String {
        val brief = if (answer.length > 1200) answer.take(1200) + "…" else answer
        val sys = "你是一名耐心的老师，只聊学习。\n" +
            "范围：这道题本身，以及任何与学习有关的知识（别的科目、概念、方法、例题、拓展都允许）。\n" +
            "红线：游戏、动漫、追星、影视、短视频、恋爱、闲聊、写程序、时事八卦等一切与学习无关的话题都不回答——" +
            "遇到就用一两句礼貌拒绝，并把学生拉回学习；不要复述红线清单、不要举具体例子。\n" +
            "回答用 Markdown 排版：分点用列表，重点用 **粗体**，公式强制 LaTeX（行内 \$...\$，独立 \$\$...\$\$），" +
            "控制在 250 字以内。\n" +
            "【题干】$question\n【参考解答】$brief"
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys))
        history.takeLast(8).forEach { (r, c) ->
            val body = if (r == "assistant" && c.length > 400) c.take(400) + "…" else c
            msgs.put(JSONObject().put("role", r).put("content", body))
        }
        msgs.put(JSONObject().put("role", "user").put("content", userMsg))
        val body = JSONObject().put("model", "deepseek-flash")
            .put("messages", msgs).put("temperature", 0.4).put("max_tokens", 600)
            .put("thinking", JSONObject().put("type", "disabled"))
        return post(ctx, body)
    }
}

data class QAPair(val subject: String, val question: String, val answer: String)
