package com.souti.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** 本地 JSON 与服务器之间的搬运工。
 *
 *  口径：**本地文件是缓存，服务器是账本**。读的时候用服务器覆盖本地，写的时候先落本地再补一条上传，
 *  所以断网也能翻历史、收藏，联网后自动对上。
 *
 *  为什么用后台作用域而不是让界面等：这台机器弱、公网走隧道，一次同步要一两秒，
 *  把它挂在点击事件上会让界面看起来卡住。上传失败只记日志——下一次 pull 之前 pending 计数不为 0，
 *  pull 会被跳过，因此不会出现"本地刚写的那条被服务器上的旧列表冲掉"这种丢数据的情况。
 *  代价：极端情况下（上传一直失败又一直不重开 App）这台机器上留着、服务器上缺一条。 */
object Sync {

    private const val TAG = "SoutiSync"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = AtomicInteger(0)

    /** 发一条写请求，不阻塞界面；成功失败都不影响本地已经落好的那份。 */
    fun fire(label: String, work: suspend () -> Unit) {
        pending.incrementAndGet()
        scope.launch {
            try {
                work()
            } catch (e: Exception) {
                Log.w(TAG, "$label 上传失败（本地已存，稍后随下次同步补齐）: ${e.message}")
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    /** 有写请求还没落地时不要 pull：那会把本地刚写、服务器还没有的条目冲掉。 */
    fun pullAll(ctx: Context) {
        if (pending.get() > 0) {
            Log.i(TAG, "还有 ${pending.get()} 条没同步完，本轮跳过拉取")
            return
        }
        scope.launch {
            runCatching { HistoryStore.pull(ctx) }.onFailure { Log.w(TAG, "拉历史失败: ${it.message}") }
            runCatching { WrongBookStore.pull(ctx) }.onFailure { Log.w(TAG, "拉收藏失败: ${it.message}") }
        }
    }
}
