package com.souti.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 问 AI 的对话按题目落盘：退出界面、跳去错题本、甚至进程被杀，下次从同一道题再进「问 AI」
 *  都接着上次聊，模型也拿得到之前几轮。
 *
 *  用题干当线程 key：这台机器上「一道题」的唯一标识就是题干（错题本也按它去重），
 *  不另外造 id，免得收藏、历史、对话三份数据对不上。 */
object ChatStore {

    data class Msg(val role: String, val content: String)

    private fun file(ctx: Context) = File(ctx.filesDir, "chats.json")

    private var cache: MutableMap<String, MutableList<Msg>>? = null

    @Synchronized
    private fun load(ctx: Context): MutableMap<String, MutableList<Msg>> {
        cache?.let { return it }
        val map = LinkedHashMap<String, MutableList<Msg>>()
        runCatching {
            val f = file(ctx)
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val list = ArrayList<Msg>()
                    val msgs = o.getJSONArray("msgs")
                    for (j in 0 until msgs.length()) {
                        val m = msgs.getJSONObject(j)
                        list.add(Msg(m.getString("r"), m.getString("c")))
                    }
                    map[o.getString("q")] = list
                }
            }
        }
        cache = map
        return map
    }

    private fun write(ctx: Context, map: Map<String, List<Msg>>) {
        val arr = JSONArray()
        map.forEach { (q, msgs) ->
            val m = JSONArray()
            msgs.forEach { m.put(JSONObject().put("r", it.role).put("c", it.content)) }
            arr.put(JSONObject().put("q", q).put("msgs", m))
        }
        file(ctx).writeText(arr.toString())
    }

    /** 这道题之前聊过的内容（没有就是空表，界面据此决定要不要发那句开场白）。 */
    fun history(ctx: Context, question: String): List<Msg> =
        load(ctx)[question]?.toList() ?: emptyList()

    @Synchronized
    fun append(ctx: Context, question: String, role: String, content: String) {
        if (question.isEmpty()) return
        val map = load(ctx)
        val list = map.getOrPut(question) { ArrayList() }
        list.add(Msg(role, content))
        write(ctx, map)
    }
}
