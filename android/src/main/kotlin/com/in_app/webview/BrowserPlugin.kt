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


/** BrowserPlugin */
class BrowserPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {

    companion object {
        const val CHANNEL = "inapp_webview_channel"
        var methodChannel: MethodChannel? = null
        private var flutterPluginBinding: FlutterPluginBinding? = null

        fun onNavigationCancel(url: String) {
            methodChannel?.invokeMethod("onNavigationCancel", url)
        }

        fun onFinish() {
            methodChannel?.invokeMethod("onFinish", null)
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

    private var activity: Activity? = null

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
        activity = binding.activity

        flutterPluginBinding?.binaryMessenger?.let {
            // Reinitialize MethodChannel Forcefully from MainIsolate
            initPlugin(it)
        }
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivity() {
        activity = null
        methodChannel = null
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "open" -> {
                val url = call.argument<String>("url")
                val invalidUrlRegex = call.argument<List<String>>("invalidUrlRegex")?.toTypedArray()
                val headers = call.argument<HashMap<String, String>>("headers")
                val color = call.argument<Long>("color")

                if (activity == null) {
                    result.error("NO_ACTIVITY", "Activity is null", null)
                    return
                }

                if (url == null) {
                    result.error("invalid_arguments", "url is null", null)
                    return
                }

                val intent = Intent(activity, WebViewActivity::class.java).apply {
                    putExtra("url", url)
                    putExtra("color", color)
                    putExtra("invalidUrlRegex", invalidUrlRegex)
                    putExtra("headers", headers)
                    putExtra("bootProbeJs", call.argument<String>("bootProbeJs"))
                    putExtra("bootProbeUrl", call.argument<String>("bootProbeUrl"))
                }

                activity?.startActivityForResult(intent, 20)
                result.success(null)
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

            "openTurnstile" -> {
                val html = call.argument<String>("html")

                if (activity == null) {
                    result.error("NO_ACTIVITY", "Activity is null", null)
                    return
                }

                if (html == null) {
                    result.error("invalid_arguments", "html is null", null)
                    return
                }

                val intent = Intent(activity, TurnstileActivity::class.java).apply {
                    putExtra("html", html)
                }

                activity?.startActivity(intent)
                result.success(null)
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

            "close" -> {
                activity?.finishActivity(20)
                result.success(null)
            }

            "reload" -> {
                WebViewActivity.instance?.reloadWebView()
                result.success(null)
            }

            else -> {
                result.notImplemented()
            }
        }
    }
}
