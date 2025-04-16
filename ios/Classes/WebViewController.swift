import UIKit
import WebKit
import Flutter

class WebViewController: UIViewController, WKNavigationDelegate {
    var url: String = ""

    private var closeButton = UIButton(type: .system)

    private var closeButtonTopConstraint: NSLayoutConstraint?

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

    override func viewDidLoad() {
        super.viewDidLoad()
        setupUI()
        loadPage()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()

        let safeAreaTop = view.safeAreaInsets.top
        closeButtonTopConstraint?.constant = safeAreaTop + 10
    }

    private func setupUI() {
        webView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(webView)

        closeButton = UIButton(type: .system)
        if #available(iOS 13.0, *) {
            closeButton.setImage(UIImage(systemName: "xmark"), for: .normal)
        } else {
            closeButton.setTitle("X", for: .normal)
        }

        updateCloseButtonAppearance()

        closeButton.addTarget(self, action: #selector(closeWebView), for: .touchUpInside)
        closeButton.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(closeButton)

        if #available(iOS 15.0, *) {
            NSLayoutConstraint.activate([
                webView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
                webView.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
                webView.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
                webView.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor),
                
                closeButton.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16), 
                closeButton.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: -14), 
                closeButton.widthAnchor.constraint(equalToConstant: 28), 
                closeButton.heightAnchor.constraint(equalToConstant: 28) 
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

    private func updateCloseButtonAppearance() {
        let isDarkMode = traitCollection.userInterfaceStyle == .dark
        closeButton.tintColor = isDarkMode ? .white : .black
        closeButton.backgroundColor = isDarkMode ? .black : .white
        closeButton.layer.cornerRadius = 14
        closeButton.layer.masksToBounds = true
        closeButton.contentEdgeInsets = UIEdgeInsets(top: 6, left: 6, bottom: 6, right: 6)
           closeButton.imageEdgeInsets = UIEdgeInsets(top: 2, left: 2, bottom: 2, right: 2)

        closeButton.layer.shadowColor = UIColor.black.cgColor
        closeButton.layer.shadowOpacity = 0.3
        closeButton.layer.shadowOffset = CGSize(width: 0, height: 2)
        closeButton.layer.shadowRadius = 3
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

        if isDeepLink(url) {
            if let deepLinkUrl = URL(string: url) {
                DispatchQueue.main.async {
                    UIApplication.shared.open(deepLinkUrl, options: [:])
                }
            }
            decisionHandler(.cancel) 
            return
        }

        decisionHandler(.allow)
    }

    private func isDeepLink(_ url: String) -> Bool {
        let allowedSchemes = ["http", "https", "file", "chrome", "data", "javascript", "about"]
        return !allowedSchemes.contains { url.hasPrefix("\($0)://") }
    }

    @objc private func closeWebView() {
        BrowserPlugin.methodChannel?.invokeMethod("onFinish", arguments: nil)
        dismiss(animated: true, completion: nil)
    }
}

