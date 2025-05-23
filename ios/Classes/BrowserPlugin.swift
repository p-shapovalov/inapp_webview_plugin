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
        if call.method == "open" {
            guard let args = call.arguments as? [String: Any],
                  let url = args["url"] as? String,
                  let invalidUrlRegex = args["invalidUrlRegex"] as? Array<String>,
                  let rootViewController = UIApplication.shared.keyWindow?.rootViewController as? UINavigationController else {
                result(FlutterError(code: "INVALID_ARGUMENTS", message: "Missing arguments", details: nil))
                return
            }
            
            let color = args["color"] as? Int64
            let headers = args["headers"] as? [String:String]
                
            let webViewController = WebViewController()
            webViewController.url = url
            webViewController.color = color
            webViewController.headers = headers
            webViewController.invalidUrlRegex = invalidUrlRegex.map { try? NSRegularExpression(pattern: $0, options: .caseInsensitive) }
            
            rootViewController.pushViewController(webViewController, animated: true)
            BrowserPlugin.webViewController = webViewController

            result(nil)

        } else if call.method == "close" {
            if let webViewController = BrowserPlugin.webViewController {
                webViewController.close()
                BrowserPlugin.webViewController = nil
            }
            result(nil)
        } else {
            result(FlutterMethodNotImplemented)
        }
    }
}

