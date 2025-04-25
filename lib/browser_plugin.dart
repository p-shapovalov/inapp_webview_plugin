import 'dart:io';

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
        if (headers != null) 'invalidUrlRegex': headers,
        if (invalidUrlRegex != null) 'invalidUrlRegex': invalidUrlRegex,
        if (color != null) 'color': color.toARGB32()
      });

  Future openTWA(String url) async => {
        if (Platform.isAndroid) _channel.invokeMethod('openTWA', {'url': url})
      };

  Future<bool> isTWASupported() async =>
      Platform.isAndroid &&
      (await _channel.invokeMethod<bool>('isTWASupported') ?? false);

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
