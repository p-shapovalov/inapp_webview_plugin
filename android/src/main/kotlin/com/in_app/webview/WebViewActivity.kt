package com.in_app.webview

import android.annotation.SuppressLint
import android.app.ComponentCaller
import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import io.flutter.plugin.common.MethodChannel
import java.util.regex.Pattern


class WebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var webViewChromeClient: WebViewChromeClient
    private lateinit var methodChannel: MethodChannel
    private var invalidUrlPatternList: List<Pattern>? = null

    private fun checkUrl(url: String): Boolean {
        return invalidUrlPatternList?.let { it.any { p -> checkPattern(p, url) } } ?: false
    }

    private fun checkPattern(p: Pattern, url: String): Boolean {
        return p.matcher(url).lookingAt()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.webview_activity)
        webView = findViewById(R.id.webview)
        webViewChromeClient = WebViewChromeClient(this, applicationContext)

        val url = intent.getStringExtra("url")

        invalidUrlPatternList =
            intent.getStringArrayExtra("invalidUrlRegex")?.map { Pattern.compile(it) }

        if (url == null) {
            finish()
            return
        }

        methodChannel = BrowserPlugin.methodChannel

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowContentAccess = true
        webView.settings.allowFileAccess = true
        webView.webChromeClient = webViewChromeClient
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val newUrl = request?.url.toString()

                if (checkUrl(newUrl)) {
                    methodChannel.invokeMethod("onNavigationCancel", newUrl)
                    return true
                }

                return false
            }
        }

        webView.loadUrl(url)
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
        caller: ComponentCaller
    ) {
        if (intent == null) return
        webViewChromeClient.resultHandler.handleResult(requestCode, resultCode, intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        methodChannel.invokeMethod("onFinish", null)
        finish()
    }
}
