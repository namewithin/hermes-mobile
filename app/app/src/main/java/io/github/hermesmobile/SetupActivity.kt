package io.github.hermesmobile

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class SetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Config.fromIntent(this, intent)) { openMain(); return }
        val current = Config.load(this)
        val pad = (20 * resources.displayMetrics.density).toInt()

        val title = TextView(this).apply { text = getString(R.string.setup_title); textSize = 24f; setTextColor(Color.WHITE) }
        val hintView = TextView(this).apply { text = getString(R.string.setup_hint); textSize = 14f; setTextColor(Color.LTGRAY); setPadding(0, pad / 2, 0, pad) }
        val url = EditText(this).apply {
            hint = "http://100.x.y.z:9120"
            setText(current?.baseUrl ?: "")
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            contentDescription = "url"
        }
        val token = EditText(this).apply {
            hint = getString(R.string.token_hint)
            setText(current?.token ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            contentDescription = "token"
        }
        val status = TextView(this).apply { setTextColor(Color.LTGRAY); setPadding(0, pad / 2, 0, pad / 2) }
        val connect = Button(this).apply { text = getString(R.string.connect) }
        connect.setOnClickListener {
            // Accept a pasted connect link (hermesmobile://connect?url=...&token=...) in the URL field.
            val raw = url.text.toString().trim()
            val link = if (raw.startsWith("hermesmobile://")) android.net.Uri.parse(raw) else null
            val u = Config.normalize(link?.getQueryParameter("url") ?: raw)
            val t = link?.getQueryParameter("token") ?: token.text.toString().trim()
            status.text = getString(R.string.checking)
            connect.isEnabled = false
            thread {
                val result = try {
                    val c = URL("$u/api/sessions?limit=1").openConnection() as HttpURLConnection
                    c.connectTimeout = 8000; c.readTimeout = 8000
                    c.setRequestProperty("Authorization", "Bearer $t")
                    c.responseCode
                } catch (e: Exception) { -1 to e.message }
                runOnUiThread {
                    connect.isEnabled = true
                    when (result) {
                        200 -> { Config.save(this, u, t); openMain() }
                        401, 403 -> status.text = getString(R.string.bad_token)
                        else -> status.text = getString(R.string.unreachable, result.toString())
                    }
                }
            }
        }
        val skip = Button(this).apply { text = getString(R.string.save_anyway) }
        skip.setOnClickListener { Config.save(this, url.text.toString(), token.text.toString()); openMain() }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0a0a0a"))
            setPadding(pad, pad, pad, pad)
            listOf(title, hintView, url, token, status, connect, skip).forEach { addView(it) }
        }
        ViewCompat.setOnApplyWindowInsetsListener(col) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(pad + b.left, pad + b.top, pad + b.right, pad + b.bottom); insets
        }
        setContentView(col)
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
