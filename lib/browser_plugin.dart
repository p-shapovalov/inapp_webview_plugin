import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_native_view_android/flutter_native_view_android.dart';

/// Identity shared with the native side: the Android native-view factory key
/// (see `WebViewNativeView.VIEW_KEY`) and the iOS platform-view type
/// (see `BrowserPlugin.register`).
const String browserWebViewType = 'inapp_webview';

/// Claims touches landing on Flutter UI so they are not forwarded to a webview
/// hosted below it. Required on Android, a passthrough elsewhere — hosts do not
/// have to know which platform composites the webview how.
class BrowserOverlayApp extends StatelessWidget {
  const BrowserOverlayApp({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) => Platform.isAndroid
      // Constant per process: toggling this would rebuild the app below it.
      ? NativeViewOverlayApp(enabled: true, child: child)
      : child;
}

/// Everything about the embedded webview that is not tied to the widget.
/// Callbacks are static single-slot — one live webview at a time.
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

  /// The app→page direction of the bus: hands something to a page that is
  /// already loaded, instead of navigating to say it. Null when no live
  /// webview is reachable (nothing embedded, or a TWA) or the script threw.
  Future<String?> evaluateJavascript(String js) =>
      _channel.invokeMethod<String>('evaluateJavascript', {'js': js});

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

/// The embedded in-app webview: a platform view on iOS, a native view below
/// the transparent Flutter view on Android. Either way Flutter UI renders on
/// top of the live page, so no teardown is needed to show a dialog.
///
/// The Android host activity must extend `NativeViewFlutterActivity` and
/// register `WebViewNativeView` under [browserWebViewType], and wrap the app
/// in [BrowserOverlayApp].
///
/// Changing [url] rebuilds the webview; the rest is create-time only.
///
/// [bootProbeJs]/[bootProbeUrl] arm the native SPA boot watchdog: on pages
/// whose URL contains [bootProbeUrl], [bootProbeJs] is evaluated ~12s after
/// load and must return 'ok', 'empty' (never rendered -> recreate) or 'none'.
/// Defining it here keeps the page contract in one place for both platforms.
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

  /// Cancelled natively and reported through
  /// [BrowserPlugin.onNavigationCancel] — the page→app bus.
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

  // The webview must win the gesture arena immediately — otherwise scroll
  // gestures inside the page compete with Flutter scrollables.
  static const _gestureRecognizers = <Factory<OneSequenceGestureRecognizer>>{
    Factory<OneSequenceGestureRecognizer>(EagerGestureRecognizer.new),
  };

  @override
  Widget build(BuildContext context) {
    if (Platform.isAndroid) {
      // Paired here, not left to the host: omitting it loses every touch,
      // silently.
      return NativeViewOverlayBody(
        enabled: true,
        child: _AndroidBrowserView(url: url, config: _config),
      );
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
      gestureRecognizers: _gestureRecognizers,
    );
  }
}

/// A placeholder whose lifecycle drives the native view below the Flutter
/// layer. A URL change is handled in place rather than by keying the widget:
/// all instances share one native view key, so a keyed swap could add the
/// replacement before the outgoing element removes the old one.
class _AndroidBrowserView extends NativeViewWidget {
  const _AndroidBrowserView({required this.url, required this.config});

  final String url;
  final Map<String, dynamic> config;

  @override
  String get viewKey => browserWebViewType;

  @override
  State<NativeViewWidget> createState() => _AndroidBrowserViewState();
}

class _AndroidBrowserViewState
    extends NativeViewWidgetState<_AndroidBrowserView> {
  // A repeat add is a native no-op, so a second live webview would adopt the
  // first one's page and be torn down by the first one's disposal. Debug-only:
  // nothing outside the assert reads this.
  static int _liveInstances = 0;

  @override
  void initState() {
    assert(() {
      assert(
        _liveInstances == 0,
        'Only one BrowserWebView can be mounted at a time on Android: all '
        'instances share the native view key "$browserWebViewType". Unmount '
        'the previous one before mounting another, or change its url in place.',
      );
      _liveInstances++;
      return true;
    }());
    super.initState();
  }

  @override
  void dispose() {
    assert(() {
      _liveInstances--;
      return true;
    }());
    super.dispose();
  }

  // The base placeholder is a childless ColoredBox, which collapses to zero
  // size under loose constraints and so drops out of the hit test — every
  // touch then gets claimed by Flutter and none reach the page.
  @override
  Widget build(BuildContext context) => const SizedBox.expand();

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

/// Emitted natively in two places that must stay in sync with [fromString]:
/// `BrowserWebViewPlatformView.recreateWebView` and
/// `WebViewNativeView.recreateWebView`.
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

/// Mapped from platform error codes natively in two places that must stay in
/// sync with this enum: `BrowserWebViewPlatformView.errorCategory(for:)` and
/// `WebViewNativeView.categoryFor(code:)`.
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

  /// Worth an in-place reload. `process` is excluded: by the time it is
  /// reported the native layer has spent its own crash-recovery reloads.
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
