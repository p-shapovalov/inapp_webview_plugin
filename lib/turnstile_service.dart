import 'dart:async';

import 'package:browser_plugin/browser_plugin.dart';

class TurnstileException implements Exception {
  final String? code;
  TurnstileException([this.code]);
}

class TurnstileService {
  TurnstileService._();
  static Completer<String>? _completer;

  static Future<String?> requestToken(String siteKey, {String? action}) async {
    if (_completer != null && !_completer!.isCompleted) {
      return _completer!.future;
    }

    _completer = Completer<String>();

    BrowserPlugin.onTurnstileToken = (token) {
      BrowserPlugin.instance.close();
      if (_completer != null && !_completer!.isCompleted) {
        _completer!.complete(token);
      }
      _clearCallbacks();
    };

    BrowserPlugin.onTurnstileError = (errorCode) {
      BrowserPlugin.instance.close();
      if (_completer != null && !_completer!.isCompleted) {
        _completer!.completeError(TurnstileException(errorCode));
      }
      _clearCallbacks();
    };

    BrowserPlugin.onTurnstileExpired = () {
      BrowserPlugin.instance.close();
      if (_completer != null && !_completer!.isCompleted) {
        _completer!.completeError(TurnstileException('expired'));
      }
      _clearCallbacks();
    };

    await BrowserPlugin.instance.openTurnstile(siteKey, action: action);

    try {
      return await _completer!.future.timeout(const Duration(minutes: 2));
    } on TimeoutException {
      BrowserPlugin.instance.close();
      _clearCallbacks();
      throw TurnstileException('timeout');
    } finally {
      _completer = null;
    }
  }

  static void _clearCallbacks() {
    BrowserPlugin.onTurnstileToken = null;
    BrowserPlugin.onTurnstileError = null;
    BrowserPlugin.onTurnstileExpired = null;
  }

  static String turnstileHtml({
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
}
