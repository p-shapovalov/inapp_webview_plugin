# browser_plugin

In-app webview **embedded in the Flutter view**, so Flutter UI (dialogs, routes, overlays) renders on top of the live page. Showing an exit dialog no longer requires closing and re-opening the webview.

| Platform | Embedding |
| --- | --- |
| iOS | Standard platform view — `WKWebView` composited inside the Flutter scene. |
| Android | Native `WebView` hosted **below** a transparent `FlutterView` via [`flutter_native_view_android`](https://github.com/p-shapovalov/flutter_native_view_android). |

The page↔app protocol is navigation interception: URLs matching `invalidUrlRegex` are cancelled natively and reported through `BrowserPlugin.onNavigationCancel`.

## Usage

```dart
BrowserOverlayApp(               // claims pointers landing on Flutter UI (Android)
  child: MaterialApp(home: SurveyPage()),
);

// …in the page hosting the webview:
Scaffold(
  backgroundColor: Platform.isAndroid ? Colors.transparent : null,
  body: SafeArea(
    child: BrowserWebView(
      url: url,
      headers: headers,
      invalidUrlRegex: ['myapp://', 'https://other.host'],
      color: Colors.blue,
      bootProbeJs: probeJs,
      bootProbeUrl: '/mobile/survey',
    ),
  ),
);
```

Changing `url` loads the new page; the other properties are create-time. Do not key a `BrowserWebView` to force a reload — on Android all instances share one native view key, so a keyed swap can add the replacement before the outgoing one is removed.

### Events and control

```dart
BrowserPlugin.onNavigationCancel = (url) { /* deeplink from the page */ };
BrowserPlugin.onLoadError = (WebViewLoadError e) {
  if (e.category.isRecoverable) BrowserPlugin.instance.reload(); // in place, no teardown
};
BrowserPlugin.onWebViewLoaded = () { /* a main-frame load succeeded */ };
BrowserPlugin.onWebViewReload = (reason) { /* native self-recovery telemetry */ };

await BrowserPlugin.instance.reload();
await BrowserPlugin.instance.isWebViewAvailable();   // Android probe, true on iOS
await BrowserPlugin.instance.isTWASupported();       // Android only
await BrowserPlugin.instance.openTWA(url);           // Android only, separate activity
```

Callbacks are static single-slot — one live webview at a time.

## Android host setup

The activity owns the native-view container, so it must extend `NativeViewFlutterActivity` (or `NativeViewFlutterFragmentActivity`) and register the factory:

```kotlin
class MainActivity : NativeViewFlutterActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent embedding clears the window background; without an opaque
        // backdrop the pre-first-frame gap composites as black.
        window.setBackgroundDrawable(ColorDrawable(getColor(R.color.splash_background)))
    }

    override fun onRegisterNativeViews() {
        registerNativeViewFactory(WebViewNativeView.VIEW_KEY) { WebViewNativeView() }
    }
}
```

Point the manifest at it (`android:name=".MainActivity"`), keep `android:windowSoftInputMode="adjustResize"`, and add the `FileProvider` if the page uses `<input type="file">`:

```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.flutter_inappwebview_android.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true"
    tools:replace="android:authorities">
    <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/provider_paths" />
</provider>
```

Every `Scaffold` on a route that shows the webview needs a transparent background, otherwise Flutter paints over the native view.

### Layout caveat

The Android native view is hosted in a full-window container, so **it always fills the window** regardless of where `BrowserWebView` sits in the widget tree. Wrapping it in `SafeArea`/`Padding` insets the Flutter placeholder but not the page itself — the native view applies system-bar and IME insets itself. On iOS the platform view is a real subview and does follow the Flutter layout, so `SafeArea` works there. Position Flutter chrome as an overlay (`Stack`) rather than expecting it to displace the Android page.

`BrowserWebView` must be given bounded constraints that cover the area you want tappable. On Android its Flutter-side placeholder is what marks a pointer as landing on the page; if it collapses to zero size (a childless box under the loose constraints a `Scaffold` body hands out), every touch is claimed by Flutter and none reach the page.

## Native self-recovery

Ported unchanged from the pre-embedding implementation and identical across platforms:

- **Recreate budget** — a killed render/WebContent process (or a failed boot probe) swaps in a fresh WebView and reloads the URL. The budget is consecutive (refilled by a load that stays up for 10s) and bounded by a lifetime cap of 10.
- **SPA boot watchdog** — on pages whose URL contains `bootProbeUrl`, `bootProbeJs` runs ~12s after load and must return `ok` / `empty` / `none`. An `empty` result gets one grace re-probe, then recreates. This catches "white page" stalls no load callback ever reports.
- **Foreground liveness probe (iOS)** — a backgrounded `WKWebView` is the top jetsam trigger and often never fires `didTerminate`; a failing JS eval on foreground triggers recreation.
- **Error categories** — `network` / `server` / `tls` / `process` / `other`, mapped natively on both platforms. `isRecoverable` marks the ones worth an in-place `reload()`; permanently-bad URLs stay `other` so the host cannot retry-loop on them. `server` means a main-frame HTTP 5xx, which is reported from `onReceivedHttpError` / `decidePolicyFor navigationResponse` — the response body is still allowed to render so the page can show its own error state.

## Notes

- Cookies use the platform store (`WKWebsiteDataStore.default()` / Android `CookieManager`); they are not synced with the host's HTTP client. Session continuity relies on the URL and the initial request headers.
- `headers` apply to the initial request only.
- `mailto:` links are opened externally.
- Page→app messaging is deeplink interception; `evaluateJavascript` is the app→page direction, for handing something to a page that is already loaded.
- File chooser and camera capture are Android-only, driven by the page's `<input type="file">`.
