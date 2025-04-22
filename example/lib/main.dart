import 'package:browser_plugin/browser_plugin.dart';
import 'package:flutter/material.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  @override
  void initState() {
    super.initState();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        floatingActionButton: FloatingActionButton(onPressed: () {
          BrowserPlugin.instance
              .open("https://jonathanbcsouza.github.io/Advanced_WebView/");
        }),
        appBar: AppBar(
          title: const Text('Plugin example app'),
        ),
      ),
    );
  }
}
