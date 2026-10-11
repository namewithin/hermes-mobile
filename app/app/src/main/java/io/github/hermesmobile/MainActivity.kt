package io.github.hermesmobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLConnection

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var cfg: Config
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null
    private var loadFailed = false
    private var proxy: LocalProxy? = null
    private val localOrigin get() = "http://127.0.0.1:${proxy?.port ?: 0}"
    private val entryUrl get() = "$localOrigin${PREFIX}index.html"

    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val cb = fileCallback ?: return@registerForActivityResult
        fileCallback = null
        cb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data) ?: run {
            val clip = res.data?.clipData
            if (res.resultCode == RESULT_OK && clip != null) Array(clip.itemCount) { clip.getItemAt(it).uri } else null
        })
    }

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val req = pendingPermission ?: return@registerForActivityResult
        pendingPermission = null
        if (granted) req.grant(req.resources) else req.deny()
    }

    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Config.fromIntent(this, intent)
        val loaded = Config.load(this)
        if (loaded == null || intent.getBooleanExtra(EXTRA_SETUP, false)) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        cfg = loaded
        startProxy()
        createChannel()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)

        web = buildWebView()
        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0a0a0a")); addView(web) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.__hermesMobileBack ? window.__hermesMobileBack() : false") { r ->
                    if (r != "true") moveTaskToBack(true)
                }
            }
        })
        web.loadUrl(entryUrl)
        handleNotificationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (Config.fromIntent(this, intent)) { cfg = Config.load(this)!!; startProxy(); web.loadUrl(entryUrl); return }
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val sid = intent?.getStringExtra(EXTRA_SESSION) ?: return
        web.evaluateJavascript("window.__hermesMobileEmit && window.__hermesMobileEmit('onFocusSession', ${JSONObject.quote(sid)})", null)
    }


    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) {
            if (loadFailed) { loadFailed = false; web.loadUrl(entryUrl) }
            web.evaluateJavascript("window.__hermesMobileEmit && window.__hermesMobileEmit('onPowerResume')", null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView = WebView(this).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.parseColor("#0a0a0a"))
        WebView.setWebContentsDebuggingEnabled(BuildConfig.WEB_DEBUG)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = "$userAgentString HermesMobile/${BuildConfig.VERSION_NAME}"
            textZoom = 100
        }
        CookieManager.getInstance().setAcceptCookie(true)
        addJavascriptInterface(Bridge(), "HermesNative")
        webViewClient = Client()
        webChromeClient = Chrome()
        setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            download(url, URLUtil.guessFileName(url, contentDisposition, mimeType))
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (isLocal(request.url) && request.url.path?.startsWith(PREFIX) == true) return false
            openExternal(request.url.toString())
            return true
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                loadFailed = true
                view.loadDataWithBaseURL(null, errorPage(error.description.toString()), "text/html", "utf-8", null)
            }
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = cb
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                val types = params.acceptTypes.filter { it.contains('/') }
                if (types.isNotEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
            }
            pickFiles.launch(Intent.createChooser(intent, getString(R.string.pick_files)))
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                runOnUiThread {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(request.resources)
                    } else {
                        pendingPermission = request
                        askMic.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            } else request.deny()
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
            val tmp = WebView(this@MainActivity)
            tmp.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    openExternal(r.url.toString()); v.destroy(); return true
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = tmp
            resultMsg.sendToTarget()
            return true
        }

        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
            if (BuildConfig.WEB_DEBUG) Log.d("HermesWeb", "${m.messageLevel()} ${m.message()} @${m.sourceId()}:${m.lineNumber()}")
            return true
        }
    }

    inner class Bridge {
        @JavascriptInterface fun getConfig(): String =
            JSONObject().put("baseUrl", localOrigin).put("remoteUrl", cfg.baseUrl).put("token", cfg.token).toString()

        @JavascriptInterface fun appVersion(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface fun openExternal(url: String) = runOnUiThread { this@MainActivity.openExternal(url) }

        @JavascriptInterface fun writeClipboard(text: String) = runOnUiThread {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Hermes", text))
        }

        @JavascriptInterface fun readClipboard(): String {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: ""
        }

        @JavascriptInterface fun download(url: String, name: String, token: String) = runOnUiThread {
            this@MainActivity.download(url, name.ifBlank { URLUtil.guessFileName(url, null, null) })
        }

        @JavascriptInterface fun notify(json: String) = runOnUiThread { showNotification(JSONObject(json)) }

        @JavascriptInterface fun openSetup() = runOnUiThread {
            startActivity(Intent(this@MainActivity, SetupActivity::class.java)); finish()
        }

        @JavascriptInterface fun vibrate() = runOnUiThread { web.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM) }

        @JavascriptInterface fun isForeground(): Boolean = hasWindowFocus()
    }

    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, url, Toast.LENGTH_SHORT).show()
        }
    }

    private fun download(url: String, name: String) {
        try {
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Hermes/$name")
            if (isLocal(Uri.parse(url)) || cfg.isSameOrigin(Uri.parse(url))) {
                req.addRequestHeader("Authorization", "Bearer ${cfg.token}")
                req.addRequestHeader("X-Hermes-Session-Token", cfg.token)
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Toast.makeText(this, getString(R.string.downloading, name), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "download failed", Toast.LENGTH_LONG).show()
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH))
    }

    private fun showNotification(o: JSONObject) {
        if (hasWindowFocus() && o.optBoolean("onlyWhenUnfocused", true)) return
        val sid = o.optString("sessionId", o.optJSONObject("data")?.optString("sessionId") ?: "")
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (sid.isNotEmpty()) intent.putExtra(EXTRA_SESSION, sid)
        val pi = PendingIntent.getActivity(this, sid.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(o.optString("title", "Hermes"))
            .setContentText(o.optString("body", ""))
            .setStyle(NotificationCompat.BigTextStyle().bigText(o.optString("body", "")))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try { NotificationManagerCompat.from(this).notify(sid.ifEmpty { o.toString() }.hashCode(), n) } catch (_: SecurityException) {}
    }

    private fun errorPage(msg: String) = """
        <html><head><meta name=viewport content="width=device-width,initial-scale=1"></head>
        <body style="background:#0a0a0a;color:#eee;font-family:sans-serif;padding:32px">
        <h2>Can't reach Hermes</h2>
        <p style="color:#aaa">${cfg.baseUrl}<br>$msg</p>
        <p>Check that Tailscale is on on both the phone and the Mac.</p>
        <button onclick="location.href='$entryUrl'" style="padding:12px 20px;font-size:16px">Retry</button>
        <button onclick="HermesNative.openSetup()" style="padding:12px 20px;font-size:16px;margin-left:8px">Settings</button>
        </body></html>""".trimIndent()

    private fun isLocal(u: Uri) = u.host == "127.0.0.1" && u.port == (proxy?.port ?: -1)

    private fun pageHtml(): ByteArray {
        val html = assets.open("renderer/index.html").bufferedReader().readText()
        val shim = assets.open("shim/shim.js").bufferedReader().readText()
        val css = assets.open("shim/mobile.css").bufferedReader().readText()
        return html.replaceFirst("<head>", "<head><script>$shim</script><style>$css</style>").toByteArray()
    }

    private fun startProxy() {
        proxy?.stop()
        proxy = LocalProxy(assets, cfg.baseUrl) { pageHtml() }.also { it.start() }
    }

    override fun onDestroy() {
        proxy?.stop()
        super.onDestroy()
    }

    private fun mimeFor(rel: String): String = when (rel.substringAfterLast('.', "").lowercase()) {
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "html" -> "text/html"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "wasm" -> "application/wasm"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ttf" -> "font/ttf"
        "webp" -> "image/webp"
        else -> URLConnection.guessContentTypeFromName(rel) ?: "application/octet-stream"
    }

    companion object {
        const val PREFIX = "/__hermes_mobile__/"
        const val CHANNEL = "hermes-turns"
        const val EXTRA_SESSION = "sessionId"
        const val EXTRA_SETUP = "setup"
    }
}
