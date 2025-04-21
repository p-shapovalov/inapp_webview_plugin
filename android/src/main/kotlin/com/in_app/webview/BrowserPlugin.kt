package com.in_app.webview

import android.app.Activity
import android.content.Intent
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
        lateinit var methodChannel: MethodChannel
        var activityPluginBinding: ActivityPluginBinding? = null
    }

    var activity: Activity? = null

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel =
            MethodChannel(flutterPluginBinding.binaryMessenger, "inapp_webview_channel")
        methodChannel.setMethodCallHandler(this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        TODO("Not yet implemented")
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "open" -> {
                val url = call.argument<String>("url")
                val invalidUrlRegex = call.argument<List<String>>("invalidUrlRegex")?.toTypedArray()

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
                    putExtra("invalidUrlRegex", invalidUrlRegex)
                }

                activity?.startActivity(intent)
                result.success(null)
            }
            "close" -> {
                activity?.finish()
                result.success(null)
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        activityPluginBinding = binding
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
        activityPluginBinding = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivity() {
        activity = null
    }
}
