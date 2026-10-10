package com.souti.ai

import android.content.Context
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

data class Entry(val id: Long, val subject: String, val question: String,
                 val answer: String, val time: String)

/** 错题本持久化：应用私有目录 JSON 文件。
 *
 *  这台机器的 CPU 很弱，之前每次 onResume / 每次收藏判定都从磁盘重读整份 JSON 再逐条跑四个正则，
 *  列表攒到几十条时打开错题本会有可感知的卡顿。现在：解析结果常驻内存，只有真正写盘时才重新计算，
 *  正则全部提前编译好，删除/取消收藏从"读两遍写一遍"改成"读一遍写一遍"。 */
object WrongBookStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "wrongbook.json")

    private val RE_NULL = Regex("^(?:null[\\s]*)+", RegexOption.IGNORE_CASE)
    private val RE_STEM = Regex("题干\\s*[：:]")
    private val RE_SUBJECT = Regex("(?m)^\\s*学科\\s*[：:].*$")

    /** 解析出来的整表缓存；只在 write() 时同步更新，进程内不会脏读。 */
    private var cache: MutableList<Entry>? = null

    /** 早期流式 bug 把 "nullnull…" 前缀和「学科：/题干：」协议标记一起存进了题干。
     *  这种条目不该整条丢掉（后面是真题目），读到时洗干净并落盘。 */
    private fun clean(q0: String): String {
        var q = RE_NULL.replace(q0, "")
        RE_STEM.find(q)?.let { q = q.substring(it.range.last + 1) }
        q = RE_SUBJECT.replace(q, "")
        return q.trim()
    }

    private fun write(ctx: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("subject", it.subject)
            .put("question", it.question).put("answer", it.answer).put("time", it.time)) }
        file(ctx).writeText(arr.toString())
        cache = list.toMutableList()
    }

    @Synchronized
    fun all(ctx: Context): List<Entry> {
        cache?.let { return it }
        val f = file(ctx)
        if (!f.exists()) { cache = mutableListOf(); return cache!! }
        val arr = JSONArray(f.readText())
        var dirty = false
        val list = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val raw = o.getString("question")
            val q = clean(raw)
            if (q != raw) dirty = true
            if (q.isEmpty()) { dirty = true; continue }
            list.add(Entry(o.getLong("id"), o.getString("subject"), q,
                o.getString("answer"), o.getString("time")))
        }
        cache = list
        if (dirty) write(ctx, list)   // 顺手把脏数据落盘清掉
        return list
    }

    @Synchronized
    fun add(ctx: Context, qa: QAPair): Boolean {
        val list = all(ctx)
        if (list.any { it.question == qa.question }) return false // 去重
        if (clean(qa.question).isEmpty()) return false            // 没识别出题目的不收
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
        write(ctx, list + Entry(System.currentTimeMillis(), qa.subject, qa.question, qa.answer, time))
        Sync.fire("收藏") {
            val resp = JSONObject(Server.postRecord(ctx, "/qa/favorites/", qa.subject, qa.question, qa.answer))
            val sid = resp.optLong("id", 0L)
            if (sid > 0L) adoptServerId(ctx, qa.question, sid)
        }
        return true
    }

    private fun adoptServerId(ctx: Context, question: String, sid: Long) {
        val list = all(ctx)
        val hit = list.indexOfFirst { it.question == question }
        if (hit < 0 || list[hit].id == sid) return
        write(ctx, list.mapIndexed { i, e -> if (i == hit) e.copy(id = sid) else e })
    }

    @Synchronized
    fun delete(ctx: Context, ids: Set<Long>) {
        write(ctx, all(ctx).filterNot { ids.contains(it.id) })
        val copy = ids.toList()
        Sync.fire("删收藏") { Server.postIds(ctx, "/qa/favorites/delete/", copy) }
    }

    @Synchronized
    fun has(ctx: Context, question: String) = all(ctx).any { it.question == question }

    /** 按题干取消收藏；返回是否真的删掉了。以前走 delete() 会把整表再解析一遍，这里一次读一次写。 */
    @Synchronized
    fun remove(ctx: Context, question: String): Boolean {
        val list = all(ctx)
        val kept = list.filterNot { it.question == question }
        if (kept.size == list.size) return false
        write(ctx, kept)
        Sync.fire("取消收藏") { Server.removeFavorite(ctx, question) }
        return true
    }

    /** 用服务器那份覆盖本地缓存（只在没有未落地的写请求时被调用，见 Sync.pullAll）。 */
    suspend fun pull(ctx: Context) {
        val arr = JSONObject(Server.get(ctx, "/qa/favorites/?limit=300")).getJSONArray("items")
        val list = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(Entry(o.getLong("id"), o.optString("subject", "其他"), o.getString("question"),
                o.optString("answer"), utcToLocal(o.optString("created_at"))))
        }
        withContext(Dispatchers.Main) { write(ctx, list) }
    }

    private fun utcToLocal(iso: String): String = runCatching {
        val parsed = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(iso.take(19))!!
        Calendar.getInstance().apply { timeInMillis = parsed.time }.time
    }.map { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(it) }
        .getOrDefault(iso.take(16).replace('T', ' '))
}
