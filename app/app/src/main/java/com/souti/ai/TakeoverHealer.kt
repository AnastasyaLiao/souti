package com.souti.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** 开机 / 覆盖安装后把桌面入口写回去。
 *
 *  顶替靠的是无障碍服务，而 enabled_accessibility_services 这条全局设置是"整条覆盖"的语义：
 *  这台笔机上的 nu.nav.bar 一开机就把自己的服务单独写进去，我们的条目就被抹掉了，
 *  桌面「智能答疑」于是又跳回原厂程序（本次就是这么复发的）。
 *  所以每次开机、每次覆盖安装，以及每次从任何入口打开自己的主页，都自愈一次。 */
class TakeoverHealer : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val changed = LauncherTakeoverService.ensureEnabled(ctx)
        Log.i(LauncherTakeoverService.TAG, "自愈(${intent?.action}) ${if (changed) "已写回入口" else "入口本来就在"}")
    }
}
