package com.in_app.webview

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.flutter.plugins.nativeview.NativeView
import java.util.regex.Pattern

/** Handed over from Dart via the `configure` call. */
class WebViewConfig(
    val url: String,
    val headers: HashMap<String, String>?,
    val invalidUrlRegex: List<String>?,
    val color: Int?,
    val bootProbeJs: String?,
    val bootProbeUrl: String?,
)

/**
 * The survey WebView hosted below the transparent Flutter view. The host
 * activity must extend NativeViewFlutterActivity and register this class under
 * [VIEW_KEY]. Flutter renders on top, so dialogs no longer require tearing the
 * webview down.
 */
class WebViewNativeView : NativeView() {
    companion object {
        const val VIEW_KEY = "inapp_webview"

        var instance: WebViewNativeView? = null

        // The native-view factory protocol has no argument channel, so the
        // config is staged by `configure` and consumed by the next onCreateView.
        var pendingConfig: WebViewConfig? = null
    }

    private lateinit var container: FrameLayout
    private lateinit var webView: WebView
    internal var chromeClient: WebViewChromeClient? = null
        private set

    private var config: WebViewConfig? = null
    private var invalidUrlPatternList: List<Pattern>? = null
    private var isClosing = false

    // Consecutive, not lifetime: a load that stays up for stabilityWindowMs
    // refills it, so a long survey survives well-spaced render kills while a
    // crash-on-load loop recreates before the refill and gives up.
    private val maxRecreates = 2
    private val stabilityWindowMs = 10_000L
    // A still-empty #survey-frame this long after onPageFinished means the
    // bundles never executed — the white page no callback reports. The refill
    // is gated on the probe passing, or an unbootable page would loop forever.
    private val bootWatchdogDelayMs = 12_000L
    private val bootProbeGraceDelayMs = 10_000L
    // Bounds a page that keeps "recovering" slower than the stability window.
    private val maxLifetimeRecreates = 10
    // An errored onPageFinished must not count as a successful load: a reset
    // retry budget on an error page loops the host's inline retry forever
    // (seen in prod — 50 retry breadcrumbs all at attempt 1).
    private var mainFrameErrored = false
    // Grace for an 'empty' probe result — one re-probe before recreating.
    private var bootProbeGraceUsed = false
    // Separate from bootProbeGraceUsed: a probe that never inspected the DOM
    // must not spend the 'empty' grace, and bounding these keeps a
    // forever-loading page from silently disarming the watchdog.
    private var bootProbeLoadingWaits = 0
    private val maxBootProbeLoadingWaits = 3
    private var totalRecreates = 0
    private var recreatesLeft = maxRecreates
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stabilityRunnable = Runnable { recreatesLeft = maxRecreates }
    private val bootWatchdogRunnable = Runnable { probeSpaBoot() }

    // WebView.reload() drops the additionalHttpHeaders of the original
    // loadUrl, and the survey needs those auth headers.
    fun reloadWebView() {
        mainHandler.post {
            if (isClosing || !::webView.isInitialized) return@post
            val headers = config?.headers
            val current = webView.url
            when {
                current == null -> loadPage()
                headers != null -> webView.loadUrl(current, headers)
                else -> webView.reload()
            }
        }
    }

    fun evaluateJavascript(js: String, onResult: (String?) -> Unit) {
        mainHandler.post {
            if (isClosing || !::webView.isInitialized) {
                onResult(null)
                return@post
            }
            // Results arrive JSON-encoded; unwrap the common string case.
            webView.evaluateJavascript(js) { raw ->
                onResult(if (raw == null || raw == "null") null else raw.trim('"'))
            }
        }
    }

    private fun checkUrl(url: String): Boolean {
        return invalidUrlPatternList?.let { it.any { p -> p.matcher(url).find() } } ?: false
    }

    // Shared vocabulary with the iOS side.
    private fun categoryFor(code: Int): String = when (code) {
        WebViewClient.ERROR_HOST_LOOKUP,
        WebViewClient.ERROR_CONNECT,
        WebViewClient.ERROR_TIMEOUT,
        WebViewClient.ERROR_IO,
        WebViewClient.ERROR_PROXY_AUTHENTICATION -> "network"
        WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> "tls"
        // BAD_URL / UNSUPPORTED_SCHEME / FILE_NOT_FOUND are permanent, so they
        // stay 'other' and the host cannot retry-loop on them. 'server' comes
        // from onReceivedHttpError, where a 5xx is worth retrying.
        else -> "other"
    }

    override fun onCreateView(): View {
        val activity = getContext() as Activity
        instance = this
        config = pendingConfig
        pendingConfig = null
        invalidUrlPatternList = config?.invalidUrlRegex?.map { Pattern.compile(it) }
        chromeClient = WebViewChromeClient(activity)

        container = TouchFocusLayout(activity)
        config?.color?.let { container.setBackgroundColor(it) }

        // Flutter does not lay this view out, so nothing else insets it. Pad
        // the container, not the WebView, so the themed background still
        // covers the inset strip.
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(container)

        webView = WebView(activity)
        container.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        configureWebView(webView)
        loadPage()
        return container
    }

    private fun loadPage() {
        val c = config ?: return
        mainFrameErrored = false
        c.headers?.let { webView.loadUrl(c.url, it) } ?: webView.loadUrl(c.url)
    }

    private fun probeSpaBoot() {
        val probeJs = config?.bootProbeJs ?: return
        if (isClosing || !::webView.isInitialized) return
        // A navigation in flight means the DOM is stale — waiting beats
        // cancelling a legitimate load.
        if (webView.progress < 100) {
            if (bootProbeLoadingWaits < maxBootProbeLoadingWaits) {
                bootProbeLoadingWaits += 1
                mainHandler.postDelayed(bootWatchdogRunnable, bootProbeGraceDelayMs)
            }
            return
        }
        // The completion can outlive a recreate; its verdict must not be
        // charged to a WebView the probe never ran against.
        val probed: WebView = webView
        probed.evaluateJavascript(probeJs) { result ->
            if (isClosing || !::webView.isInitialized || probed !== webView) {
                return@evaluateJavascript
            }
            when (result?.trim('"')) {
                "ok" -> recreatesLeft = maxRecreates
                "empty" ->
                    if (bootProbeGraceUsed) {
                        recreateWebView("boot-watchdog")
                    } else {
                        bootProbeGraceUsed = true
                        mainHandler.postDelayed(bootWatchdogRunnable, bootProbeGraceDelayMs)
                    }
                else -> Unit // 'none' or unexpected: marker absent, take no action
            }
        }
    }

    // Blank-page recovery. The reloaded URL carries sessionId, so the survey
    // resumes server-side rather than restarting.
    private fun recreateWebView(reason: String = "recreate") {
        if (isClosing) return
        // Only a load that survives the full window counts as recovered.
        mainHandler.removeCallbacks(stabilityRunnable)
        mainHandler.removeCallbacks(bootWatchdogRunnable)
        if (recreatesLeft <= 0 || totalRecreates >= maxLifetimeRecreates) {
            // Checked before destroying: an in-flight host reload() must not
            // land on a destroyed instance.
            BrowserPlugin.onLoadError(-1, "android", "$reason: recovery exhausted", "process")
            return
        }
        chromeClient?.resetFileChooser()
        // Detached first so in-flight callbacks — and the clients it shares
        // with the replacement — cannot fire against a dead WebView.
        val old = webView
        old.stopLoading()
        old.webChromeClient = null
        old.webViewClient = WebViewClient()
        (old.parent as? ViewGroup)?.removeView(old)
        old.destroy()
        recreatesLeft -= 1
        totalRecreates += 1
        webView = WebView(container.context)
        container.addView(
            webView,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        configureWebView(webView)
        BrowserPlugin.onWebViewReload(reason) // recovery telemetry
        loadPage()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(wv: WebView) {
        config?.color?.let { wv.setBackgroundColor(it) }
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.allowContentAccess = true
        wv.settings.allowFileAccess = true
        // The transparent FlutterView above holds focus by default, and the
        // WebView only raises the IME when it can take focus itself.
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        wv.webChromeClient = chromeClient

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val newUrl = request?.url.toString()

                if (newUrl.startsWith("mailto:")) {
                    container.context.startActivity(Intent(Intent.ACTION_VIEW, newUrl.toUri()))
                    return true
                } else if (checkUrl(newUrl)) {
                    BrowserPlugin.onNavigationCancel(newUrl)
                    return true
                }

                return false
            }

            // Without this a stale probe can refill the budget against an
            // error page, and an uncommitted-navigation error (intent://)
            // misclassifies the next successful load as errored.
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                mainFrameErrored = false
                bootProbeLoadingWaits = 0
                mainHandler.removeCallbacks(stabilityRunnable)
                mainHandler.removeCallbacks(bootWatchdogRunnable)
            }

            // Arms the recreate-budget refill, which only fires if this load
            // stays up — recreateWebView cancels it on a re-crash.
            override fun onPageFinished(view: WebView?, url: String?) {
                if (isClosing || mainFrameErrored) return
                BrowserPlugin.onWebViewLoaded()
                mainHandler.removeCallbacks(stabilityRunnable)
                mainHandler.removeCallbacks(bootWatchdogRunnable)
                bootProbeGraceUsed = false
                bootProbeLoadingWaits = 0
                val probeUrl = config?.bootProbeUrl
                if (config?.bootProbeJs != null && probeUrl != null && url?.contains(probeUrl) == true) {
                    mainHandler.postDelayed(bootWatchdogRunnable, bootWatchdogDelayMs)
                } else {
                    mainHandler.postDelayed(stabilityRunnable, stabilityWindowMs)
                }
            }

            // Sub-resource errors are ignored to avoid noise.
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame != true) return
                mainFrameErrored = true
                val code = error?.errorCode ?: 0
                BrowserPlugin.onLoadError(
                    code,
                    "android",
                    error?.description?.toString() ?: "",
                    categoryFor(code)
                )
            }

            // A main-frame HTTP failure never reaches onReceivedError: the
            // error body loads and onPageFinished fires as if all was well.
            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?
            ) {
                if (request?.isForMainFrame != true) return
                val status = errorResponse?.statusCode ?: return
                if (status < 500) return
                mainFrameErrored = true
                BrowserPlugin.onLoadError(
                    status,
                    "android",
                    errorResponse.reasonPhrase ?: "HTTP $status",
                    "server"
                )
            }

            // Unhandled, a render-process kill takes the host app down;
            // returning true keeps it alive.
            @RequiresApi(Build.VERSION_CODES.O)
            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                recreateWebView()
                return true
            }
        }
    }

    override fun onDispose() {
        isClosing = true
        mainHandler.removeCallbacks(stabilityRunnable)
        mainHandler.removeCallbacks(bootWatchdogRunnable)
        chromeClient?.resetFileChooser()
        chromeClient = null
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        if (instance === this) instance = null
    }
}

/**
 * Touches arrive synthetically from the activity's gesture handler, so the
 * framework's touch-mode focus handoff never runs and page text inputs get a
 * caret but no keyboard.
 *
 * Focus is taken on ACTION_UP, not DOWN: Flutter claims a pointer over an async
 * channel hop that can never beat the DOWN, so every tap on Flutter UI above
 * the page is forwarded here first and focusing then would break the Flutter
 * IME. A claim arrives as ACTION_CANCEL, so a gesture surviving to UP is one
 * Flutter did not want.
 */
private class TouchFocusLayout(context: Context) : FrameLayout(context) {
    private var gestureClaimedByFlutter = false

    // Always child 0, so reading it off the hierarchy avoids a callback that
    // would capture — and outlive with — the enclosing native view.
    private val webView: WebView? get() = getChildAt(0) as? WebView

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> gestureClaimedByFlutter = false
            MotionEvent.ACTION_CANCEL -> gestureClaimedByFlutter = true
            MotionEvent.ACTION_UP ->
                if (!gestureClaimedByFlutter) {
                    webView?.let { if (!it.hasFocus()) it.requestFocus() }
                }
        }
        return super.dispatchTouchEvent(ev)
    }
}
