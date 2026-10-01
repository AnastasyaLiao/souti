package com.souti.ai

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** 题目详情：题干=AI 提炼的题干原文，解答=AI 的 Markdown+LaTeX；收藏 / 问 AI。 */
class ResultActivity : AppCompatActivity() {

    private var question = ""
    private var answer = ""
    private var subject = "其他"
    private var analyzing = false

    private lateinit var web: WebView
    private lateinit var tabStem: TextView
    private lateinit var tabAnswer: TextView
    private lateinit var tvFav: TextView
    private var showStem = true
    private var userPickedTab = false
    private var pageReady = false
    private var pendingMd: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)
        web = findViewById(R.id.web_content)
        tabStem = findViewById(R.id.tab_stem)
        tabAnswer = findViewById(R.id.tab_answer)
        tvFav = findViewById(R.id.tv_fav)

        MarkdownRender.setup(web)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                pageReady = true
                pendingMd?.let { show(it); pendingMd = null }
            }
        }
        web.loadDataWithBaseURL(MARKDOWN_BASE, MarkdownRender.template(this), "text/html", "utf-8", null)

        findViewById<View>(R.id.iv_back).setOnClickListener { finish() }
        tabStem.setOnClickListener { showStem = true; userPickedTab = true; render() }
        tabAnswer.setOnClickListener { showStem = false; userPickedTab = true; render() }
        tvFav.setOnClickListener { toggleFav() }
        findViewById<View>(R.id.tv_book).setOnClickListener {
            startActivity(Intent(this, WrongBookActivity::class.java))
        }
        findViewById<View>(R.id.tv_ask).setOnClickListener {
            if (question.isEmpty()) return@setOnClickListener
            startActivity(Intent(this, AskAIActivity::class.java)
                .putExtra("question", question).putExtra("answer", answer))
        }

        val q = intent.getStringExtra("question")
        if (q != null) {
            // 从错题本进入：直接展示已存内容
            question = q
            answer = intent.getStringExtra("answer") ?: ""
            subject = intent.getStringExtra("subject") ?: "其他"
            findViewById<View>(R.id.pb).visibility = View.GONE
            findViewById<TextView>(R.id.tv_title).text = "$subject · 题目详情"
            render()
            updateFav()
        } else {
            val path = intent.getStringExtra("photo")
            if (path == null) { finish(); return }
            show("正在识别并解答题目…")
            analyze(File(path))
        }
    }

    private fun analyze(img: File) = lifecycleScope.launch {
        if (!Net.online(this@ResultActivity)) {
            findViewById<View>(R.id.pb).visibility = View.GONE
            findViewById<TextView>(R.id.tv_title).text = "题目详情"
            show("当前没有网络，识别需要联网。\n\n连上 Wi‑Fi 后点左上角返回，重新拍一张。")
            return@launch
        }
        analyzing = true
        // 不再干转圈：接口每回一段就上一屏，先出题干再出解答
        findViewById<View>(R.id.pb).visibility = View.GONE
        findViewById<TextView>(R.id.tv_title).text = "正在识别题目…"
        findViewById<View>(R.id.tv_ask).alpha = 0.4f
        render()
        var failed: String? = null
        var lastDraw = 0L
        runCatching {
            DeepSeekClient.streamAnalyze(this@ResultActivity, encodeJpeg(img)) { p ->
                subject = p.subject; question = p.question; answer = p.answer
                findViewById<TextView>(R.id.tv_title).text = "${p.subject} · 题目详情"
                // 用户没手动选标签时，跟着生成进度走：写完题干自动跳到解答
                if (!userPickedTab) showStem = answer.isEmpty()
                val now = SystemClock.uptimeMillis()
                // 流式途中只走 lite：省掉每帧整篇 KaTeX 重排（这台机器上最贵的一步）
                if (now - lastDraw >= 200) { lastDraw = now; render(lite = true) }
            }
        }.onFailure { failed = it.message }
        analyzing = false
        findViewById<View>(R.id.tv_ask).alpha = 1f
        if (failed != null) show("识别失败：$failed\n\n请检查网络或 API 额度后重试。")
        else { if (!userPickedTab) showStem = true; render() }   // 收尾补一次完整公式排版
        updateFav()
    }


    /** 压缩到最长边 1280、JPEG 75，控制视觉 token 成本。 */
    private fun encodeJpeg(img: File): String {
        val raw = BitmapFactory.decodeFile(img.absolutePath)
        var bmp = raw
        val max = 1280
        val m0 = maxOf(raw.width, raw.height)
        if (m0 > max) {
            val s = max.toFloat() / m0
            bmp = Bitmap.createScaledBitmap(raw, (raw.width * s).toInt(), (raw.height * s).toInt(), true)
        }
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 75, bos)
        if (bmp !== raw) raw.recycle()
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    /** lite=true：只排版不跑 KaTeX。流式期间每 200ms 全量重排公式会把这台机器的 CPU 顶满，
     *  识别过程中先用纯文本上屏，收尾时再完整排版一次。 */
    private fun show(md: String, lite: Boolean = false) {
        if (!pageReady) { pendingMd = md; return }
        val js = if (lite) "setMainLite" else "setMain"
        web.evaluateJavascript("$js(${JSONObject.quote(md)})", null)
    }

    private fun render(lite: Boolean = false) {
        val text = when {
            showStem -> question.ifEmpty {
                if (analyzing) "正在识别题干…"
                else "（本题未提取到题干，可点重拍后裁剪更清晰的区域）"
            }
            else -> answer.ifEmpty {
                if (analyzing) "题干已识别，正在解答…"
                else "（本题暂无解答）"
            }
        }
        show(decorate(text), lite)
        val active = ContextCompat.getColorStateList(this, R.color.blue)
        val inactive = ContextCompat.getColorStateList(this, R.color.gray)
        tabStem.setTextColor(if (showStem) active else inactive)
        tabAnswer.setTextColor(if (showStem) inactive else active)
    }

    /** 流式时把正文末尾画成打字机效果，避免看着像卡住。 */
    private fun decorate(s: String) = if (analyzing) "$s ▍" else s

    /** 星星一键收藏 / 取消收藏。 */
    private fun toggleFav() {
        if (question.isEmpty()) { toast("题目还没识别出来，稍等一下再收藏"); return }
        val removed = WrongBookStore.remove(this, question)
        if (!removed) WrongBookStore.add(this, QAPair(subject, question, answer))
        toast(if (removed) "已从错题本取消收藏" else "已收藏到错题本")
        updateFav()
    }

    private fun updateFav() {
        val saved = question.isNotEmpty() && WrongBookStore.has(this, question)
        tvFav.text = if (saved) "已收藏" else "收藏"
        tvFav.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (saved) R.drawable.ic_star_fill else R.drawable.ic_star_line, 0, 0, 0)
    }

    private fun toast(s: String) =
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    companion object { const val MARKDOWN_BASE = "file:///android_asset/render/" }
}
