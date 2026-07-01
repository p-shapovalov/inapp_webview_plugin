import UIKit
import WebKit
import Flutter
class WebViewController: UIViewController, WKNavigationDelegate {
    var url: String = ""
    var color: Int64?
    var headers: [String:String]?
    var invalidUrlRegex: Array<NSRegularExpression?> = []
    
    
    var progressBar = UIProgressView(progressViewStyle: .bar)
    var progressBarTimer: Timer?

    // The iOS 18.x WebContent-process crash (FB15670666) is repeatable on a
    // bad page — without a cap, reload() → crash → reload() spins forever.
    private var processCrashReloadsLeft = 2
    func startIndefiniteProgress() {
        progressBarTimer = Timer.scheduledTimer(timeInterval: 0.03, target: self, selector: #selector(updateProgressView), userInfo: nil, repeats: true)
    }

    private func stopIndefiniteProgress() {
        progressBarTimer?.invalidate()
        progressBarTimer = nil
        progressBar.isHidden = true
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
        stopIndefiniteProgress()
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        webView.isHidden = false
        stopIndefiniteProgress()
        notifyLoadError(error)
    }

    // iOS 18.7.x WKWebView is more aggressive about provisional failures
    // (TLS / DNS / ATS / connection drops). Without this, the webview stays
    // hidden in loadPage() and the user sees only the background color.
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        webView.isHidden = false
        stopIndefiniteProgress()
        notifyLoadError(error)
    }

    // Open WebKit regression (FB15670666) on iOS 18.x — the WebContent
    // process can be killed after repeated reloads. Apple's recommended
    // recovery is to reload, but the crash often recurs on the same page,
    // so cap retries and surface the failure once we give up.
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        guard processCrashReloadsLeft > 0 else {
            webView.isHidden = false
            stopIndefiniteProgress()
            BrowserPlugin.methodChannel?.invokeMethod("onLoadError", arguments: [
                "code": -1,
                "domain": "WKWebViewProcessDidTerminate",
                "message": "Web content process terminated repeatedly",
                "category": "process",
            ])
            return
        }
        processCrashReloadsLeft -= 1
        // Tell the host we are auto-reloading after a crash: this reload can
        // silently re-enter/restart an in-flight survey (and, if the prior
        // completion POST's 200 was lost, re-drive completion). Host logs it.
        BrowserPlugin.methodChannel?.invokeMethod("onWebViewReload", arguments: [
            "reason": "process_terminate",
            "attempt": 2 - processCrashReloadsLeft,
        ])
        webView.reload()
    }

    private func notifyLoadError(_ error: Error) {
        let ns = error as NSError
        // -999 fires when we intentionally cancel a navigation in
        // decidePolicyFor (deeplinks) or the user backs out — not a load
        // failure the caller should react to.
        if ns.domain == NSURLErrorDomain && ns.code == NSURLErrorCancelled {
            return
        }
        BrowserPlugin.methodChannel?.invokeMethod("onLoadError", arguments: [
            "code": ns.code,
            "domain": ns.domain,
            "message": ns.localizedDescription,
            "category": Self.errorCategory(for: ns),
        ])
    }

    private static func errorCategory(for error: NSError) -> String {
        guard error.domain == NSURLErrorDomain else { return "other" }
        switch error.code {
        case NSURLErrorNotConnectedToInternet,
             NSURLErrorNetworkConnectionLost,
             NSURLErrorTimedOut,
             NSURLErrorCannotFindHost,
             NSURLErrorCannotConnectToHost,
             NSURLErrorDNSLookupFailed,
             NSURLErrorInternationalRoamingOff,
             NSURLErrorCallIsActive,
             NSURLErrorDataNotAllowed:
            return "network"
        case NSURLErrorBadServerResponse,
             NSURLErrorZeroByteResource,
             NSURLErrorRedirectToNonExistentLocation,
             NSURLErrorBadURL,
             NSURLErrorUnsupportedURL:
            return "server"
        case NSURLErrorServerCertificateHasBadDate,
             NSURLErrorServerCertificateUntrusted,
             NSURLErrorServerCertificateHasUnknownRoot,
             NSURLErrorServerCertificateNotYetValid,
             NSURLErrorClientCertificateRejected,
             NSURLErrorClientCertificateRequired,
             NSURLErrorAppTransportSecurityRequiresSecureConnection,
             NSURLErrorSecureConnectionFailed:
            return "tls"
        default:
            return "other"
        }
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
    
    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        if isMovingFromParent {
            // Stop in-flight load and detach delegate so callbacks that race
            // dismissal (didFinish/didFail firing after the user pops) don't
            // touch a half-torn-down VC. Late-fire after pop is a documented
            // source of crashes and Sentry noise.
            webView.stopLoading()
            webView.navigationDelegate = nil
            stopIndefiniteProgress()
            BrowserPlugin.methodChannel?.invokeMethod("onFinish", arguments: nil)
        }
    }

    deinit {
        progressBarTimer?.invalidate()
    }

    // Reload the survey page IN PLACE to recover from a transient load failure
    // (e.g. an iOS 18.x provisional network failure) without tearing the survey
    // down. Reloads the current page, or re-loads the original URL if the
    // provisional load never committed.
    func reload() {
        startIndefiniteProgress()
        webView.isHidden = true
        if let current = webView.url, !current.absoluteString.isEmpty {
            webView.reload()
        } else {
            loadPage()
        }
    }

    func close() {
        self.navigationController?.popViewController(animated: true)
    }
}
