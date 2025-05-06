import UIKit
import WebKit
import Flutter
class WebViewController: UIViewController, WKNavigationDelegate {
    var url: String = ""
    var color: Int64?
    var headers: [String:String]?
    var invalidUrlRegex: Array<NSRegularExpression?> = []
    
    
    var progressBar = UIProgressView(progressViewStyle: .bar)
    var progressBarTimer: Timer!
    func startIndefiniteProgress() {
        progressBarTimer = Timer.scheduledTimer(timeInterval: 0.03, target: self, selector: #selector(updateProgressView), userInfo: nil, repeats: true)
    }
    
    @objc func updateProgressView() {
        progressBar.progress += 0.01
        if progressBar.progress >= 1.0 {
            progressBar.progress = 0.0
        }
    }
    
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
            let c =  uiColor(fromInt: color!)
            self.view.backgroundColor = c
            self.webView.backgroundColor = c
            self.webView.scrollView.backgroundColor = c
        }
        startIndefiniteProgress()
        super.viewDidLoad()
        setupUI()
        loadPage()
    }
    
    private func setupUI() {
        webView.translatesAutoresizingMaskIntoConstraints = false
        progressBar.translatesAutoresizingMaskIntoConstraints = false
        
        view.insertSubview(progressBar, aboveSubview: webView)
        view.addSubview(webView)
        
        progressBar.topAnchor.constraint(equalTo: self.view.safeAreaLayoutGuide.topAnchor, constant: 0.0).isActive = true
        progressBar.leadingAnchor.constraint(equalTo: self.view.safeAreaLayoutGuide.leadingAnchor, constant: 0.0).isActive = true
        progressBar.trailingAnchor.constraint(equalTo: self.view.safeAreaLayoutGuide.trailingAnchor, constant: 0.0).isActive = true
        
            NSLayoutConstraint.activate([
                webView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
                webView.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
                webView.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
                webView.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            ])
    }
    
    @objc func keyboardWillShow(notification: NSNotification) {
        if let keyboardHeight = (notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue)?.cgRectValue.height {
            self.webView.scrollView.contentInset = UIEdgeInsets(top: 0, left: 0, bottom: 0 - keyboardHeight, right: 0)
        }
    }
    
    @objc func keyboardWillHideß(notification: NSNotification) {
        UIView.animate(withDuration: 0.2, animations: {
            self.webView.scrollView.contentInset = UIEdgeInsets(top: 0, left: 0, bottom: 0, right: 0)
        })
    }
    
    private func loadPage() {
        guard let url = URL(string: url) else {
            return
        }
        var request = URLRequest(url: url)
        webView.isHidden = true
        
        if(headers != nil) {
            for (key, value) in headers! {
                request.setValue(value, forHTTPHeaderField: key)
            }
        }
        
        webView.load(request)
    }
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        webView.isHidden = false
        progressBar.isHidden = true
    }
    
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        webView.isHidden = false
        progressBar.isHidden = true
    }
    
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard
            let url = navigationAction.request.url  else {
            decisionHandler(.allow)
            return
        }
        
        if (url.scheme?.lowercased() == "mailto") {
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
            decisionHandler(.cancel)
            return
        }
        
        if checkUrl(url.absoluteString) {
            BrowserPlugin.methodChannel?.invokeMethod("onNavigationCancel", arguments: url.absoluteString)
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

