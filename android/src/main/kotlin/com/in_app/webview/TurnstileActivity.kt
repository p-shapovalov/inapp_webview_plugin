package com.in_app.webview

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class TurnstileActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        val html = intent.getStringExtra("html") ?: run { finish(); return }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        webView.addJavascriptInterface(TurnstileBridge(), "TurnstileBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return false
            }
        }

        webView.loadDataWithBaseURL("https://localhost", html, "text/html", "UTF-8", null)
    }

    private inner class TurnstileBridge {
        @JavascriptInterface
        fun onToken(token: String) {
            BrowserPlugin.methodChannel?.invokeMethod("onTurnstileToken", token)
            runOnUiThread { finish() }
        }

        @JavascriptInterface
        fun onError(error: String) {
            BrowserPlugin.methodChannel?.invokeMethod("onTurnstileError", error)
        }

        @JavascriptInterface
        fun onExpired() {
            BrowserPlugin.methodChannel?.invokeMethod("onTurnstileExpired", null)
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
