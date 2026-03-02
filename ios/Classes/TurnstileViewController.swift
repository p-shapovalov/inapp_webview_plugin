import UIKit
import WebKit

class TurnstileViewController: UIViewController, WKNavigationDelegate, WKScriptMessageHandler {
    var html: String = ""

    private lazy var webView: WKWebView = {
        let config = WKWebViewConfiguration()
        config.preferences.javaScriptEnabled = true
        config.websiteDataStore = WKWebsiteDataStore.default()
        config.userContentController.add(self, name: "turnstile")

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = self
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        return webView
    }()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        setupUI()
        webView.loadHTMLString(html, baseURL: URL(string: "https://localhost"))
    }

    private func setupUI() {
        webView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(webView)
        NSLayoutConstraint.activate([
            webView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            webView.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
            webView.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
        ])
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.name == "turnstile",
              let body = message.body as? String,
              let data = body.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: String],
              let type = json["type"] else { return }

        switch type {
        case "token":
            if let token = json["value"] {
                BrowserPlugin.methodChannel?.invokeMethod("onTurnstileToken", arguments: token)
                DispatchQueue.main.async {
                    self.navigationController?.popViewController(animated: true)
                }
            }
        case "error":
            BrowserPlugin.methodChannel?.invokeMethod("onTurnstileError", arguments: json["value"])
        case "expired":
            BrowserPlugin.methodChannel?.invokeMethod("onTurnstileExpired", arguments: nil)
        default:
            break
        }
    }

    deinit {
        webView.configuration.userContentController.removeScriptMessageHandler(forName: "turnstile")
    }
}
