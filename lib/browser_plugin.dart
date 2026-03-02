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
        if (color != null) 'color': color.value
      });

  Future openTWA(String url) async => {
        if (Platform.isAndroid) _channel.invokeMethod('openTWA', {'url': url})
      };

  Future<bool> isTWASupported() async =>
      Platform.isAndroid &&
      (await _channel.invokeMethod<bool>('isTWASupported') ?? false);

  Future close() => _channel.invokeMethod('close');

  Future openTurnstile(
    String siteKey, {
    String? action,
    String? cData,
    String theme = 'auto',
    String size = 'normal',
  }) =>
      _channel.invokeMethod('openTurnstile', {
        'html': _turnstileHtml(
          siteKey: siteKey,
          action: action ?? '',
          cData: cData ?? '',
          theme: theme,
          size: size,
        ),
      });

  static String _turnstileHtml({
    required String siteKey,
    required String action,
    required String cData,
    required String theme,
    required String size,
  }) =>
      '''<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<script src="https://challenges.cloudflare.com/turnstile/v0/api.js?onload=onTurnstileLoad" async defer></script>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{display:flex;justify-content:center;align-items:center;min-height:100vh;background:transparent}
#turnstile-container{display:flex;justify-content:center}
</style>
</head>
<body>
<div id="turnstile-container"></div>
<script>
function onTurnstileLoad(){
  turnstile.render('#turnstile-container',{
    sitekey:'$siteKey',
    action:'$action'||undefined,
    cData:'$cData'||undefined,
    theme:'$theme',
    size:'$size',
    callback:function(t){
      if(window.TurnstileBridge){TurnstileBridge.onToken(t)}
      else if(window.webkit&&window.webkit.messageHandlers&&window.webkit.messageHandlers.turnstile){window.webkit.messageHandlers.turnstile.postMessage(JSON.stringify({type:'token',value:t}))}
    },
    'error-callback':function(e){
      if(window.TurnstileBridge){TurnstileBridge.onError(e)}
      else if(window.webkit&&window.webkit.messageHandlers&&window.webkit.messageHandlers.turnstile){window.webkit.messageHandlers.turnstile.postMessage(JSON.stringify({type:'error',value:e}))}
    },
    'expired-callback':function(){
      if(window.TurnstileBridge){TurnstileBridge.onExpired()}
      else if(window.webkit&&window.webkit.messageHandlers&&window.webkit.messageHandlers.turnstile){window.webkit.messageHandlers.turnstile.postMessage(JSON.stringify({type:'expired'}))}
    }
  });
}
</script>
</body>
</html>''';

  static VoidCallback? onFinish;
  static Function(String)? onNavigationCancel;
  static Function(String)? onTurnstileToken;
  static Function(String)? onTurnstileError;
  static VoidCallback? onTurnstileExpired;

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
    }
  }
}
