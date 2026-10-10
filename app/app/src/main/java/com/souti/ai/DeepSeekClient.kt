package com.souti.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/** 识别与追问的客户端入口。
 *
 *  这里**不再有任何 AI 凭据、也没有提示词**：两者都挪到了服务器（fchat 的 /qa/analyze/ 与 /qa/chat/）。
 *  理由是同一个：APK 可被反编译，包里带 key 等于把账单挂给全网，带提示词等于把判题规则交给别人改。
 *  本类只负责把服务器透传下来的 SSE 增量按「学科：/题干：/解答：」协议拼回界面能用的结构。 */
object DeepSeekClient {

    /** 拍照识别并解答：服务端流式透传，边生成边回调，界面先见题干再见解答。 */
    suspend fun streamAnalyze(ctx: Context, base64Jpeg: String,
                              onDelta: (QAPair) -> Unit): QAPair {
        val acc = StringBuilder()
        var failure: String? = null
        Server.analyze(ctx, base64Jpeg) { line ->
            val payload = line.substring(5).trim()
            if (payload == "[DONE]") return@analyze
            val o = runCatching { JSONObject(payload) }.getOrNull() ?: return@analyze
            // 上游中途断流时状态码已经发不出去（200 + 已开流），错误只能作为事件送回来
            o.optString("error").takeIf { it.isNotBlank() }?.let { failure = it; return@analyze }
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
                val pair = parseStream(acc.toString())
                withContext(Dispatchers.Main) { onDelta(pair) }
            }
        }
        failure?.let { throw IOException(it) }
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

    /** 单题追问：整段回复（与改造前一致）；历史裁剪与 token 上限都由服务端把关。 */
    suspend fun chat(ctx: Context, question: String, answer: String,
                     history: List<Pair<String, String>>, userMsg: String): String =
        Server.chat(ctx, question, answer, history, userMsg)
}

data class QAPair(val subject: String, val question: String, val answer: String)
