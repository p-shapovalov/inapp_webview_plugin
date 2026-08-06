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
    private var bootProbeGraceUsed = false
    private var totalRecreates = 0
    private var recreatesLeft = maxRecreates
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stabilityRunnable = Runnable { recreatesLeft = maxRecreates }
    private val bootWatchdogRunnable = Runnable { probeSpaBoot() }

    fun reloadWebView() {
        mainHandler.post { if (!isClosing && ::webView.isInitialized) webView.reload() }
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
        WebViewClient.ERROR_BAD_URL,
        WebViewClient.ERROR_UNSUPPORTED_SCHEME,
        WebViewClient.ERROR_FILE_NOT_FOUND -> "server"
        else -> "other"
    }

    override fun onCreateView(): View {
        val activity = getContext() as Activity
        instance = this
        config = pendingConfig
        pendingConfig = null
        invalidUrlPatternList = config?.invalidUrlRegex?.map { Pattern.compile(it) }
        chromeClient = WebViewChromeClient(activity)

        container = TouchFocusLayout(activity) { webViewOrNull() }
        config?.color?.let { container.setBackgroundColor(it) }

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

    private fun webViewOrNull(): WebView? = if (::webView.isInitialized) webView else null

    private fun loadPage() {
        val c = config ?: return
        mainFrameErrored = false
        c.headers?.let { webView.loadUrl(c.url, it) } ?: webView.loadUrl(c.url)
    }

    private fun probeSpaBoot() {
        val probeJs = config?.bootProbeJs ?: return
        if (isClosing || !::webView.isInitialized) return
        // A navigation in flight (e.g. the SPA's own reloaded=true recovery)
        // means the DOM we'd probe is stale — give it one grace period instead
        // of recreating (and thereby cancelling) a legitimate load.
        if (webView.progress < 100) {
            if (!bootProbeGraceUsed) {
                bootProbeGraceUsed = true
                mainHandler.postDelayed(bootWatchdogRunnable, bootProbeGraceDelayMs)
            }
            return
        }
        webView.evaluateJavascript(probeJs) { result ->
            if (isClosing || !::webView.isInitialized) return@evaluateJavascript
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
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
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
 * handler, not the normal window traversal), so the framework's
 * touch-mode focus handoff never runs — without this, page text inputs get
 * caret and taps but no keyboard.
 */
private class TouchFocusLayout(
    context: Context,
    private val webView: () -> WebView?,
) : FrameLayout(context) {
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            webView()?.let { if (!it.hasFocus()) it.requestFocus() }
        }
        return super.dispatchTouchEvent(ev)
    }
}
