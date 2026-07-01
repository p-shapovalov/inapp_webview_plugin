package com.in_app.webview

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.webview_activity)
        webView = findViewById(R.id.webview)
        instance = this
        val layout = findViewById<FrameLayout>(R.id.relativeLayout)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        val extras = intent.extras ?: return
        val url = extras.getString("url")
        val headers = intent.getSerializableExtra("headers") as HashMap<String, String>?

        val color = extras.getLong("color")
        webView.setBackgroundColor(color.toInt())
        layout.setBackgroundColor(color.toInt())

        invalidUrlPatternList =
            intent.getStringArrayExtra("invalidUrlRegex")?.map { Pattern.compile(it) }

        if (url == null) {
            finish()
            return
        }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowContentAccess = true
        webView.settings.allowFileAccess = true
        webView.webChromeClient = WebViewChromeClient(this)

        webView.webViewClient = object : WebViewClient() {
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

            // Without handling this, a WebView render-process kill (OOM /
            // system pressure) crashes the whole host app. Detach the dead
            // WebView, report it as a load error so the host can offer retry,
            // and return true to keep the app alive.
            @RequiresApi(Build.VERSION_CODES.O)
            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                (view?.parent as? ViewGroup)?.removeView(view)
                view?.destroy()
                BrowserPlugin.onLoadError(
                    -1,
                    "android",
                    "render process gone (crash=${detail?.didCrash()})",
                    "process"
                )
                return true
            }
        }
        if (headers != null) webView.loadUrl(url, headers) else webView.loadUrl(url)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        BrowserPlugin.onFinish()
        finish()
    }
}
