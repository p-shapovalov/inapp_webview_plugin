package com.in_app.webview

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import java.util.regex.Pattern


class WebViewActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    private var invalidUrlPatternList: List<Pattern>? = null

    private fun checkUrl(url: String): Boolean {
        return invalidUrlPatternList?.let { it.any { p -> checkPattern(p, url) } } ?: false
    }

    private fun checkPattern(p: Pattern, url: String): Boolean {
        return p.matcher(url).find()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.webview_activity)
        webView = findViewById(R.id.webview)
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
        }
        if (headers != null) webView.loadUrl(url, headers) else webView.loadUrl(url)
    }

    override fun onDestroy() {
        super.onDestroy()
        BrowserPlugin.onFinish()
        finish()
    }
}
