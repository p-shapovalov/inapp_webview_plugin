import 'package:flutter/services.dart';

class BrowserPlugin {
  static const MethodChannel _channel = MethodChannel('inapp_webview_channel');

  final String url;

  BrowserPlugin({required this.url});

  Future<void> open() async {
    await _channel.invokeMethod('openWebView', {'url': url});
  }

  Future<void> close() async {
    await _channel.invokeMethod('closeWebView');
  }
}
