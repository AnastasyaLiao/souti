package com.souti.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 搜题历史：拍过并识别出结果的题一律落盘，跟收藏与否无关。
 *
 *  为什么不塞进 wrongbook.json：那份文件是用户挑过的，列表里的「删除」和取消收藏都直接写它；
 *  流水账要是跟它同进同出，删一条历史就会顺手把收藏也弄丢。
 *  解析结果常驻内存（同 WrongBookStore 的口径），这机器 CPU 弱，每次开列表重读整份 JSON 会卡。 */
object HistoryStore {

    private fun file(ctx: Context) = File(ctx.filesDir, "history.json")

    private var cache: MutableList<Entry>? = null

    @Synchronized
    fun all(ctx: Context): List<Entry> {
        cache?.let { return it }
        val list = ArrayList<Entry>()
        runCatching {
            val f = file(ctx)
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(Entry(o.getLong("id"), o.optString("subject", "其他"),
                        o.getString("question"), o.optString("answer"), o.optString("time")))
                }
            }
        }
        cache = list
        return list
    }

    private fun write(ctx: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("subject", it.subject)
            .put("question", it.question).put("answer", it.answer).put("time", it.time)) }
        file(ctx).writeText(arr.toString())
        cache = list.toMutableList()
    }

    /** 同一道题再拍一次不新增条目，只留最新的一份解答；id 换成新的毫秒数，
     *  列表按 id 倒序排，所以再拍过的题会回到最前面。 */
    @Synchronized
    fun record(ctx: Context, qa: QAPair) {
        if (qa.question.isBlank()) return
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
        val list = all(ctx)
        val kept = list.filterNot { it.question == qa.question }
        write(ctx, kept + Entry(System.currentTimeMillis(), qa.subject, qa.question, qa.answer, time))
    }

    @Synchronized
    fun delete(ctx: Context, ids: Set<Long>) {
        write(ctx, all(ctx).filterNot { ids.contains(it.id) })
    }
}
