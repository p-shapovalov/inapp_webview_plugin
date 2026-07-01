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

    // Cap self-healing recreations so a genuinely-bad page (FB15670666, an iOS
    // 18.x WebContent-process crash that recurs on the same page) can't loop.
    // The budget is CONSECUTIVE, not lifetime: once a recreated page loads and
    // stays up for stabilityWindow, didFinish's stabilityTimer refills it, so a
    // long survey that hits well-spaced crashes keeps recovering. A tight loop
    // (crash before the timer fires) never refills → gives up after maxRecreates.
    private static let maxRecreates = 2
    private static let stabilityWindow: TimeInterval = 10
    private var recreatesLeft = WebViewController.maxRecreates
    private var stabilityTimer: Timer?
    private var isClosing = false
    private var hasLoaded = false
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
    
    // Recreatable (not lazy): a jetsam'd WebContent process leaves a dead
    // WKWebView that only a fresh instance can recover — see recreateWebView().
    private var webView: WKWebView!

    private func makeWebView() -> WKWebView {
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
    }
    
    private func uiColor(fromInt value: Int64) -> UIColor {
        return UIColor(red: CGFloat((value & 0xFF0000) >> 16) / 0xFF,
                       green: CGFloat((value & 0x00FF00) >> 8) / 0xFF,
                       blue: CGFloat(value & 0x0000FF) / 0xFF,
                       alpha: CGFloat((value & 0xFF000000) >> 24) / 0xFF)
    }
    
    override func viewDidLoad() {
        webView = makeWebView()
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
        // A backgrounded WKWebView is the #1 jetsam trigger and often does NOT
        // fire webViewWebContentProcessDidTerminate — probe on foreground and
        // recreate if the WebContent process is dead (blank-page recovery).
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appWillEnterForeground),
            name: UIApplication.willEnterForegroundNotification,
            object: nil)
    }

    @objc private func appWillEnterForeground() {
        guard !isClosing, hasLoaded else { return }
        // A dead WebContent process fails JS eval; a live one returns a string.
        webView.evaluateJavaScript("document.readyState") { [weak self] _, error in
            if error != nil { self?.recreateWebView() }
        }
    }
    
    private func setupUI() {
        progressBar.translatesAutoresizingMaskIntoConstraints = false
        attachWebView()
        view.addSubview(progressBar)

        progressBar.topAnchor.constraint(equalTo: self.view.safeAreaLayoutGuide.topAnchor, constant: 0.0).isActive = true
        progressBar.leadingAnchor.constraint(equalTo: self.view.safeAreaLayoutGuide.leadingAnchor, constant: 0.0).isActive = true
        progressBar.trailingAnchor.constraint(equalTo: self.view.safeAreaLayoutGuide.trailingAnchor, constant: 0.0).isActive = true
    }

    // Add the current webView below the progress bar with edge constraints.
    // Reused when recreateWebView() swaps in a fresh instance.
    private func attachWebView() {
        webView.translatesAutoresizingMaskIntoConstraints = false
        view.insertSubview(webView, at: 0)
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
        hasLoaded = true
        stopIndefiniteProgress()
        // Tell the host a load succeeded (host resets its transient-network
        // inline-retry budget — see base_web_survey_page onWebViewLoaded).
        BrowserPlugin.methodChannel?.invokeMethod("onWebViewLoaded", arguments: nil)
        // Refill the recreate budget only if this load STAYS up: a tight
        // crash-on-load loop recreates before the timer fires (recreateWebView
        // invalidates it), so it never refills; a stable load does.
        stabilityTimer?.invalidate()
        stabilityTimer = Timer.scheduledTimer(withTimeInterval: Self.stabilityWindow, repeats: false) { [weak self] _ in
            self?.recreatesLeft = Self.maxRecreates
        }
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

    // Open WebKit regression (FB15670666) on iOS 18.x — the WebContent process
    // can be killed (memory pressure / backgrounding). reload() is unreliable
    // once the process is dead, so recover by recreating the WKWebView.
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        recreateWebView()
    }

    // Blank-page recovery: swap in a fresh WKWebView and reload the URL (which
    // carries sessionId, so the survey resumes server-side rather than
    // restarting). Capped so a genuinely-bad page can't loop; on exhaustion,
    // report a fatal load error so the host can surface its retry dialog.
    private func recreateWebView() {
        guard !isClosing else { return }
        // A crash cancels any pending budget refill — only a load that survives
        // the full stabilityWindow counts as recovered.
        stabilityTimer?.invalidate()
        guard recreatesLeft > 0 else {
            webView.isHidden = false
            stopIndefiniteProgress()
            BrowserPlugin.methodChannel?.invokeMethod("onLoadError", arguments: [
                "code": -1,
                "domain": "WKWebViewProcessDidTerminate",
                "message": "Web content process died; recovery exhausted",
                "category": "process",
            ])
            return
        }
        recreatesLeft -= 1
        let old: WKWebView? = webView
        old?.stopLoading()
        old?.navigationDelegate = nil
        old?.removeFromSuperview()
        webView = makeWebView()
        if color != nil {
            let c = uiColor(fromInt: color!)
            webView.backgroundColor = c
            webView.scrollView.backgroundColor = c
        }
        attachWebView()
        // Recovery telemetry — the recreate re-enters the survey (resumes via
        // sessionId); the host logs it (see base_web_survey_page onWebViewReload).
        BrowserPlugin.methodChannel?.invokeMethod("onWebViewReload", arguments: [
            "reason": "recreate",
            "attempt": Self.maxRecreates - recreatesLeft,
        ])
        loadPage()
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
            isClosing = true
            // Stop in-flight load and detach delegate so callbacks that race
            // dismissal (didFinish/didFail firing after the user pops) don't
            // touch a half-torn-down VC. Late-fire after pop is a documented
            // source of crashes and Sentry noise.
            webView.stopLoading()
            webView.navigationDelegate = nil
            stabilityTimer?.invalidate()
            stopIndefiniteProgress()
            BrowserPlugin.methodChannel?.invokeMethod("onFinish", arguments: nil)
        }
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
        progressBarTimer?.invalidate()
        stabilityTimer?.invalidate()
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
        isClosing = true
        self.navigationController?.popViewController(animated: true)
    }
}
