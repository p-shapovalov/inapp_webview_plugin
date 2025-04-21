import 'package:flutter/services.dart';

class BrowserPlugin {
  static final MethodChannel _channel = MethodChannel('inapp_webview_channel')
    ..setMethodCallHandler(_handleMessages);

  static Future<void> open(String url, {List<String>? invalidUrlRegex}) async {
    await _channel.invokeMethod('open', {
      'url': url,
      if (invalidUrlRegex != null) 'invalidUrlRegex': invalidUrlRegex
    });
  }

  static Future close() => _channel.invokeMethod('close');

  static VoidCallback? onFinish;
  static Function(String)? onNavigationCancel;

  static Future<Null> _handleMessages(MethodCall call) async {
    switch (call.method) {
      case 'onFinish':
        onFinish?.call();
        break;
      case 'onNavigationCancel':
        onNavigationCancel?.call(call.arguments.toString());
        break;
    }
  }
}
