import Flutter
import UIKit

public class BrowserPlugin: NSObject, FlutterPlugin {

    static var methodChannel: FlutterMethodChannel?
    static var webViewController: WebViewController?

    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: "inapp_webview_channel", binaryMessenger: registrar.messenger())
        let instance = BrowserPlugin()
        registrar.addMethodCallDelegate(instance, channel: channel)

        methodChannel = channel
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        if call.method == "openWebView" {
            guard let args = call.arguments as? [String: Any],
                  let url = args["url"] as? String,
                  let rootViewController = UIApplication.shared.keyWindow?.rootViewController else {
                result(FlutterError(code: "INVALID_ARGUMENTS", message: "Missing arguments", details: nil))
                return
            }

            let webViewController = WebViewController()
            webViewController.url = url
            webViewController.modalPresentationStyle = .overFullScreen
            rootViewController.present(webViewController, animated: true, completion: nil)

            BrowserPlugin.webViewController = webViewController

            result(nil)

        } else if call.method == "closeWebView" {
            if let webViewController = BrowserPlugin.webViewController {
                webViewController.dismiss(animated: true, completion: {
                    BrowserPlugin.webViewController = nil 
                    self.sendWebViewClosedEvent() 
                })
            }
            result(nil)
        } else {
            result(FlutterMethodNotImplemented)
        }
    }

    private func sendWebViewClosedEvent() {
        BrowserPlugin.methodChannel?.invokeMethod("onFinish", arguments: nil)
    }
}

