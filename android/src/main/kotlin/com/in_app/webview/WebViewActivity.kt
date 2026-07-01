package com.in_app.webview

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import java.util.regex.Pattern


class WebViewActivity : AppCompatActivity() {
    companion object {
        // The current live survey WebView activity, so the plugin can reload it
        // in place (recover from a transient load error without tearing down).
        var instance: WebViewActivity? = null
    }

    private lateinit var webView: WebView

    private var invalidUrlPatternList: List<Pattern>? = null
    private var pageUrl: String? = null
    private var pageHeaders: HashMap<String, String>? = null
    private var bgColor: Int? = null
    private var isClosing = false

    // Cap self-healing recreations so a genuinely-bad page can't loop. The
    // budget is CONSECUTIVE, not lifetime: a page that loads and stays up for
    // stabilityWindowMs (onPageFinished's stabilityRunnable) refills it, so a
    // long survey survives well-spaced render kills; a tight crash-on-load loop
    // recreates before the runnable fires and never refills.
    private val maxRecreates = 2
    private val stabilityWindowMs = 10_000L
    private var recreatesLeft = maxRecreates
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stabilityRunnable = Runnable { recreatesLeft = maxRecreates }

    fun reloadWebView() {
        runOnUiThread { if (::webView.isInitialized) webView.reload() }
    }

    private fun checkUrl(url: String): Boolean {
        return invalidUrlPatternList?.let { it.any { p -> checkPattern(p, url) } } ?: false
    }

    private fun checkPattern(p: Pattern, url: String): Boolean {
        return p.matcher(url).find()
    }

    // Map WebViewClient error codes onto the shared category vocabulary
    // (network | tls | server | process | other) used by the iOS side.
    private fun categoryFor(code: Int): String = when (code) {
        WebViewClient.ERROR_HOST_LOOKUP,
        WebViewClient.ERROR_CONNECT,
        WebViewClient.ERROR_TIMEOUT,
        WebViewClient.ERROR_IO,
        WebViewClient.ERROR_PROXY_AUTHENTICATION -> "network"
        WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> "tls"
        WebViewClient.ERROR_BAD_URL,
        WebViewClient.ERROR_UNSUPPORTED_SCHEME,
        WebViewClient.ERROR_FILE_NOT_FOUND -> "server"
        else -> "other"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.webview_activity)
        webView = findViewById(R.id.webview)
        instance = this
        val layout = findViewById<FrameLayout>(R.id.relativeLayout)
        val extras = intent.extras ?: return
        val url = extras.getString("url")
        pageHeaders = intent.getSerializableExtra("headers") as HashMap<String, String>?

        bgColor = extras.getLong("color").toInt()
        bgColor?.let { layout.setBackgroundColor(it) }

        invalidUrlPatternList =
            intent.getStringArrayExtra("invalidUrlRegex")?.map { Pattern.compile(it) }

        if (url == null) {
            finish()
            return
        }
        pageUrl = url

        configureWebView(webView)
        loadPage()
    }

    private fun loadPage() {
        val u = pageUrl ?: return
        pageHeaders?.let { webView.loadUrl(u, it) } ?: webView.loadUrl(u)
    }

    // Blank-page recovery: the render process was killed (onRenderProcessGone).
    // Recreate the WebView and reload the URL (which carries sessionId, so the
    // survey resumes server-side rather than restarting). Capped so a
    // genuinely-bad page can't loop; on exhaustion, report a fatal load error.
    private fun recreateWebView() {
        if (isClosing) return
        // A crash cancels any pending budget refill — only a load that survives
        // the full stability window counts as recovered.
        mainHandler.removeCallbacks(stabilityRunnable)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        if (recreatesLeft <= 0) {
            BrowserPlugin.onLoadError(-1, "android", "render process gone; recovery exhausted", "process")
            return
        }
        recreatesLeft -= 1
        webView = WebView(this)
        findViewById<FrameLayout>(R.id.relativeLayout).addView(
            webView,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        configureWebView(webView)
        BrowserPlugin.onWebViewReload() // recovery telemetry
        loadPage()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(wv: WebView) {
        bgColor?.let { wv.setBackgroundColor(it) }
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.allowContentAccess = true
        wv.settings.allowFileAccess = true
        wv.webChromeClient = WebViewChromeClient(this)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val newUrl = request?.url.toString()

                if (newUrl.startsWith("mailto:")) {
                    startActivity(Intent(Intent.ACTION_VIEW, newUrl.toUri()))
                    return true
                } else if (checkUrl(newUrl)) {
                    BrowserPlugin.onNavigationCancel(newUrl)
                    return true
                }

                return false
            }

            // A successful load ends any transient-failure streak: tell the
            // host (resets its inline-retry budget) and arm the recreate-budget
            // refill, which only fires if this load stays up (recreateWebView
            // cancels it on a re-crash).
            override fun onPageFinished(view: WebView?, url: String?) {
                if (isClosing) return
                BrowserPlugin.onWebViewLoaded()
                mainHandler.removeCallbacks(stabilityRunnable)
                mainHandler.postDelayed(stabilityRunnable, stabilityWindowMs)
            }

            // Report main-frame load failures to the host (parity with iOS
            // notifyLoadError). Sub-resource errors are ignored to avoid noise.
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame != true) return
                val code = error?.errorCode ?: 0
                BrowserPlugin.onLoadError(
                    code,
                    "android",
                    error?.description?.toString() ?: "",
                    categoryFor(code)
                )
            }

            // A render-process kill (OOM / system pressure) would crash the host
            // app if unhandled. Recover in place by recreating the WebView;
            // returning true keeps the app alive.
            @RequiresApi(Build.VERSION_CODES.O)
            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                recreateWebView()
                return true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isClosing = true
        mainHandler.removeCallbacks(stabilityRunnable)
        if (instance === this) instance = null
        BrowserPlugin.onFinish()
        finish()
    }
}
