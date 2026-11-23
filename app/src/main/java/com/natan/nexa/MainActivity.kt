package com.natan.nexa

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.webkit.WebViewAssetLoader
import com.natan.nexa.agent.AgentRunner
import com.natan.nexa.live.LiveVoiceService
import com.natan.nexa.tiktok.TikTokLiveManager
import com.natan.nexa.ui.NexaBridge
import com.natan.nexa.wake.WakeWordService
import org.json.JSONObject

/**
 * Layar utama NEXA AI BY NATAN.
 * Tampilan dibuat dengan HTML/CSS/JS (assets/web) di WebView, terhubung ke Kotlin lewat NexaBridge.
 */
class MainActivity : AppCompatActivity() {

    lateinit var web: WebView
        private set
    private lateinit var bridge: NexaBridge
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val uris = WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data)
            fileCallback?.onReceiveValue(uris)
            fileCallback = null
        }

    val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            emit(JSONObject().put("type", "dictation").put("text", text ?: ""))
        }

    val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { emitStatus() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.parseColor("#070B1A")
        window.navigationBarColor = Color.parseColor("#070B1A")
        AgentRunner.init(this)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#070B1A"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            // UI dimuat dari https://appassets…, server lokal Termux memakai http://127.0.0.1
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    loader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    if (url.host == "appassets.androidplatform.net") return false
                    startActivity(Intent(Intent.ACTION_VIEW, url)); return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams,
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    return try { fileChooser.launch(params.createIntent()); true } catch (e: Exception) {
                        fileCallback = null; false
                    }
                }
            }
        }
        bridge = NexaBridge(this)
        web.addJavascriptInterface(bridge, "Android")
        setContentView(web)
        web.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")

        // Event dari layanan → UI
        AgentRunner.listener = { msg -> emit(JSONObject().put("type", "log").put("text", msg)) }
        LiveVoiceService.onStateChange = { on -> emit(JSONObject().put("type", "live").put("on", on)) }
        TikTokLiveManager.onEvent = { ev -> emit(JSONObject().put("type", "tiktok").put("data", ev)) }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when {
            intent?.getBooleanExtra("start_wake", false) == true -> WakeWordService.start(this)
            // Dipanggil sebagai aplikasi asisten (tahan tombol Home/Power, bila dipilih di pengaturan)
            intent?.action == Intent.ACTION_ASSIST || intent?.action == Intent.ACTION_VOICE_COMMAND ->
                if (!LiveVoiceService.isActive) LiveVoiceService.start(this, greet = true)
        }
    }

    override fun onResume() {
        super.onResume()
        emitStatus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.NEXA && NEXA.back && NEXA.back()") { r ->
            if (r != "true") moveTaskToBack(true)   // tetap berjalan di latar belakang
        }
    }

    override fun onDestroy() {
        AgentRunner.listener = null
        LiveVoiceService.onStateChange = null
        TikTokLiveManager.onEvent = null
        web.destroy()
        super.onDestroy()
    }

    fun emitStatus() = emit(JSONObject().put("type", "status").put("data", JSONObject(bridge.getStatus())))

    /** Kirim event ke JavaScript: window.NEXA.onEvent(obj). */
    fun emit(obj: JSONObject) {
        val js = "window.NEXA && NEXA.onEvent(${JSONObject.quote(obj.toString())})"
        runOnUiThread { if (::web.isInitialized) web.evaluateJavascript(js, null) }
    }
}
