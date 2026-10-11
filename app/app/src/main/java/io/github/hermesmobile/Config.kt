package io.github.hermesmobile

import android.content.Context
import android.content.Intent
import android.net.Uri

data class Config(val baseUrl: String, val token: String) {
    val entryUrl get() = "$baseUrl${MainActivity.PREFIX}index.html"
    private val origin = Uri.parse(baseUrl)
    fun isSameOrigin(u: Uri) = u.scheme == origin.scheme && u.host == origin.host && u.port == origin.port

    companion object {
        private const val PREFS = "hermes"

        fun normalize(raw: String): String {
            var u = raw.trim().trimEnd('/')
            if (u.isEmpty()) return u
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            return u
        }

        fun load(ctx: Context): Config? {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val url = p.getString("url", null) ?: return null
            val token = p.getString("token", null) ?: return null
            return Config(url, token)
        }

        fun save(ctx: Context, url: String, token: String) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("url", normalize(url)).putString("token", token.trim()).apply()
        }

        /** hermesmobile://connect?url=...&token=... — returns true when it changed the config. */
        fun fromIntent(ctx: Context, intent: Intent?): Boolean {
            val data = intent?.data ?: return false
            if (data.scheme != "hermesmobile" || data.host != "connect") return false
            val url = data.getQueryParameter("url") ?: return false
            val token = data.getQueryParameter("token") ?: return false
            save(ctx, url, token)
            intent.data = null
            return true
        }
    }
}
