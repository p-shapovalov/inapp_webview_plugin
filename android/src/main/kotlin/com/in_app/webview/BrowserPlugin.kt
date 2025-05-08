package com.in_app.webview

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.in_app.webview.LauncherActivity
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.ActivityResultListener


/** BrowserPlugin */
class BrowserPlugin : FlutterPlugin, MethodCallHandler, ActivityAware, ActivityResultListener {

    companion object {
        var methodChannel: MethodChannel? = null
        var activityPluginBinding: ActivityPluginBinding? = null
    }

    private var activity: Activity? = null

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
//        if(methodChannel == null) {
            methodChannel =
                MethodChannel(flutterPluginBinding.binaryMessenger, "inapp_webview_channel")
            methodChannel!!.setMethodCallHandler(this)
//        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        TODO("Not yet implemented")
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
                }

                activity?.startActivityForResult(intent, 20)
                result.success(null)
            }
            "openTWA" -> {
                val intent = Intent(activity, LauncherActivity::class.java).apply {
                    data = call.argument<String>("url")?.let { Uri.parse(it) }
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
            "close" -> {
                activity?.finishActivity(20)
                result.success(null)
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        activityPluginBinding =  binding
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
        activityPluginBinding = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        activityPluginBinding =  binding
    }

    override fun onDetachedFromActivity() {
        activity = null
    }
}
