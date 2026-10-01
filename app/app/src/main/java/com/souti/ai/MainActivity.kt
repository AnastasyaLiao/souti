package com.souti.ai

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** 首页：智能答疑（拍照答疑入口 + 错题本入口）。 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 只要用户从任何别的途径（应用列表、通知、桌面其它磁贴）进过一次自己的软件，
        // 就顺手确认桌面「智能答疑」的顶替入口还在——它会被开机时的 nu.nav.bar 抹掉。
        runCatching { LauncherTakeoverService.ensureEnabled(this) }
        findViewById<android.view.View>(R.id.ll_paizhao).setOnClickListener {
            // 没网就别让用户白拍一趟：识别要联网，直接挡在门口
            if (!Net.require(this, "拍照搜题")) return@setOnClickListener
            startActivity(Intent(this, PhotoActivity::class.java))
        }
        findViewById<android.view.View>(R.id.tv_cuotiben).setOnClickListener {
            startActivity(Intent(this, WrongBookActivity::class.java))
        }
    }
}
