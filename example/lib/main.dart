import 'dart:io';

import 'package:browser_plugin/browser_plugin.dart';
import 'package:flutter/material.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    // Claims pointers that land on Flutter UI so they are not forwarded to the
    // Android native view below. No-op on iOS, where the webview is a regular
    // platform view.
    return NativeViewOverlayApp(
      enabled: Platform.isAndroid,
      child: MaterialApp(
        theme: ThemeData(brightness: Brightness.dark),
        home: const HomePage(),
      ),
    );
  }
}

class HomePage extends StatelessWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Plugin example app')),
      body: Center(
        child: ElevatedButton(
          onPressed: () => Navigator.of(context).push(
            MaterialPageRoute<void>(builder: (_) => const SurveyPage()),
          ),
          child: const Text('Open webview'),
        ),
      ),
    );
  }
}

class SurveyPage extends StatefulWidget {
  const SurveyPage({super.key});

  @override
  State<SurveyPage> createState() => _SurveyPageState();
}

class _SurveyPageState extends State<SurveyPage> {
  @override
  void initState() {
    super.initState();
    // A cancelled navigation is the page->app bus. The dialog it opens renders
    // OVER the live webview — the webview is never torn down.
    BrowserPlugin.onNavigationCancel = (url) => _showExitDialog(url);
    BrowserPlugin.onLoadError = (error) => debugPrint('$error');
  }

  @override
  void dispose() {
    BrowserPlugin.onNavigationCancel = null;
    BrowserPlugin.onLoadError = null;
    super.dispose();
  }

  void _showExitDialog(String url) {
    showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Exit survey?'),
        content: Text(url),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Continue'),
          ),
          TextButton(
            onPressed: () {
              Navigator.of(context).pop();
              Navigator.of(context).pop();
            },
            child: const Text('Exit'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      // The Android native view sits below the Flutter layer, so the scaffold
      // must not paint over it.
      backgroundColor: Platform.isAndroid ? Colors.transparent : null,
      body: SafeArea(
        child: NativeViewOverlayBody(
          enabled: Platform.isAndroid,
          child: const BrowserWebView(
            url: 'https://jonathanbcsouza.github.io/Advanced_WebView/',
            invalidUrlRegex: ['paidviewpoint'],
            color: Colors.blue,
          ),
        ),
      ),
    );
  }
}
