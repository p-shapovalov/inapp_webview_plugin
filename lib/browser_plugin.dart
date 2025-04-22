import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class BrowserPlugin {
  static BrowserPlugin? _instance;
  static const MethodChannel _channel = MethodChannel('inapp_webview_channel');

  BrowserPlugin._();

  static BrowserPlugin get instance =>
      (_instance != null) ? _instance! : _init();

  static BrowserPlugin _init() {
    _channel.setMethodCallHandler((call) async {
      try {
        return await _handleMethod(call);
      } on Error catch (e, stackTrace) {
        debugPrint(e.toString());
        debugPrint(stackTrace.toString());
      }
    });
    _instance = BrowserPlugin._();
    return _instance!;
  }

  Future<void> open(String url, {List<String>? invalidUrlRegex}) async {
    await _channel.invokeMethod('open', {
      'url': url,
      if (invalidUrlRegex != null) 'invalidUrlRegex': invalidUrlRegex
    });
  }

  Future close() => _channel.invokeMethod('close');

  static VoidCallback? onFinish;
  static Function(String)? onNavigationCancel;

  static Future _handleMethod(MethodCall call) async {
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
