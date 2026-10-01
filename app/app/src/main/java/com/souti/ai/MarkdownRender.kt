package com.souti.ai

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/** Markdown + LaTeX 渲染：assets/render 下的自带 ES5 Markdown 解析器 + KaTeX 模板。 */
object MarkdownRender {

    private const val BASE = "file:///android_asset/render/"
    @Volatile private var cachedTemplate: String? = null

    private val RE_FRAC = Regex("\\\\d?frac\\{([^{}]*)\\}\\{([^{}]*)\\}")
    private val RE_SQRT = Regex("\\\\sqrt\\{([^{}]*)\\}")
    private val RE_TEXT = Regex("\\\\text\\{([^{}]*)\\}")
    private val RE_SUP = Regex("\\^\\{([^{}]*)\\}")
    private val RE_SUB = Regex("_\\{([^{}]*)\\}")
    private val RE_CMD = Regex("\\\\[a-zA-Z]+")
    private val RE_BRACE = Regex("[{}]")
    private val RE_MD = Regex("[*_#>`|]")
    private val RE_WS = Regex("\\s+")
    private val SYMBOLS = listOf(
        "\\times" to "×", "\\div" to "÷", "\\pm" to "±", "\\cdot" to "·",
        "\\Delta" to "Δ", "\\alpha" to "α", "\\beta" to "β", "\\pi" to "π",
        "\\neq" to "≠", "\\leq" to "≤", "\\geq" to "≥", "\\approx" to "≈",
        "\\infty" to "∞", "\\degree" to "°", "\\%" to "%",
        "\\mid" to "|", "\\vert" to "|", "\\ldots" to "…")

    /** 列表页等纯文本场景：把 Markdown/LaTeX 压成可读的一行字。
     *  错题本一次要转几十条，这里所有正则都是编译好的常量、符号表也是常量，
     *  避免每条都重新编译 Regex、重新 new 一遍 map（实测这是列表页的主要 CPU 开销）。 */
    fun plain(md: String): String {
        var s = md
        s = s.replace("\\left", "").replace("\\right", "")
        s = RE_FRAC.replace(s) { "${it.groupValues[1]}/${it.groupValues[2]}" }
        s = RE_SQRT.replace(s) { "√${it.groupValues[1]}" }
        s = RE_TEXT.replace(s) { it.groupValues[1] }
        SYMBOLS.forEach { (k, v) -> s = s.replace(k, v) }
        s = RE_SUP.replace(s) { "^${it.groupValues[1]}" }
        s = RE_SUB.replace(s) { "_${it.groupValues[1]}" }
        s = RE_CMD.replace(s, "")
        s = s.replace("$", "").replace(RE_BRACE, "")
        s = s.replace(RE_MD, "")
        return RE_WS.replace(s, " ").trim()
    }

    /** 模板整页 HTML 有 100KB+，每次进结果页/聊天页都从 assets 读一遍、解一遍码，
     *  在这台机器上是可感知的白屏时间；内容从不改，读一次就常驻。 */
    fun template(ctx: Context): String {
        cachedTemplate?.let { return it }
        synchronized(this) {
            cachedTemplate?.let { return it }
            return ctx.assets.open("render/template.html").bufferedReader().use { it.readText() }
                .also { cachedTemplate = it }
        }
    }

    /** 生成整页 HTML；inject 为页面加载后要执行的 JS（如 setMain(...)）。 */
    fun html(ctx: Context, inject: String): String =
        template(ctx).replace("/*__INJECT__*/", "window.addEventListener('load',function(){$inject});")

    fun jsCall(fn: String, arg: String): String = "$fn(${JSONObject.quote(arg)})"

    fun setup(web: WebView) {
        web.setBackgroundColor(0)
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = true
        web.settings.textZoom = 100
        web.isVerticalScrollBarEnabled = false
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) { view.evaluateJavascript("document.body.style.webkitTapHighlightColor='transparent';", null) }
        }
    }
}
