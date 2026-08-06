import Flutter
import UIKit

public class BrowserPlugin: NSObject, FlutterPlugin {

    static var methodChannel: FlutterMethodChannel?

    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: "inapp_webview_channel", binaryMessenger: registrar.messenger())
        let instance = BrowserPlugin()
        registrar.addMethodCallDelegate(instance, channel: channel)
        registrar.register(BrowserWebViewFactory(), withId: "inapp_webview")

        methodChannel = channel
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        if call.method == "reload" {
            BrowserWebViewPlatformView.current?.reload()
            result(nil)
        } else if call.method == "evaluateJavascript" {
            guard let args = call.arguments as? [String: Any],
                  let js = args["js"] as? String else {
                result(FlutterError(code: "INVALID_ARGUMENTS", message: "js is required", details: nil))
                return
            }
            guard let view = BrowserWebViewPlatformView.current else {
                result(nil)
                return
            }
            view.evaluate(js) { result($0) }
        } else {
            // openTWA / isTWASupported / isWebViewAvailable are Android-only;
            // the Dart facade guards on platform and never calls them here.
            result(FlutterMethodNotImplemented)
        }
    }
}
