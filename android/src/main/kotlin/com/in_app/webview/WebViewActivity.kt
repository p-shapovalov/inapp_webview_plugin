package com.in_app.webview

import android.annotation.SuppressLint
import android.app.ComponentCaller
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.regex.Pattern


class WebViewActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    private var pickerCallback: ValueCallback<Array<Uri>>? = null

    private var invalidUrlPatternList: List<Pattern>? = null

    private fun checkUrl(url: String): Boolean {
        return invalidUrlPatternList?.let { it.any { p -> checkPattern(p, url) } } ?: false
    }

    private fun checkPattern(p: Pattern, url: String): Boolean {
        return p.matcher(url).find()
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
        caller: ComponentCaller
    ) {
        if (pickerCallback != null && requestCode == PICKER) {
            val result = FileChooserParams.parseResult(resultCode, data)
            pickerCallback!!.onReceiveValue(result)
            pickerCallback = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.webview_activity)
        webView = findViewById(R.id.webview)

        val url = intent.getStringExtra("url")

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
        webView.webChromeClient =  object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                val intent = fileChooserParams?.createIntent()
                if (intent != null) {
                    pickerCallback = filePathCallback
                    startActivityForResult(intent, PICKER)
                    return true
                }

                return false
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if ("android.webkit.resource.VIDEO_CAPTURE" == request.resources[0]) {
                        if (ContextCompat.checkSelfPermission(
                                applicationContext,
                                android.Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            request.grant(request.resources)
                        } else {
                            ActivityCompat.requestPermissions(
                                this@WebViewActivity,
                                arrayOf(
                                    android.Manifest.permission.CAMERA,
                                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                                ),
                                REQUEST_CODE
                            )
                        }
                    }
                }
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val newUrl = request?.url.toString()

                if (checkUrl(newUrl)) {
                    BrowserPlugin.methodChannel.invokeMethod("onNavigationCancel", newUrl)
                    return true
                }

                return false
            }
        }

        webView.loadUrl(url)
    }

    override fun onDestroy() {
        super.onDestroy()
        BrowserPlugin.methodChannel.invokeMethod("onFinish", null)
        finish()
    }

    companion object{

        private const val PICKER = 1
        private const val REQUEST_CODE = 2
    }
}
