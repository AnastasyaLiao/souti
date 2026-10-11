package com.souti.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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
     *  列表按 id 倒序排，所以再拍过的题会回到最前面。上传成功后把本地临时 id 换成服务器 id，
     *  之后的删除才发得对号。 */
    @Synchronized
    fun record(ctx: Context, qa: QAPair) {
        if (qa.question.isBlank()) return
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
        val list = all(ctx)
        val kept = list.filterNot { it.question == qa.question }
        write(ctx, kept + Entry(System.currentTimeMillis(), qa.subject, qa.question, qa.answer, time))
        Sync.fire("搜题历史") {
            val resp = JSONObject(Server.postRecord(ctx, "/qa/history/", qa.subject, qa.question, qa.answer))
            val sid = resp.optLong("id", 0L)
            if (sid > 0L) adoptServerId(ctx, qa.question, sid)
        }
    }

    private fun adoptServerId(ctx: Context, question: String, sid: Long) {
        val list = all(ctx)
        val hit = list.indexOfFirst { it.question == question }
        if (hit < 0 || list[hit].id == sid) return
        write(ctx, list.mapIndexed { i, e -> if (i == hit) e.copy(id = sid) else e })
    }

    /** 用服务器那份覆盖本地缓存（只在没有未落地的写请求时被调用，见 Sync.pullAll）。 */
    suspend fun pull(ctx: Context) {
        val text = Server.get(ctx, "/qa/history/?limit=300")
        val arr = JSONObject(text).getJSONArray("items")
        if (arr.length() == 0 && all(ctx).isNotEmpty()) {
            // 从 v1 升级上来、服务器还是空的：本地这份是要迁移上去的账，不能被"覆盖"掉
            migrateUp(ctx, "/qa/history/")
            return
        }
        val list = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(Entry(o.getLong("id"), o.optString("subject", "其他"), o.getString("question"),
                o.optString("answer"), utcToLocal(o.optString("created_at"))))
        }
        withContext(Dispatchers.Main) { write(ctx, list) }
    }

    /** 把本地已有的条目逐条推给服务器（升级迁移用；服务器按题干去重，重复推不会产生双份）。 */
    private suspend fun migrateUp(ctx: Context, path: String) {
        all(ctx).forEach { e ->
            runCatching { Server.postRecord(ctx, path, e.subject, e.question, e.answer) }
                .onFailure { Log.w("SoutiSync", "迁移一条失败，本地仍保留: ${it.message}") }
        }
    }

    /** 服务器回的是 UTC 裸时间（无时区标记），显示要换成设备本地时区。 */
    private fun utcToLocal(iso: String): String = runCatching {
        val parsed = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(iso.take(19))!!
        val cal = Calendar.getInstance().apply { timeInMillis = parsed.time }
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(cal.time)
    }.getOrDefault(iso.take(16).replace('T', ' '))

    @Synchronized
    fun delete(ctx: Context, ids: Set<Long>) {
        write(ctx, all(ctx).filterNot { ids.contains(it.id) })
        val copy = ids.toList()
        Sync.fire("删历史") { Server.postIds(ctx, "/qa/history/delete/", copy) }
    }
}
