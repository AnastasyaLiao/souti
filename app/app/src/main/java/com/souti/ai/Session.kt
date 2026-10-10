package com.souti.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/** 登录态存储：access / refresh 两枚 JWT 与账号名。
 *
 *  用 EncryptedSharedPreferences（Android Keystore 主密钥 + AES-GCM）：refresh 一枚就等价于
 *  "30 天内免登录的账号访问权"，明文 XML 落盘意味着任何拿到 root 或做过备份提取的人都能拿走它。
 *  老设备上 Keystore 偶发不可用（厂商 ROM 的已知坑），那种情况退回应用私有目录的普通 prefs：
 *  别的 App 读不到，只是少了"连备份/取证也读不到"这一层，同时打日志留痕——
 *  宁可降级可用，也不要把人永久锁在登录页外。
 */
object Session {

    private const val TAG = "SoutiSession"
    private const val PLAIN_FALLBACK = "session_plain"

    private fun prefs(ctx: Context): SharedPreferences = runCatching {
        EncryptedSharedPreferences.create(
            ctx, "session", MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrElse {
        Log.w(TAG, "EncryptedSharedPreferences 不可用，退回应用私有 prefs: ${it.message}")
        ctx.getSharedPreferences(PLAIN_FALLBACK, Context.MODE_PRIVATE)
    }

    fun access(ctx: Context): String = prefs(ctx).getString("access", "").orEmpty()
    fun refresh(ctx: Context): String = prefs(ctx).getString("refresh", "").orEmpty()
    fun account(ctx: Context): String = prefs(ctx).getString("account", "").orEmpty()

    /** access 令牌的到期时刻（毫秒）。只解 exp 声明、不验签：验签是服务器的事，
     *  这里只用来决定"要不要提前续期"，被篡改也只会让自己多续一次，不会放宽任何权限。 */
    fun exp(ctx: Context): Long = jwtExp(access(ctx))

    fun save(ctx: Context, account: String, access: String, refresh: String) {
        prefs(ctx).edit()
            .putString("access", access).putString("refresh", refresh)
            .putString("account", account).apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove("access").remove("refresh").remove("account").apply()
        ctx.getSharedPreferences(PLAIN_FALLBACK, Context.MODE_PRIVATE)
            .edit().remove("access").remove("refresh").remove("account").apply()
    }

    private fun jwtExp(token: String): Long = runCatching {
        val part = token.split('.')[1]
        val raw = part.padEnd(part.length + (4 - part.length % 4) % 4, '=')
        val json = String(android.util.Base64.decode(raw, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP))
        JSONObject(json).getLong("exp") * 1000L
    }.getOrDefault(0L)
}
