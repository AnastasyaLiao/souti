package com.souti.ai

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** 首页：智能答疑（拍照答疑入口 + 搜题历史 + 错题本入口）。 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 只要用户从任何别的途径（应用列表、通知、桌面其它磁贴）进过一次自己的软件，
        // 就顺手确认桌面「智能答疑」的顶替入口还在——它会被开机时的 nu.nav.bar 抹掉。
        // 必须排在登录门之前：没登录的人（也就是刚升级上来的第一次启动）会被直接 finish() 走，
        // 放后面就等于把自愈机会一起掐掉了。
        runCatching { LauncherTakeoverService.ensureEnabled(this) }
        // 没登录态就先去账号门：识别与讲解都要走服务器，未登录的界面没有意义
        if (!Server.loggedIn(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        // 开屏先和服务器对一次账（后台跑，不挡界面）：历史与收藏以服务器那份为准
        Sync.pullAll(this)
        findViewById<android.view.View>(R.id.ll_paizhao).setOnClickListener {
            // 没网就别让用户白拍一趟：识别要联网，直接挡在门口
            if (!Net.require(this, "拍照搜题")) return@setOnClickListener
            startActivity(Intent(this, PhotoActivity::class.java))
        }
        findViewById<android.view.View>(R.id.tv_history).setOnClickListener {
            startActivity(Intent(this, WrongBookActivity::class.java).putExtra("mode", "history"))
        }
        findViewById<android.view.View>(R.id.tv_cuotiben).setOnClickListener {
            startActivity(Intent(this, WrongBookActivity::class.java))
        }
    }
}
