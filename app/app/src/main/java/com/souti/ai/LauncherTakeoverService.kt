package com.souti.ai

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/** 桌面「智能答疑」磁贴在启动器 APK 的资源表里写死了目标组件
 *  （com.jxw.souti/.activity.MainActivity、com.ailide.scan.d3/.D3ScanActivity），
 *  这台笔子没有 root 改不动 /system。所以走事件驱动：磁贴一唤醒原程序，立刻把我们的主页面顶上去。
 *  只在窗口切换事件里做一次包名比较，不轮询、不读控件树，常态零开销。 */
class LauncherTakeoverService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg !in TARGETS) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastFireAt < DEBOUNCE) return
        lastFireAt = now
        Log.i(TAG, "桌面入口命中原程序 $pkg -> 顶替为自己的答疑页")
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }.onFailure { Log.w(TAG, "顶替失败: ${it.message}") }
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "入口服务已绑定，开始盯桌面磁贴")
    }

    companion object {
        const val TAG = "Takeover"
        private const val DEBOUNCE = 1500L
        private val TARGETS = setOf("com.jxw.souti", "com.ailide.scan.d3")
        @Volatile private var lastFireAt = 0L

        /** adb 一条命令就能打开，不用人去设置里翻。 */
        const val COMPONENT = "com.souti.ai/com.souti.ai.LauncherTakeoverService"

        /** 把入口写回 secure 设置；返回是否真的改动了。
         *  需要 WRITE_SECURE_SETTINGS（adb pm grant 给一次就有），没授权时静默失败、不影响其它功能。
         *  注意要"追加"而不是"覆盖"：这台机器上 nu.nav.bar 也在这条列表里，直接整条写死会把它的服务挤掉。 */
        fun ensureEnabled(ctx: Context): Boolean {
            val resolver = ctx.contentResolver
            val cur = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val on = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
            val parts = cur.split(":").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            val present = parts.any { it.equals(COMPONENT, true) }
            if (present && on) return false
            if (!present) parts.add(COMPONENT)
            return runCatching {
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    parts.joinToString(":"))
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }.getOrNull() != null
        }
    }
}
