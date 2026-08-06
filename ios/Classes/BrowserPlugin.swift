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
        } else {
            // openTWA / isTWASupported / isWebViewAvailable are Android-only;
            // the Dart facade guards on platform and never calls them here.
            result(FlutterMethodNotImplemented)
        }
    }
}
