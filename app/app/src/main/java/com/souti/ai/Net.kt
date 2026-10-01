package com.souti.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.widget.Toast

/** 联网判断：没网就别浪费用户时间了，拍照搜题/问 AI 全部先过这一关。 */
object Net {
    fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= 23) {
                val n = cm.activeNetwork ?: return@runCatching false
                cm.getNetworkCapabilities(n)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.isConnected == true
            }
        }.getOrDefault(false)
    }

    /** 没网就弹提示并返回 false，调用方直接中止。 */
    fun require(ctx: Context, what: String): Boolean {
        if (online(ctx)) return true
        Toast.makeText(ctx, "当前没有网络，${what}需要联网才能用\n请先连上 Wi‑Fi", Toast.LENGTH_LONG).show()
        return false
    }
}

/** 问 AI 的话题闸门：学习之外的一律不送进模型（既守住范围，也省 token）。
 *  关键词只兜最明显的越界；带学习字样的问题一律放行，剩下的交给 system prompt。 */
object TopicGuard {
    private val OFF = listOf(
        "游戏", "原神", "王者荣耀", "英雄联盟", "蛋仔派对", "迷你世界", "和平精英", "第五人格",
        "攻略", "抽卡", "充值", "氪金", "抖音", "快手", "哔哩哔哩", "b站", "动漫", "番剧",
        "漫画", "小说", "追星", "明星", "八卦", "恋爱", "表白", "处对象", "陪我聊", "闲聊",
        "讲个笑话", "无聊", "写代码", "编程", "色情", "赌博", "骂",
        "game", "gaming", "anime", "manga", "tiktok", "joke", "programming", "gamble")
    private val STUDY = listOf(
        "题", "解", "公式", "知识点", "课文", "单词", "语法", "作文", "方程", "化学", "物理",
        "生物", "历史", "地理", "政治", "英语", "数学", "怎么算", "为什么", "讲解", "概念",
        "实验", "翻译", "背诵", "定义", "定理", "化合", "元素", "作文", "阅读理解")

    fun offTopic(q: String): Boolean {
        val s = q.lowercase()
        if (STUDY.any { s.contains(it) }) return false
        return OFF.any { s.contains(it) }
    }

    const val REFUSAL = "这个和学习无关，我们不当话题聊。\n\n" +
        "你可以接着问这道题的任一步，或者换个学科里的知识点问我，比如这道题背后的能量转化概念。"
}
