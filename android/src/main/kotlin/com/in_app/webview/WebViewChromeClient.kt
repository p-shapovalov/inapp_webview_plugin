package com.in_app.webview

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Parcelable
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.flutter.plugin.common.PluginRegistry.ActivityResultListener
import java.io.File
import java.io.IOException

internal class WebViewChromeClient(
//    private var activity: Activity
) : WebChromeClient(), ActivityResultListener {
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var videoOutputFileUri: Uri? = null
    private var imageOutputFileUri: Uri? = null

    init {
//        BrowserPlugin.activityPluginBinding?.addActivityResultListener(this)
    }

    override fun onShowFileChooser(
        webView: WebView, filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean {

        val acceptTypes: Array<String> =
            fileChooserParams.acceptTypes
        val captureEnabled: Boolean = fileChooserParams.isCaptureEnabled
        val allowMultiple =
            fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE
        startPickerIntent(
            filePathCallback,
            acceptTypes,
            allowMultiple,
            captureEnabled
        )


        return true
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (filePathCallback == null) {
            return true
        }

        // based off of which button was pressed, we get an activity result and a file
        // the camera activity doesn't properly return the filename* (I think?) so we use
        // this filename instead
        when (requestCode) {
            PICKER -> {
                var results: Array<Uri>? = null
                if (resultCode == Activity.RESULT_OK) {
                    results = getSelectedFiles(data, resultCode)
                }

                if (filePathCallback != null) {
                    filePathCallback!!.onReceiveValue(results)
                }
            }
        }

        filePathCallback = null
        imageOutputFileUri = null
        videoOutputFileUri = null

        return true
    }

    private val photoIntent: Intent
        get() {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            imageOutputFileUri = getOutputUri(MediaStore.ACTION_IMAGE_CAPTURE)
            intent.putExtra(MediaStore.EXTRA_OUTPUT, imageOutputFileUri)
            return intent
        }

    private val videoIntent: Intent
        get() {
            val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
            videoOutputFileUri = getOutputUri(MediaStore.ACTION_VIDEO_CAPTURE)
            intent.putExtra(MediaStore.EXTRA_OUTPUT, videoOutputFileUri)
            return intent
        }

    private fun startPickerIntent(
        callback: ValueCallback<Array<Uri>>, acceptTypes: Array<String>,
        allowMultiple: Boolean, captureEnabled: Boolean
    ): Boolean {
        filePathCallback = callback

        val images = acceptsImages(acceptTypes)
        val video = acceptsVideo(acceptTypes)

        var pickerIntent: Intent? = null

        if (captureEnabled) {
            if (!needsCameraPermission()) {
                if (images) {
                    pickerIntent = photoIntent
                } else if (video) {
                    pickerIntent = videoIntent
                }
            }
        }
        if (pickerIntent == null) {
            val extraIntents = ArrayList<Parcelable>()
            if (!needsCameraPermission()) {
                if (images) {
                    extraIntents.add(photoIntent)
                }
                if (video) {
                    extraIntents.add(videoIntent)
                }
            }

            val fileSelectionIntent = getFileChooserIntent(acceptTypes, allowMultiple)

            pickerIntent = Intent(Intent.ACTION_CHOOSER)
            pickerIntent.putExtra(Intent.EXTRA_INTENT, fileSelectionIntent)
            pickerIntent.putExtra(
                Intent.EXTRA_INITIAL_INTENTS, extraIntents.toArray(
                    arrayOf<Parcelable>()
                )
            )
        }

        val activity = BrowserPlugin.activityPluginBinding?.activity
        if (activity != null && pickerIntent.resolveActivity(activity.packageManager) != null) {
            activity.startActivityForResult(pickerIntent, PICKER)
        }

        return true
    }

    private fun getSelectedFiles(data: Intent?, resultCode: Int): Array<Uri>? {
        // we have one file selected
        if (data != null && data.data != null) {
            return if (resultCode == Activity.RESULT_OK) {
                FileChooserParams.parseResult(resultCode, data)
            } else {
                null
            }
        }

        // we have multiple files selected
        if (data != null && data.clipData != null) {
            val result = List<Uri>(
                size = data.clipData!!.itemCount,
                init = { data.clipData!!.getItemAt(it).uri })
            return result.toTypedArray()
        }

        // we have a captured image or video file
        val mediaUri = capturedMediaFile
        if (mediaUri != null) {
            return arrayOf(mediaUri)
        }

        return null
    }

    private val capturedMediaFile: Uri?
        get() {
            if (imageOutputFileUri != null && isFileNotEmpty(imageOutputFileUri!!)) {
                return imageOutputFileUri
            }

            if (videoOutputFileUri != null && isFileNotEmpty(videoOutputFileUri!!)) {
                return videoOutputFileUri
            }

            return null
        }


    private fun isFileNotEmpty(uri: Uri): Boolean {
        val activity = BrowserPlugin.activityPluginBinding?.activity ?: return false

        val length: Long
        try {
            val descriptor = activity.contentResolver.openAssetFileDescriptor(uri, "r")
            length = descriptor!!.length
            descriptor.close()
        } catch (e: IOException) {
            return false
        }

        return length > 0
    }

    private fun getFileChooserIntent(acceptTypes: Array<String>, allowMultiple: Boolean): Intent {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.setType("*/*")
        intent.putExtra(Intent.EXTRA_MIME_TYPES, getAcceptedMimeType(acceptTypes))
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
        return intent
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            request.grant(request.resources)
        }
    }

    private fun needsCameraPermission(): Boolean {
        var needed = false

        val activity = BrowserPlugin.activityPluginBinding?.activity ?: return false
        val packageManager = activity.packageManager
        try {
            val requestedPermissions = packageManager.getPackageInfo(
                activity.applicationContext.packageName,
                PackageManager.GET_PERMISSIONS
            ).requestedPermissions
            if (requestedPermissions != null && listOf(*requestedPermissions).contains(android.Manifest.permission.CAMERA)
                && ContextCompat.checkSelfPermission(
                    activity,
                    android.Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                needed = true
            }
        } catch (e: PackageManager.NameNotFoundException) {
            needed = true
        }

        return needed
    }

    private fun acceptsImages(types: Array<String>): Boolean {
        val mimeTypes = getAcceptedMimeType(types)
        return acceptsAny(types) || arrayContainsString(mimeTypes, "image")
    }

    private fun acceptsVideo(types: Array<String>): Boolean {
        val mimeTypes = getAcceptedMimeType(types)
        return acceptsAny(types) || arrayContainsString(mimeTypes, "video")
    }

    private fun acceptsAny(types: Array<String>): Boolean {
        if (isArrayEmpty(types)) {
            return true
        }

        for (type in types) {
            if (type == "*/*") {
                return true
            }
        }

        return false
    }
    private fun isArrayEmpty(arr: Array<String>): Boolean {
        // when our array returned from getAcceptTypes() has no values set from the webview
        // i.e. <input type="file" />, without any "accept" attr
        // will be an array with one empty string element, afaik
        return arr.isEmpty() || (arr.size == 1 && arr[0].isEmpty())
    }

    private fun arrayContainsString(array: Array<String?>, pattern: String): Boolean {
        for (content in array) {
            if (content != null && content.contains(pattern)) {
                return true
            }
        }
        return false
    }

    private fun getAcceptedMimeType(types: Array<String>): Array<String?> {
        if (isArrayEmpty(types)) {
            return arrayOf("*/*")
        }
        val mimeTypes = arrayOfNulls<String>(types.size)
        for (i in types.indices) {
            val t = types[i]
            // convert file extensions to mime types
            if (t.matches("\\.\\w+".toRegex())) {
                val mimeType = getMimeTypeFromExtension(t.replace(".", ""))
                mimeTypes[i] = mimeType
            } else {
                mimeTypes[i] = t
            }
        }
        return mimeTypes
    }

    private fun getMimeTypeFromExtension(extension: String?): String? {
        var type: String? = null
        if (extension != null) {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        }
        return type
    }

    private fun getOutputUri(intentType: String): Uri? {
        var capturedFile: File? = null
        try {
            capturedFile = getCapturedFile(intentType)
        } catch (e: IOException) {
            Log.e(LOG_TAG, "Error occurred while creating the File", e)
        }
        if (capturedFile == null) {
            return null
        }

        // for versions below 6.0 (23) we use the old File creation & permissions model
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return Uri.fromFile(capturedFile)
        }

        val activity = BrowserPlugin.activityPluginBinding?.activity ?: return null
        // for versions 6.0+ (23) we use the FileProvider to avoid runtime permissions
        val fileProviderAuthority = activity.applicationContext.packageName + "." +
                InAppWebViewFileProvider.fileProviderAuthorityExtension
        try {
            return FileProvider.getUriForFile(
                activity.applicationContext,
                fileProviderAuthority,
                capturedFile
            )
        } catch (e: Exception) {
            Log.e(LOG_TAG, "", e)
        }
        return null
    }


    @Throws(IOException::class)
    private fun getCapturedFile(intentType: String): File? {
        var prefix = ""
        var suffix = ""
        var dir: String? = ""

        if (intentType == MediaStore.ACTION_IMAGE_CAPTURE) {
            prefix = "image"
            suffix = ".jpg"
            dir = Environment.DIRECTORY_PICTURES
        } else if (intentType == MediaStore.ACTION_VIDEO_CAPTURE) {
            prefix = "video"
            suffix = ".mp4"
            dir = Environment.DIRECTORY_MOVIES
        }

        // for versions below 6.0 (23) we use the old File creation & permissions model
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // only this Directory works on all tested Android versions
            // ctx.getExternalFilesDir(dir) was failing on Android 5.0 (sdk 21)
            val storageDir = Environment.getExternalStoragePublicDirectory(dir)
            val filename = String.format("%s-%d%s", prefix, System.currentTimeMillis(), suffix)
            return File(storageDir, filename)
        }

        val activity = BrowserPlugin.activityPluginBinding?.activity ?: return null
        val storageDir = activity.applicationContext.getExternalFilesDir(null)
        return File.createTempFile(prefix, suffix, storageDir)
    }


    companion object {
        private const val LOG_TAG: String = "IABWebChromeClient"
        private const val PICKER = 1
    }
}