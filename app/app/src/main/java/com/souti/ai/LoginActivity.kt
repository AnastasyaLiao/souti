package com.souti.ai

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** 首次进入（或登录态失效）时的账号门：用 fchat 账号登录一次，之后靠 refresh 令牌长期免登录。
 *  登录时就地确认这个账号有没有搜题权限——没权限的账号不该进到一半才吃一个 403。 */
class LoginActivity : AppCompatActivity() {

    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var tvErr: TextView
    private lateinit var tvLogin: TextView
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Server.loggedIn(this)) { goMain(); return }
        setContentView(R.layout.activity_login)
        // 账号门也要自愈桌面入口：覆盖安装会把本包的无障碍注册清掉，
        // 而升级后的第一次启动恰好就停在这一页，不在这里补一次，桌面磁贴就一直不跳。
        runCatching { LauncherTakeoverService.ensureEnabled(this) }

        etUser = findViewById(R.id.et_user)
        etPass = findViewById(R.id.et_pass)
        tvErr = findViewById(R.id.tv_err)
        tvLogin = findViewById(R.id.tv_login)
        findViewById<TextView>(R.id.tv_account).text = Session.account(this)

        tvLogin.setOnClickListener { submit() }
        etPass.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) submit()
            true
        }
    }

    private fun submit() {
        if (busy) return
        val user = etUser.text.toString().trim()
        val pass = etPass.text.toString()
        if (user.isEmpty() || pass.isEmpty()) { show("账号和口令都要填上。"); return }
        if (!Net.online(this)) { show("当前没有网络，登录需要联网。"); return }
        busy = true
        tvLogin.alpha = 0.4f
        show("正在登录…")
        lifecycleScope.launch {
            runCatching { Server.login(this@LoginActivity, user, pass) }
                .onSuccess { goMain() }
                .onFailure { show(it.message ?: "登录失败，稍后再试。") }
            busy = false
            tvLogin.alpha = 1f
        }
    }

    private fun show(s: String) {
        tvErr.text = s
        tvErr.visibility = View.VISIBLE
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
