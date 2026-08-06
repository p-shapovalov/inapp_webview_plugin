package com.in_app.webview

import android.app.Activity
import android.content.Intent
import android.webkit.WebView
import androidx.core.net.toUri
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.FlutterPlugin.FlutterPluginBinding
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry

/**
 * Control channel for the embedded webview ([WebViewNativeView]) plus the TWA
 * fast-path. The webview itself is created by the host activity's native-view
 * factory (flutter_native_view_android); `configure` stages the per-open
 * config the factory has no argument channel for.
 */
class BrowserPlugin : FlutterPlugin, MethodCallHandler, ActivityAware,
    PluginRegistry.ActivityResultListener {

    companion object {
        const val CHANNEL = "inapp_webview_channel"
        var methodChannel: MethodChannel? = null
        private var flutterPluginBinding: FlutterPluginBinding? = null

        fun onNavigationCancel(url: String) {
            methodChannel?.invokeMethod("onNavigationCancel", url)
        }

        fun onLoadError(code: Int, domain: String, message: String, category: String) {
            methodChannel?.invokeMethod(
                "onLoadError",
                mapOf(
                    "code" to code,
                    "domain" to domain,
                    "message" to message,
                    "category" to category,
                ),
            )
        }

        fun onWebViewReload(reason: String) {
            methodChannel?.invokeMethod("onWebViewReload", mapOf("reason" to reason))
        }

        fun onWebViewLoaded() {
            methodChannel?.invokeMethod("onWebViewLoaded", null)
        }
    }

    private var activityBinding: ActivityPluginBinding? = null
    private val activity: Activity? get() = activityBinding?.activity

    private fun initPlugin(binaryMessenger: BinaryMessenger) {
        methodChannel = MethodChannel(binaryMessenger, CHANNEL)
        methodChannel?.setMethodCallHandler(this)
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPluginBinding) {
        BrowserPlugin.flutterPluginBinding = flutterPluginBinding

        if (methodChannel == null) {
            initPlugin(flutterPluginBinding.binaryMessenger)
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPluginBinding) {
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        // File-chooser results (WebViewChromeClient launches pickers with
        // startActivityForResult) come back through the plugin binding — the
        // host activity is a plain FlutterActivity, no ComponentActivity
        // launcher registration involved.
        binding.addActivityResultListener(this)

        flutterPluginBinding?.binaryMessenger?.let {
            // Reinitialize MethodChannel Forcefully from MainIsolate
            initPlugin(it)
        }
    }

    override fun onDetachedFromActivityForConfigChanges() = detachActivity()

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activityBinding = binding
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivity() {
        detachActivity()
        methodChannel = null
    }

    private fun detachActivity() {
        activityBinding?.removeActivityResultListener(this)
        activityBinding = null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean =
        WebViewNativeView.instance?.chromeClient?.onActivityResult(requestCode, resultCode, data)
            ?: false

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "configure" -> {
                val url = call.argument<String>("url")
                if (url == null) {
                    result.error("invalid_arguments", "url is null", null)
                    return
                }
                WebViewNativeView.pendingConfig = WebViewConfig(
                    url = url,
                    headers = call.argument<HashMap<String, String>>("headers"),
                    invalidUrlRegex = call.argument<List<String>>("invalidUrlRegex"),
                    // ARGB with a full alpha byte overflows Int32, so the
                    // codec delivers it as a Long.
                    color = (call.argument<Any>("color") as? Number)?.toInt(),
                    bootProbeJs = call.argument<String>("bootProbeJs"),
                    bootProbeUrl = call.argument<String>("bootProbeUrl"),
                )
                result.success(null)
            }

            "reload" -> {
                WebViewNativeView.instance?.reloadWebView()
                result.success(null)
            }

            "evaluateJavascript" -> {
                val js = call.argument<String>("js")
                if (js == null) {
                    result.error("invalid_arguments", "js is null", null)
                    return
                }
                val view = WebViewNativeView.instance
                if (view == null) {
                    result.success(null)
                    return
                }
                view.evaluateJavascript(js) { result.success(it) }
            }

            "openTWA" -> {
                val intent = Intent(activity, LauncherActivity::class.java).apply {
                    data = call.argument<String>("url")?.toUri()
                }
                activity?.startActivity(intent)
            }

            "isTWASupported" -> {
                result.success(
                    activity?.applicationContext?.packageManager?.let
                    { isTwaSupported(it) }
                        ?: false
                )
            }

            "isWebViewAvailable" -> {
                result.success(try {
                    WebView(activity ?: result.run {
                        error("NO_ACTIVITY", "Activity is null", null)
                        return
                    }).destroy()
                    true
                } catch (e: Exception) {
                    false
                })
            }

            else -> {
                result.notImplemented()
            }
        }
    }
}
