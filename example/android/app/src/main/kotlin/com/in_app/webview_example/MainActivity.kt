package com.in_app.webview_example

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import com.in_app.webview.WebViewNativeView
import io.flutter.plugins.nativeview.NativeViewFlutterActivity

class MainActivity : NativeViewFlutterActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The transparent embedding clears the window background, so the gap
        // before Flutter's first frame composites as black. Native views are
        // children drawn above the window background, so an opaque backdrop
        // here doesn't affect them.
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
    }

    override fun onRegisterNativeViews() {
        registerNativeViewFactory(WebViewNativeView.VIEW_KEY) { WebViewNativeView() }
    }
}
