import 'dart:io';

import 'package:browser_plugin/turnstile_service.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class BrowserPlugin {
  static BrowserPlugin? _instance;
  final MethodChannel _channel;

  BrowserPlugin._(this._channel);

  static BrowserPlugin get instance => _instance ?? _init();

  static BrowserPlugin _init() {
    final channel = MethodChannel('inapp_webview_channel');
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

  Future open(String url, {List<String>? invalidUrlRegex, Map<String, String>? headers, Color? color}) =>
      _channel.invokeMethod('open', {
        'url': url,
        'headers': ?headers,
        'invalidUrlRegex': ?invalidUrlRegex,
        if (color != null) 'color': color.toARGB32()
      });

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

  Future close() => _channel.invokeMethod('close');

  /// Reload the current WebView page in place (no teardown), to recover from a
  /// transient load failure without restarting/closing the survey.
  Future reload() => _channel.invokeMethod('reload');

  Future openTurnstile(
    String siteKey, {
    String? action,
    String? cData,
    String theme = 'auto',
    String size = 'normal',
  }) =>
      _channel.invokeMethod('openTurnstile', {
        'html': TurnstileService.turnstileHtml(
          siteKey: siteKey,
          action: action ?? '',
          cData: cData ?? '',
          theme: theme,
          size: size,
        ),
      });
  static VoidCallback? onFinish;
  static Function(String)? onNavigationCancel;
  static Function(String)? onTurnstileToken;
  static Function(String)? onTurnstileError;
  static VoidCallback? onTurnstileExpired;
  static Function(WebViewLoadError)? onLoadError;

  /// Fired when the native layer had to reload the page to recover from a
  /// WebView crash (iOS WebContent-process termination). Host can use it for
  /// telemetry — the reload can silently re-enter/restart an in-flight survey.
  static VoidCallback? onWebViewReload;

  /// Fired when a main-frame page load completes successfully (iOS didFinish /
  /// Android onPageFinished). Host uses it to reset consecutive-failure retry
  /// budgets — a load that succeeds ends any in-flight transient-error streak.
  static VoidCallback? onWebViewLoaded;

  static Future _handleMethod(MethodCall call) async {
    switch (call.method) {
      case 'onFinish':
        onFinish?.call();
        break;
      case 'onNavigationCancel':
        onNavigationCancel?.call(call.arguments.toString());
        break;
      case 'onTurnstileToken':
        onTurnstileToken?.call(call.arguments.toString());
        break;
      case 'onTurnstileError':
        onTurnstileError?.call(call.arguments.toString());
        break;
      case 'onTurnstileExpired':
        onTurnstileExpired?.call();
        break;
      case 'onLoadError':
        final args = Map<String, dynamic>.from(call.arguments as Map);
        onLoadError?.call(WebViewLoadError(
          code: args['code'] as int? ?? 0,
          domain: args['domain'] as String? ?? '',
          message: args['message'] as String? ?? '',
          category: WebViewLoadErrorCategory.fromString(args['category'] as String?),
        ));
        break;
      case 'onWebViewReload':
        onWebViewReload?.call();
        break;
      case 'onWebViewLoaded':
        onWebViewLoaded?.call();
        break;
    }
  }
}

/// Shared error-category vocabulary. The mapping from platform error codes to
/// these values is implemented natively in TWO places that MUST be kept in sync
/// with this enum: iOS `WebViewController.errorCategory(for:)` and Android
/// `WebViewActivity.categoryFor(code:)`.
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
  String toString() => 'WebViewLoadError(${category.name} — $domain $code: $message)';
}
