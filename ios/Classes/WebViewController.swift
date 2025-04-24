import UIKit
import WebKit
import Flutter
class WebViewController: UIViewController, WKNavigationDelegate {
    var url: String = ""
    var color: Int64?
    var invalidUrlRegex: Array<NSRegularExpression?> = []
        
    private lazy var webView: WKWebView = {
        let webConfiguration = WKWebViewConfiguration()
        webConfiguration.preferences.javaScriptEnabled = true
        webConfiguration.websiteDataStore = WKWebsiteDataStore.default()
        webConfiguration.allowsInlineMediaPlayback = true
        webConfiguration.mediaTypesRequiringUserActionForPlayback = []
        
        let webView = WKWebView(frame: .zero, configuration: webConfiguration)
        webView.navigationDelegate = self
        webView.isUserInteractionEnabled = true
        webView.allowsLinkPreview = false
        webView.allowsBackForwardNavigationGestures = true
        webView.scrollView.showsHorizontalScrollIndicator = false
        webView.scrollView.showsVerticalScrollIndicator = false
        return webView
    }()
    
    private func uiColor(fromInt value: Int64) -> UIColor {
        return UIColor(red: CGFloat((value & 0xFF0000) >> 16) / 0xFF,
                       green: CGFloat((value & 0x00FF00) >> 8) / 0xFF,
                       blue: CGFloat(value & 0x0000FF) / 0xFF,
                       alpha: CGFloat((value & 0xFF000000) >> 24) / 0xFF)
    }
    
    override func viewDidLoad() {
        if (color != nil) {
            self.view.backgroundColor = uiColor(fromInt: color!)
        }
        super.viewDidLoad()
        setupUI()
        loadPage()
    }
        
    private func setupUI() {
        webView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(webView)
        
        if #available(iOS 15.0, *) {
            NSLayoutConstraint.activate([
                webView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
                webView.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
                webView.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
                webView.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor),
            ])
        } else {
            NSLayoutConstraint.activate([
                webView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
                webView.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
                webView.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
                webView.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            ])
        }
    }
    
    private func loadPage() {
        guard let url = URL(string: url) else {
            return
        }
        let request = URLRequest(url: url)
        webView.load(request)
    }
    
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        
        guard let url = navigationAction.request.url?.absoluteString else {
            decisionHandler(.allow)
            return
        }
        
        if checkUrl(url) {
            BrowserPlugin.methodChannel?.invokeMethod("onNavigationCancel", arguments: url)
            decisionHandler(.cancel)
            return
        }
        
        decisionHandler(.allow)
    }
    
    private func checkUrl(_ url: String) -> Bool {
        return invalidUrlRegex.contains { checkPattern($0, url) }
    }
    
    
    private func checkPattern(_ regex: NSRegularExpression?, _ url: String) -> Bool {
        let match = regex?.firstMatch(
            in: url,
            options: [],
            range: NSRange(location: 0, length: url.count))
        return match != nil
    }
    
     func close() {
        BrowserPlugin.methodChannel?.invokeMethod("onFinish", arguments: nil)
        self.navigationController?.popViewController(animated: true)
    }
}

