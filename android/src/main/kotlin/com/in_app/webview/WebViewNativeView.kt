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

/** Per-webview configuration handed over from Dart via the `configure` call. */
class WebViewConfig(
    val url: String,
    val headers: HashMap<String, String>?,
    val invalidUrlRegex: List<String>?,
    val color: Int?,
    val bootProbeJs: String?,
    val bootProbeUrl: String?,
)

/**
 * The survey WebView hosted below the transparent Flutter view by
 * flutter_native_view_android. The host activity must extend
 * NativeViewFlutterActivity and register this class under [VIEW_KEY]:
 *
 * ```
 * override fun onRegisterNativeViews() {
 *     registerNativeViewFactory(WebViewNativeView.VIEW_KEY) { WebViewNativeView() }
 * }
 * ```
 *
 * Because Flutter renders ON TOP of this view, dialogs and overlays no longer
 * require tearing the webview down — the recovery/watchdog logic ported from
 * the old WebViewActivity is unchanged.
 */
class WebViewNativeView : NativeView() {
    companion object {
        const val VIEW_KEY = "inapp_webview"

        // The current live webview, so the plugin can reload it in place and
        // route file-chooser activity results into its chrome client.
        var instance: WebViewNativeView? = null

        // Staged by BrowserPlugin's `configure` call, consumed by the next
        // onCreateView — the native-view factory protocol has no argument
        // channel of its own.
        var pendingConfig: WebViewConfig? = null
    }

    private lateinit var container: FrameLayout
    private lateinit var webView: WebView
    internal var chromeClient: WebViewChromeClient? = null
        private set

    private var config: WebViewConfig? = null
    private var invalidUrlPatternList: List<Pattern>? = null
    private var isClosing = false

    // Cap self-healing recreations so a genuinely-bad page can't loop. The
    // budget is CONSECUTIVE, not lifetime: a page that loads and stays up for
    // stabilityWindowMs (onPageFinished's stabilityRunnable) refills it, so a
    // long survey survives well-spaced render kills; a tight crash-on-load loop
    // recreates before the runnable fires and never refills.
    private val maxRecreates = 2
    private val stabilityWindowMs = 10_000L
    // The survey page HTML ships an EMPTY #survey-frame that the SPA renders
    // into. If it is still empty this long after onPageFinished, the bundles
    // never executed (edge asset failure / JS stall) — a "white page" no
    // WebViewClient callback ever reports. Recreate under the same budget.
    // On survey pages the recreate-budget refill is gated on the probe PASSING
    // (not on a bare timer): a refill racing ahead of the probe would make the
    // budget unexhaustible and a permanently-unbootable page would loop forever.
    private val bootWatchdogDelayMs = 12_000L
    private val bootProbeGraceDelayMs = 10_000L
    // Consecutive budget refills make loops possible when the page keeps
    // "recovering" (e.g. a partner page that render-crashes slower than the
    // stability window) — the lifetime cap bounds them.
    private val maxLifetimeRecreates = 10
    // The CURRENT page load errored (set in onReceivedError, cleared when the
    // next navigation starts): an errored onPageFinished must not count as a
    // successful load — a reset retry budget on an error page loops the host's
    // inline retry forever (seen in prod: 50 retry breadcrumbs all at attempt 1).
    private var mainFrameErrored = false
    // Grace for an 'empty' probe result — one re-probe before recreating.
    private var bootProbeGraceUsed = false
    // Reschedules spent waiting for an in-flight navigation. Tracked apart from
    // bootProbeGraceUsed: a probe that never inspected the DOM must not consume
    // the 'empty' grace, and bounding the waits separately keeps a page that
    // loads forever from silently disarming the watchdog.
    private var bootProbeLoadingWaits = 0
    private val maxBootProbeLoadingWaits = 3
    private var totalRecreates = 0
    private var recreatesLeft = maxRecreates
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stabilityRunnable = Runnable { recreatesLeft = maxRecreates }
    private val bootWatchdogRunnable = Runnable { probeSpaBoot() }

    // WebView.reload() re-issues the request WITHOUT the additionalHttpHeaders
    // of the original loadUrl, so a plain reload would drop the auth headers
    // the survey needs. Re-load the current URL with them instead.
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
            // Results arrive JSON-encoded; unwrap the common string case so the
            // Dart side sees what the script actually returned.
            webView.evaluateJavascript(js) { raw ->
                onResult(if (raw == null || raw == "null") null else raw.trim('"'))
            }
        }
    }

    private fun checkUrl(url: String): Boolean {
        return invalidUrlPatternList?.let { it.any { p -> p.matcher(url).find() } } ?: false
    }

    // Map WebViewClient error codes onto the shared category vocabulary
    // (network | tls | server | process | other) used by the iOS side.
    private fun categoryFor(code: Int): String = when (code) {
        WebViewClient.ERROR_HOST_LOOKUP,
        WebViewClient.ERROR_CONNECT,
        WebViewClient.ERROR_TIMEOUT,
        WebViewClient.ERROR_IO,
        WebViewClient.ERROR_PROXY_AUTHENTICATION -> "network"
        WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> "tls"
        // ERROR_BAD_URL / ERROR_UNSUPPORTED_SCHEME / ERROR_FILE_NOT_FOUND are
        // permanent: they stay 'other' so the host's isRecoverable retry does
        // not loop on a URL that can never load. 'server' is reported from
        // onReceivedHttpError, where a real 5xx is worth retrying.
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

        // The native view fills the window (it is not laid out by Flutter), so
        // nothing insets it for the status/navigation bars or the keyboard —
        // the page would run under them. Pad the container instead of the
        // WebView so the themed background still covers the inset area.
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
        // A navigation in flight (e.g. the SPA's own reloaded=true recovery)
        // means the DOM we'd probe is stale — wait rather than recreate (and
        // thereby cancel) a legitimate load.
        if (webView.progress < 100) {
            if (bootProbeLoadingWaits < maxBootProbeLoadingWaits) {
                bootProbeLoadingWaits += 1
                mainHandler.postDelayed(bootWatchdogRunnable, bootProbeGraceDelayMs)
            }
            return
        }
        // The completion can outlive a recreate; charging its verdict to the
        // replacement would refill (or re-spend) the budget for a WebView the
        // probe never ran against.
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

    // Blank-page recovery: the render process was killed (onRenderProcessGone)
    // or the SPA never booted (boot watchdog). Recreate the WebView and reload
    // the URL (which carries sessionId, so the survey resumes server-side
    // rather than restarting). Capped so a genuinely-bad page can't loop; on
    // exhaustion, report a fatal load error so the host can surface its dialog.
    private fun recreateWebView(reason: String = "recreate") {
        if (isClosing) return
        // A crash cancels any pending budget refill — only a load that survives
        // the full stability window counts as recovered.
        mainHandler.removeCallbacks(stabilityRunnable)
        mainHandler.removeCallbacks(bootWatchdogRunnable)
        if (recreatesLeft <= 0 || totalRecreates >= maxLifetimeRecreates) {
            // Budget checked BEFORE destroying: the exhausted state must keep a
            // usable WebView — an in-flight host reload() would otherwise land
            // on a destroyed instance.
            BrowserPlugin.onLoadError(-1, "android", "$reason: recovery exhausted", "process")
            return
        }
        chromeClient?.resetFileChooser()
        // Detach the old instance before destroying it, so in-flight callbacks
        // (and the clients it shares with the replacement) can't fire against a
        // dead WebView — the counterpart of iOS's navigationDelegate = nil.
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
        // The transparent FlutterView above shares the window and holds focus
        // by default; the WebView only summons the IME for page text inputs
        // when it can take focus itself (see TouchFocusLayout).
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

            // A new navigation supersedes the previous document's pending
            // checks and error state — without this, a stale probe can run
            // against an error page (refilling the budget vacuously), and an
            // uncommitted-navigation error (intent:// etc.) would misclassify
            // the NEXT successful load as errored.
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                mainFrameErrored = false
                bootProbeLoadingWaits = 0
                mainHandler.removeCallbacks(stabilityRunnable)
                mainHandler.removeCallbacks(bootWatchdogRunnable)
            }

            // A successful load ends any transient-failure streak: tell the
            // host (resets its inline-retry budget) and arm the recreate-budget
            // refill, which only fires if this load stays up (recreateWebView
            // cancels it on a re-crash).
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

            // Report main-frame load failures to the host (parity with iOS
            // notifyLoadError). Sub-resource errors are ignored to avoid noise.
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

            // A main-frame HTTP failure never reaches onReceivedError — the
            // response body loads and onPageFinished fires as if all was well.
            // 5xx is the genuinely retryable 'server' case; report it and mark
            // the load errored so the error body doesn't reset the host's
            // retry budget.
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

            // A render-process kill (OOM / system pressure) would crash the host
            // app if unhandled. Recover in place by recreating the WebView;
            // returning true keeps the app alive.
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
 * Container that moves focus to the WebView when a forwarded touch lands on
 * it. Touch events arrive synthetically (dispatched by the activity's gesture
 * handler, not the normal window traversal), so the framework's touch-mode
 * focus handoff never runs — without this, page text inputs get caret and taps
 * but no keyboard.
 *
 * Focus is taken on ACTION_UP rather than ACTION_DOWN. Flutter claims a pointer
 * over an async method-channel hop that can never beat the DOWN, so every tap
 * on Flutter UI above the page — including the exit dialog — is forwarded here
 * first; focusing on DOWN would pull focus off the FlutterView and break the
 * Flutter IME. A claim arrives as an ACTION_CANCEL, so a gesture that survives
 * to UP is one Flutter did not want. The WebView still sees the UP afterwards,
 * which is when it focuses the editable element and raises the keyboard.
 */
private class TouchFocusLayout(context: Context) : FrameLayout(context) {
    private var gestureClaimedByFlutter = false

    // The WebView is always child 0 — added on an empty container and
    // re-inserted at 0 on recreate — so it can be read straight off the
    // hierarchy rather than through a callback that would capture (and outlive
    // with) the enclosing native view.
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
