package com.in_app.webview

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.flutter.plugin.common.MethodChannel

class WebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var methodChannel: MethodChannel

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.webview_activity)
        webView = findViewById(R.id.webview)

        val url = intent.getStringExtra("url")

        if (url == null) {
            finish()
            return
        }

        methodChannel = BrowserPlugin.methodChannel

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true 
        webView.settings.allowContentAccess = true
        webView.settings.allowFileAccess = true
        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val newUrl = request?.url.toString()

                if (isDeepLink(newUrl)) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(newUrl))
                        startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(
                            this@WebViewActivity,
                            "No app found to open this link",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return true
                }

                return false 
            }

            override fun onPageFinished(view: WebView?, url: String?) {

            }
        }

        webView.loadUrl(url)
    }

    override fun onDestroy() {
        super.onDestroy()
        methodChannel.invokeMethod("onFinish", null)
        finish()
    }


    private fun isDeepLink(url: String): Boolean {
        val allowedSchemes =
            listOf("http", "https", "file", "chrome", "data", "javascript", "about")
        return allowedSchemes.none { url.startsWith("$it://") }
    }

}
