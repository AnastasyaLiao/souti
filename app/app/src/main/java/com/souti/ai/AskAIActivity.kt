package com.souti.ai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 问 AI：上下文仅当前这一道题；回复按 Markdown+LaTeX 渲染。 */
class AskAIActivity : AppCompatActivity() {

    private data class Msg(val role: String, val content: String)
    private val msgs = mutableListOf<Msg>()
    private lateinit var web: WebView
    private lateinit var et: EditText
    private lateinit var tvSend: TextView
    private var question = ""
    private var answer = ""
    private var busy = false
    private var pageReady = false
    private var shown = 0
    private var recording = false
    private var inCancelZone = false
    private var downY = 0f
    private val ui = Handler(Looper.getMainLooper())

    companion object {
        private const val HINT_IDLE = "输入你的问题…（长按说话，上滑取消）"
        private const val HINT_RECORD = "正在录音…松开发送，往上滑取消"
        private const val HINT_CANCEL = "松开手指就取消这次语音"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ask_ai)
        question = intent.getStringExtra("question") ?: ""
        answer = intent.getStringExtra("answer") ?: ""

        findViewById<View>(R.id.iv_back).setOnClickListener { finish() }
        // 标题里别露原始 LaTeX：上一版直接 take(12) 截的是 "$(-2)^{3}…" 这种源码，看着像坏了
        val brief = MarkdownRender.plain(question)
        findViewById<TextView>(R.id.tv_title).text =
            if (brief.length > 12) "问 AI · ${brief.take(12)}…" else if (brief.isEmpty()) "问 AI" else "问 AI · $brief"
        et = findViewById(R.id.et_input)
        tvSend = findViewById(R.id.tv_send)
        web = findViewById(R.id.web_chat)
        MarkdownRender.setup(web)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                pageReady = true; renderAll()
            }
        }
        web.loadDataWithBaseURL(ResultActivity.MARKDOWN_BASE,
            MarkdownRender.template(this), "text/html", "utf-8", null)

        msgs.add(Msg("assistant", "这道题哪里不明白？可以直接问我，也可以长按输入框说话。\n【题干】$question"))
        tvSend.setOnClickListener { send() }
        et.setOnEditorActionListener { _, _, _ -> send(); true }

        // 长按输入框 = 说话转文字；短按还是正常打字，所以自己接管触摸
        et.isLongClickable = false
        et.setOnTouchListener { v, ev -> onInputTouch(v, ev) }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 2)
        }
    }

    private val startRec = Runnable {
        if (busy) return@Runnable
        if (!Asr.start(this)) { toast("麦克风打不开，先检查有没有别的应用在用"); return@Runnable }
        recording = true
        et.hint = HINT_RECORD
    }

    /** 录音时向上滑超过这个距离就作废本次识别（单位 dp，和原版录音软件一致的手感）。 */
    private fun cancelSlide() = 56f * resources.displayMetrics.density

    /** 按下 350ms 起算录音；松手时如果在录音就吞掉这次点击，否则交回输入框正常处理。
     *  录音中手指往上抬过一段距离 = 取消：录音直接丢掉，不送转写，一个 token 也不花。 */
    private fun onInputTouch(v: View, ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.rawY; inCancelZone = false
                ui.postDelayed(startRec, 350); return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (recording) {
                    val shouldCancel = downY - ev.rawY > cancelSlide()
                    if (shouldCancel != inCancelZone) {
                        inCancelZone = shouldCancel
                        et.hint = if (shouldCancel) HINT_CANCEL else HINT_RECORD
                    }
                }
                return recording
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                ui.removeCallbacks(startRec)
                if (!recording) return false
                if (inCancelZone) dropRecording() else stopAndFill()
                return true
            }
        }
        return false
    }

    /** 取消：只管把麦克风关掉，音频一个字都不留。 */
    private fun dropRecording() {
        recording = false; inCancelZone = false
        et.hint = HINT_IDLE
        Asr.discard()
        toast("已取消这次语音")
    }

    private fun stopAndFill() {
        recording = false; inCancelZone = false
        et.hint = HINT_IDLE
        val wav = Asr.stop()
        if (wav == null) { toast("没录到声音，长按住再说话"); return }
        busy = true
        tvSend.alpha = 0.4f
        toast("在转文字…")
        lifecycleScope.launch {
            runCatching { Asr.transcribe(this@AskAIActivity, wav) }
                .onSuccess { t ->
                    if (t.isBlank()) toast("没听清，长按再说一遍")
                    else et.setText((et.text.toString() + t).trim())
                }
                .onFailure { toast("转文字失败：${it.message}\n检查电脑上 FunASR(8737) 有没有开") }
            busy = false
            tvSend.alpha = 1f
        }
    }

    private fun toast(s: String) =
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun renderAll() {
        if (!pageReady) return
        // 增量上屏：聊得越久，整屏重建越接近 O(n²)，这台机器的 WebView 顶不住。
        // 只有条数变了才追加；最后一条内容变化（占位→真回复）只重画那一个气泡。
        if (shown > msgs.size) {
            shown = 0
            web.evaluateJavascript("clearChat()", null)
        }
        if (msgs.size > shown) {
            msgs.drop(shown).forEach { m ->
                val who = if (m.role == "user") "我" else "AI 老师"
                val me = if (m.role == "user") "true" else "false"
                web.evaluateJavascript(
                    "addBubble(${JSONObject.quote(who)}, ${JSONObject.quote(m.content)}, $me)", null)
            }
            shown = msgs.size
            // 滚动范围取证：只在新增气泡时量一次，别每次重绘都多跑一遍 JS
            web.evaluateJavascript(
                "document.body.scrollHeight+','+window.innerHeight+','+document.body.scrollWidth+','+window.innerWidth"
            ) { v -> Log.i("ChatDiag", "extent(scrollH,viewH,scrollW,viewW)=$v") }
        } else if (msgs.isNotEmpty()) {
            web.evaluateJavascript(
                "setLastBubble(${JSONObject.quote(msgs.last().content)})", null)
        }
    }

    private fun send() {
        val text = et.text.toString().trim()
        if (text.isEmpty() || busy) return
        if (!Net.require(this, "问 AI")) return
        et.setText("")
        // 学习之外的话题本地就挡掉：不进模型，一分 token 都不花
        if (TopicGuard.offTopic(text)) {
            msgs.add(Msg("user", text))
            msgs.add(Msg("assistant", TopicGuard.REFUSAL))
            renderAll()
            return
        }
        busy = true
        tvSend.alpha = 0.4f
        msgs.add(Msg("user", text))
        msgs.add(Msg("assistant", "思考中…"))
        renderAll()
        val history = msgs.dropLast(2).map { it.role to it.content }
        lifecycleScope.launch {
            runCatching {
                DeepSeekClient.chat(this@AskAIActivity, question, answer, history, text)
            }.onSuccess { msgs[msgs.size - 1] = Msg("assistant", it) }
             .onFailure { msgs[msgs.size - 1] = Msg("assistant", "请求失败：${it.message}") }
            busy = false; tvSend.alpha = 1f
            renderAll()
        }
    }
}
