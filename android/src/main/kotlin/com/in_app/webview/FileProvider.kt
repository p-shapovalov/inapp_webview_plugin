package com.in_app.webview

import androidx.core.content.FileProvider

object InAppWebViewFileProvider : FileProvider() {
    const val fileProviderAuthorityExtension: String = "flutter_inappwebview_android.fileprovider"
}