import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_native_view_android/flutter_native_view_android.dart';

// Host apps need the overlay wrappers to route gestures around the Android
// native view; re-exported so they don't have to depend on the package
// directly.
export 'package:flutter_native_view_android/flutter_native_view_android.dart'
    show NativeViewOverlayApp, NativeViewOverlayBody;

/// Identity shared with the native side: the Android native-view factory key
/// (see `WebViewNativeView.VIEW_KEY`) and the iOS platform-view type
/// (see `BrowserPlugin.register`).
const String browserWebViewType = 'inapp_webview';

/// Control channel + event callbacks for the embedded webview.
///
/// The webview itself is embedded with [BrowserWebView]; this class carries
/// everything that is not tied to a widget: in-place [reload], the TWA
/// fast-path, and the event callbacks fired by the native layer. Callbacks are
/// static single-slot (one live webview at a time) — same contract as the
/// pre-embedded plugin.
class BrowserPlugin {
  static BrowserPlugin? _instance;
  final MethodChannel _channel;

  BrowserPlugin._(this._channel);

  static BrowserPlugin get instance => _instance ?? _init();

  static const _channelName = 'inapp_webview_channel';

  static BrowserPlugin _init() {
    final channel = MethodChannel(_channelName);
    channel.setMethodCallHandler((call) async {
      try {
        return await _handleMethod(call);
      } on Error catch (e, stackTrace) {
        debugPrint(e.toString());
        debugPrint(stackTrace.toString());
      }
    });
    _instance = BrowserPlugin._(channel);
    return _instance!;
  }

  Future openTWA(String url) async => {
        if (Platform.isAndroid) _channel.invokeMethod('openTWA', {'url': url})
      };

  Future<bool> isTWASupported() async =>
      Platform.isAndroid &&
      (await _channel.invokeMethod<bool>('isTWASupported') ?? false);

  Future<bool> isWebViewAvailable() async {
    if (!Platform.isAndroid) return true;
    return await _channel.invokeMethod<bool>('isWebViewAvailable') ?? false;
  }

  /// Reload the current WebView page in place (no teardown), to recover from a
  /// transient load failure without restarting/closing the survey.
  Future reload() => _channel.invokeMethod('reload');

  static Function(String)? onNavigationCancel;
  static Function(WebViewLoadError)? onLoadError;

  /// Fired when the native layer had to recreate/reload the page to recover.
  /// Host can use it for telemetry — the reload silently re-enters the
  /// in-flight survey (resumes via sessionId).
  static Function(WebViewReloadReason reason)? onWebViewReload;

  /// Fired when a main-frame page load completes successfully (iOS didFinish /
  /// Android onPageFinished). Host uses it to reset consecutive-failure retry
  /// budgets — a load that succeeds ends any in-flight transient-error streak.
  static VoidCallback? onWebViewLoaded;

  static Future _handleMethod(MethodCall call) async {
    switch (call.method) {
      case 'onNavigationCancel':
        onNavigationCancel?.call(call.arguments.toString());
        break;
      case 'onLoadError':
        final args = Map<String, dynamic>.from(call.arguments as Map);
        onLoadError?.call(WebViewLoadError(
          code: args['code'] as int? ?? 0,
          domain: args['domain'] as String? ?? '',
          message: args['message'] as String? ?? '',
          category:
              WebViewLoadErrorCategory.fromString(args['category'] as String?),
        ));
        break;
      case 'onWebViewReload':
        final args = call.arguments as Map?;
        onWebViewReload
            ?.call(WebViewReloadReason.fromString(args?['reason'] as String?));
        break;
      case 'onWebViewLoaded':
        onWebViewLoaded?.call();
        break;
    }
  }
}

/// The embedded in-app webview.
///
/// iOS: a standard platform view (WKWebView composited inside the Flutter
/// scene). Android: a native WebView hosted *below* the transparent Flutter
/// view by `flutter_native_view_android` — the host activity must extend
/// `NativeViewFlutterActivity` and register `WebViewNativeView` under
/// [browserWebViewType], and the app must be wrapped in [NativeViewOverlayApp]
/// with this widget's subtree wrapped in [NativeViewOverlayBody].
///
/// In both embeddings Flutter UI (dialogs, routes, overlays) renders on top of
/// the live webview — no teardown needed to show an exit dialog.
///
/// Changing [url] rebuilds the webview from scratch, which is what the previous
/// `open()` call did. The remaining properties are create-time only.
///
/// [bootProbeJs]/[bootProbeUrl] arm the native SPA boot watchdog: on pages
/// whose URL contains [bootProbeUrl], [bootProbeJs] is evaluated ~12s after
/// load and must return 'ok' (booted), 'empty' (loaded but SPA never
/// rendered -> recreate) or 'none' (marker absent -> no action). Keeping the
/// contract here means the host that owns the page defines it once for both
/// platforms.
class BrowserWebView extends StatelessWidget {
  const BrowserWebView({
    super.key,
    required this.url,
    this.invalidUrlRegex,
    this.headers,
    this.color,
    this.bootProbeJs,
    this.bootProbeUrl,
  });

  final String url;

  /// Navigations matching any of these regexes are cancelled natively and
  /// reported through [BrowserPlugin.onNavigationCancel] — the deeplink IPC
  /// bus between the page and the host app.
  final List<String>? invalidUrlRegex;

  /// Extra headers for the initial request only.
  final Map<String, String>? headers;

  /// Background color shown while the page loads.
  final Color? color;

  final String? bootProbeJs;
  final String? bootProbeUrl;

  Map<String, dynamic> get _config => {
        'url': url,
        'invalidUrlRegex': invalidUrlRegex ?? [],
        'headers': ?headers,
        'bootProbeJs': ?bootProbeJs,
        'bootProbeUrl': ?bootProbeUrl,
        if (color != null) 'color': color!.toARGB32(),
      };

  @override
  Widget build(BuildContext context) {
    if (Platform.isAndroid) {
      return _AndroidBrowserView(config: _config);
    }
    return UiKitView(
      // creationParams are create-time, so a new URL must mint a new platform
      // view. Distinct view ids make this safe — unlike Android, where all
      // instances share one view key.
      key: ValueKey(url),
      viewType: browserWebViewType,
      layoutDirection: TextDirection.ltr,
      creationParams: _config,
      creationParamsCodec: const StandardMessageCodec(),
      // The webview must win the gesture arena immediately — otherwise scroll
      // gestures inside the page compete with Flutter scrollables.
      gestureRecognizers: {
        Factory<OneSequenceGestureRecognizer>(EagerGestureRecognizer.new),
      },
    );
  }
}

/// Android embedding: a transparent placeholder whose lifecycle drives the
/// native view below the Flutter layer. The config must reach the native side
/// before the view is instantiated, hence the configure-then-add override.
///
/// A URL change is handled in place rather than by keying the widget: every
/// instance shares one native view key, so a keyed swap could add the new view
/// before the outgoing element removed the old one — and the removal would
/// then tear down the view that just replaced it.
class _AndroidBrowserView extends NativeViewWidget {
  const _AndroidBrowserView({required this.config});

  final Map<String, dynamic> config;

  String get url => config['url'] as String;

  @override
  String get viewKey => browserWebViewType;

  @override
  State<NativeViewWidget> createState() => _AndroidBrowserViewState();
}

class _AndroidBrowserViewState
    extends NativeViewWidgetState<_AndroidBrowserView> {
  // Every instance addresses the same native view key, and the native side
  // treats a repeat add as a no-op: a second live webview would silently adopt
  // the first one's page, and the first one's disposal would then tear down
  // the view the second is showing. Two co-mounted instances — a
  // pushReplacement between pages, say — are a usage error, not a layout the
  // native side can express.
  static int _liveInstances = 0;

  @override
  void initState() {
    assert(
      _liveInstances == 0,
      'Only one BrowserWebView can be mounted at a time on Android: all '
      'instances share the native view key "$browserWebViewType". Unmount the '
      'previous one before mounting another, or change its url in place.',
    );
    _liveInstances++;
    super.initState();
  }

  @override
  void dispose() {
    _liveInstances--;
    super.dispose();
  }

  @override
  Future<void> addNativeView() async {
    await BrowserPlugin.instance._channel
        .invokeMethod('configure', widget.config);
    await super.addNativeView();
  }

  @override
  void didUpdateWidget(_AndroidBrowserView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.url != widget.url) {
      _reloadNativeView();
    }
  }

  Future<void> _reloadNativeView() async {
    await removeNativeView();
    if (mounted) await addNativeView();
  }
}

/// Why the native layer recreated the WebView. Emitted natively in TWO places
/// that MUST be kept in sync with [fromString]: iOS
/// `BrowserWebViewPlatformView.recreateWebView(reason:)` and Android
/// `WebViewNativeView.recreateWebView(reason)`.
enum WebViewReloadReason {
  /// WebContent/render-process death (crash, jetsam, foreground probe).
  recreate,

  /// Page loaded but the SPA never rendered into the boot-probe container.
  bootWatchdog,

  unknown;

  static WebViewReloadReason fromString(String? raw) => switch (raw) {
        'recreate' => WebViewReloadReason.recreate,
        'boot-watchdog' => WebViewReloadReason.bootWatchdog,
        _ => WebViewReloadReason.unknown,
      };
}

/// Shared error-category vocabulary. The mapping from platform error codes to
/// these values is implemented natively in TWO places that MUST be kept in sync
/// with this enum: iOS `BrowserWebViewPlatformView.errorCategory(for:)` and
/// Android `WebViewNativeView.categoryFor(code:)`.
enum WebViewLoadErrorCategory {
  network,
  server,
  tls,
  process,
  other;

  static WebViewLoadErrorCategory fromString(String? raw) => switch (raw) {
        'network' => WebViewLoadErrorCategory.network,
        'server' => WebViewLoadErrorCategory.server,
        'tls' => WebViewLoadErrorCategory.tls,
        'process' => WebViewLoadErrorCategory.process,
        _ => WebViewLoadErrorCategory.other,
      };

  /// Transient failures worth an in-place reload. `process` (WebContent-process
  /// give-up) and `other` are NOT recoverable this way — by the time the native
  /// layer reports `process` it has already exhausted its own crash-recovery
  /// reloads.
  bool get isRecoverable => this == network || this == tls || this == server;
}

class WebViewLoadError {
  final int code;
  final String domain;
  final String message;
  final WebViewLoadErrorCategory category;
  const WebViewLoadError({
    required this.code,
    required this.domain,
    required this.message,
    required this.category,
  });

  @override
  String toString() =>
      'WebViewLoadError(${category.name} — $domain $code: $message)';
}
