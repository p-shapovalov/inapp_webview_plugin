import Flutter
import UIKit
import WebKit

class BrowserWebViewFactory: NSObject, FlutterPlatformViewFactory {
    func createArgsCodec() -> FlutterMessageCodec & NSObjectProtocol {
        FlutterStandardMessageCodec.sharedInstance()
    }

    func create(withFrame frame: CGRect, viewIdentifier viewId: Int64, arguments args: Any?) -> FlutterPlatformView {
        BrowserWebViewPlatformView(frame: frame, args: args as? [String: Any] ?? [:])
    }
}

/// The survey webview as a standard platform view: the WKWebView is composited
/// inside the Flutter scene, so Flutter dialogs/routes render on top of the
/// live page — no teardown needed to show an exit dialog. All recovery logic
/// (recreate budget, SPA boot watchdog, foreground liveness probe) is ported
/// unchanged from the pushed-view-controller implementation.
class BrowserWebViewPlatformView: NSObject, FlutterPlatformView, WKNavigationDelegate {
    // The live instance, so the plugin's `reload` call can reach it. One
    // webview at a time — same contract as the Dart side's static callbacks.
    static weak var current: BrowserWebViewPlatformView?

    private let container: UIView
    private var url: String = ""
    private var color: Int64?
    private var headers: [String: String]?
    private var invalidUrlRegex: [NSRegularExpression?] = []

    // Cap self-healing recreations so a genuinely-bad page (FB15670666, an iOS
    // 18.x WebContent-process crash that recurs on the same page) can't loop.
    // The budget is CONSECUTIVE, not lifetime: once a recreated page loads and
    // stays up for stabilityWindow, didFinish's stabilityTimer refills it, so a
    // long survey that hits well-spaced crashes keeps recovering. A tight loop
    // (crash before the timer fires) never refills → gives up after maxRecreates.
    private static let maxRecreates = 2
    private static let stabilityWindow: TimeInterval = 10
    // SPA boot watchdog: bootProbeJs is supplied by the Dart host through the
    // widget config together with bootProbeUrl (URL-substring gate), so the
    // page contract lives in ONE place. The probe must return 'ok' (booted),
    // 'empty' (loaded but SPA never rendered -> recreate) or 'none' (marker
    // absent -> no action). On probed pages the recreate-budget refill is
    // gated on 'ok' — a bare-timer refill would make the budget unexhaustible
    // and a permanently-unbootable page would loop forever. A single 'empty'
    // gets one grace re-probe before recreating (slow bundles on slow links).
    private static let bootWatchdogDelay: TimeInterval = 12
    private static let bootProbeGraceDelay: TimeInterval = 10
    // Consecutive budget refills make loops possible when the page keeps
    // "recovering" (e.g. a partner page that render-crashes slower than the
    // stability window) — the lifetime cap bounds them.
    private static let maxLifetimeRecreates = 10
    private var bootProbeJs: String?
    private var bootProbeUrl: String?
    // Grace for an 'empty' probe result — one re-probe before recreating.
    private var bootProbeGraceUsed = false
    // Reschedules spent waiting for an in-flight navigation. Tracked apart from
    // bootProbeGraceUsed: a probe that never inspected the DOM must not consume
    // the 'empty' grace, and bounding the waits separately keeps a page that
    // loads forever from silently disarming the watchdog.
    private var bootProbeLoadingWaits = 0
    private static let maxBootProbeLoadingWaits = 3
    // A main-frame HTTP failure still fires didFinish; without this the error
    // body would report as a successful load.
    private var mainFrameHttpErrored = false
    private var totalRecreates = 0
    private var recreatesLeft = BrowserWebViewPlatformView.maxRecreates
    private var stabilityTimer: Timer?
    private var bootWatchdogTimer: Timer?
    private var isClosing = false
    private var hasLoaded = false

    // Recreatable (not lazy): a jetsam'd WebContent process leaves a dead
    // WKWebView that only a fresh instance can recover — see recreateWebView().
    private var webView: WKWebView!

    init(frame: CGRect, args: [String: Any]) {
        container = UIView(frame: frame)
        super.init()

        url = args["url"] as? String ?? ""
        color = (args["color"] as? NSNumber)?.int64Value
        headers = args["headers"] as? [String: String]
        bootProbeJs = args["bootProbeJs"] as? String
        bootProbeUrl = args["bootProbeUrl"] as? String
        invalidUrlRegex = (args["invalidUrlRegex"] as? [String] ?? [])
            .map { try? NSRegularExpression(pattern: $0, options: .caseInsensitive) }

        if let color {
            container.backgroundColor = uiColor(fromInt: color)
        }

        webView = makeWebView()
        applyColor(to: webView)
        attachWebView()
        loadPage()

        Self.current = self

        // A backgrounded WKWebView is the #1 jetsam trigger and often does NOT
        // fire webViewWebContentProcessDidTerminate — probe on foreground and
        // recreate if the WebContent process is dead (blank-page recovery).
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appWillEnterForeground),
            name: UIApplication.willEnterForegroundNotification,
            object: nil)
    }

    func view() -> UIView {
        container
    }

    deinit {
        // Platform views have no explicit dispose hook on iOS — dealloc is the
        // teardown. Stop in-flight loads and detach the delegate so callbacks
        // racing disposal don't touch a dead instance.
        isClosing = true
        NotificationCenter.default.removeObserver(self)
        webView?.stopLoading()
        webView?.navigationDelegate = nil
        stabilityTimer?.invalidate()
        bootWatchdogTimer?.invalidate()
    }

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

    private func applyColor(to webView: WKWebView) {
        guard let color else { return }
        let c = uiColor(fromInt: color)
        webView.backgroundColor = c
        webView.scrollView.backgroundColor = c
    }

    private func uiColor(fromInt value: Int64) -> UIColor {
        return UIColor(red: CGFloat((value & 0xFF0000) >> 16) / 0xFF,
                       green: CGFloat((value & 0x00FF00) >> 8) / 0xFF,
                       blue: CGFloat(value & 0x0000FF) / 0xFF,
                       alpha: CGFloat((value & 0xFF000000) >> 24) / 0xFF)
    }

    // Pin the current webView to the container edges. Safe-area insets are the
    // Flutter widget's responsibility now that the webview is embedded — the
    // host wraps BrowserWebView in SafeArea. Reused when recreateWebView()
    // swaps in a fresh instance.
    private func attachWebView() {
        webView.translatesAutoresizingMaskIntoConstraints = false
        container.insertSubview(webView, at: 0)
        NSLayoutConstraint.activate([
            webView.topAnchor.constraint(equalTo: container.topAnchor),
            webView.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            webView.bottomAnchor.constraint(equalTo: container.bottomAnchor),
        ])
    }

    @objc private func appWillEnterForeground() {
        guard !isClosing, hasLoaded else { return }
        // A dead WebContent process fails JS eval; a live one returns a string.
        // The identity guard drops stale completions: recreateWebView may have
        // already swapped the instance by the time this fires (a single jetsam
        // must charge the recreate budget once, not per pending completion).
        let probed: WKWebView = webView
        probed.evaluateJavaScript("document.readyState") { [weak self] _, error in
            guard let self, probed === self.webView else { return }
            if error != nil { self.recreateWebView() }
        }
    }

    private func loadPage() {
        guard let url = URL(string: url) else {
            return
        }
        var request = URLRequest(url: url)
        // Hidden until didFinish so the configured background color shows
        // instead of a white flash.
        webView.isHidden = true

        if let headers {
            for (key, value) in headers {
                request.setValue(value, forHTTPHeaderField: key)
            }
        }

        webView.load(request)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        webView.isHidden = false
        hasLoaded = true
        // An error body finished loading is not a successful load — reporting
        // it would reset the host's inline-retry budget on every retry.
        guard !mainFrameHttpErrored else { return }
        // Tell the host a load succeeded (host resets its transient-network
        // inline-retry budget — see base_web_survey_page onWebViewLoaded).
        BrowserPlugin.methodChannel?.invokeMethod("onWebViewLoaded", arguments: nil)
        // Refill the recreate budget only if this load STAYS up: a tight
        // crash-on-load loop recreates before the timer fires (recreateWebView
        // invalidates it), so it never refills; a stable load does.
        stabilityTimer?.invalidate()
        bootWatchdogTimer?.invalidate()
        bootProbeGraceUsed = false
        if let probeUrl = bootProbeUrl, bootProbeJs != nil,
           webView.url?.absoluteString.contains(probeUrl) == true {
            scheduleBootProbe(after: Self.bootWatchdogDelay)
        } else {
            stabilityTimer = Timer.scheduledTimer(withTimeInterval: Self.stabilityWindow, repeats: false) { [weak self] _ in
                self?.recreatesLeft = Self.maxRecreates
            }
        }
    }

    private func scheduleBootProbe(after delay: TimeInterval) {
        bootWatchdogTimer?.invalidate()
        bootWatchdogTimer = Timer.scheduledTimer(withTimeInterval: delay, repeats: false) { [weak self] _ in
            self?.probeSpaBoot()
        }
    }

    private func probeSpaBoot() {
        guard !isClosing, let probeJs = bootProbeJs else { return }
        // A navigation in flight (e.g. the SPA's own reloaded=true recovery)
        // means the DOM we'd probe is stale — wait rather than recreate (and
        // thereby cancel) a legitimate load.
        if webView.isLoading {
            if bootProbeLoadingWaits < Self.maxBootProbeLoadingWaits {
                bootProbeLoadingWaits += 1
                scheduleBootProbe(after: Self.bootProbeGraceDelay)
            }
            return
        }
        let probed: WKWebView = webView
        probed.evaluateJavaScript(probeJs) { [weak self] result, error in
            guard let self, probed === self.webView, !self.isClosing else { return }
            if error != nil { return } // dead process -> didTerminate/foreground probe own it
            switch result as? String {
            case "ok":
                self.recreatesLeft = Self.maxRecreates
            case "empty":
                if self.bootProbeGraceUsed {
                    self.recreateWebView(reason: "boot-watchdog")
                } else {
                    self.bootProbeGraceUsed = true
                    self.scheduleBootProbe(after: Self.bootProbeGraceDelay)
                }
            default:
                break // 'none' or unexpected: marker absent, take no action
            }
        }
    }

    // A new navigation supersedes any pending post-load check for the previous
    // document — without this, a stale probe can run against an error page.
    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        mainFrameHttpErrored = false
        bootProbeLoadingWaits = 0
        stabilityTimer?.invalidate()
        bootWatchdogTimer?.invalidate()
    }

    // A main-frame HTTP failure never surfaces as a navigation error — the
    // response body loads and didFinish fires as if all was well. 5xx is the
    // genuinely retryable 'server' case; report it and let the body render so
    // the page can show its own error state.
    func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse,
                 decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        if navigationResponse.isForMainFrame,
           let http = navigationResponse.response as? HTTPURLResponse,
           http.statusCode >= 500 {
            mainFrameHttpErrored = true
            BrowserPlugin.methodChannel?.invokeMethod("onLoadError", arguments: [
                "code": http.statusCode,
                "domain": "HTTP",
                "message": HTTPURLResponse.localizedString(forStatusCode: http.statusCode),
                "category": "server",
            ])
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        webView.isHidden = false
        stabilityTimer?.invalidate()
        bootWatchdogTimer?.invalidate()
        notifyLoadError(error)
    }

    // iOS 18.7.x WKWebView is more aggressive about provisional failures
    // (TLS / DNS / ATS / connection drops). Without this, the webview stays
    // hidden in loadPage() and the user sees only the background color.
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        webView.isHidden = false
        stabilityTimer?.invalidate()
        bootWatchdogTimer?.invalidate()
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
    private func recreateWebView(reason: String = "recreate") {
        guard !isClosing else { return }
        // A crash cancels any pending budget refill — only a load that survives
        // the full stabilityWindow counts as recovered.
        stabilityTimer?.invalidate()
        bootWatchdogTimer?.invalidate()
        guard recreatesLeft > 0, totalRecreates < Self.maxLifetimeRecreates else {
            webView.isHidden = false
            BrowserPlugin.methodChannel?.invokeMethod("onLoadError", arguments: [
                "code": -1,
                "domain": "WKWebViewProcessDidTerminate",
                "message": "\(reason): recovery exhausted",
                "category": "process",
            ])
            return
        }
        recreatesLeft -= 1
        totalRecreates += 1
        let old: WKWebView? = webView
        old?.stopLoading()
        old?.navigationDelegate = nil
        old?.removeFromSuperview()
        webView = makeWebView()
        applyColor(to: webView)
        attachWebView()
        // Recovery telemetry — the recreate re-enters the survey (resumes via
        // sessionId); the host logs it (see base_web_survey_page onWebViewReload).
        BrowserPlugin.methodChannel?.invokeMethod("onWebViewReload", arguments: [
            "reason": reason,
        ])
        loadPage()
    }

    private func notifyLoadError(_ error: Error) {
        let ns = error as NSError
        // -999 fires when we intentionally cancel a navigation in
        // decidePolicyFor (deeplinks) — not a load failure the caller should
        // react to.
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
        // NSURLErrorBadURL / NSURLErrorUnsupportedURL are permanent and stay
        // 'other', so the host's isRecoverable retry does not loop on a URL
        // that can never load. Real HTTP 5xx is reported from
        // decidePolicyFor navigationResponse.
        case NSURLErrorBadServerResponse,
             NSURLErrorZeroByteResource,
             NSURLErrorRedirectToNonExistentLocation:
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
            let url = navigationAction.request.url else {
            decisionHandler(.allow)
            return
        }

        if url.scheme?.lowercased() == "mailto" {
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
        // NSRegularExpression ranges are UTF-16 offsets; String.count counts
        // characters, so a non-ASCII URL would be searched only up to a
        // truncated prefix and a deeplink past it would not be intercepted.
        let match = regex?.firstMatch(
            in: url,
            options: [],
            range: NSRange(location: 0, length: url.utf16.count))
        return match != nil
    }

    // Reload the survey page IN PLACE to recover from a transient load failure
    // (e.g. an iOS 18.x provisional network failure) without tearing the survey
    // down. Reloads the current page, or re-loads the original URL if the
    // provisional load never committed.
    func reload() {
        webView.isHidden = true
        if let current = webView.url, !current.absoluteString.isEmpty {
            webView.reload()
        } else {
            loadPage()
        }
    }
}
