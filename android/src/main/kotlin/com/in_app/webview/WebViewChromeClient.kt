package com.in_app.webview

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date


internal class WebViewChromeClient(
    private var activity: Activity,
    private var context: Context
) : WebChromeClient() {
    private var mUploadMessageArray: ValueCallback<Array<Uri>>? = null
    private var fileUri: Uri? = null
    private var videoUri: Uri? = null

    private fun getFileSize(fileUri: Uri): Long {
        val returnCursor = context.contentResolver.query(fileUri, null, null, null, null)
        returnCursor!!.moveToFirst()
        val sizeIndex = returnCursor.getColumnIndex(OpenableColumns.SIZE)
        val size = returnCursor.getLong(sizeIndex)
        returnCursor.close()
        return size
    }

    internal inner class ResultHandler {
        fun handleResult(requestCode: Int, resultCode: Int, intent: Intent?): Boolean {
            var handled = false
            if (requestCode == FILECHOOSER_RESULTCODE) {
                var results: Array<Uri>? = null
                if (resultCode == Activity.RESULT_OK) {

                    if (fileUri != null && getFileSize(fileUri!!) > 0) {
                        results = arrayOf(fileUri!!)
                    } else if (videoUri != null && getFileSize(videoUri!!) > 0) {
                        results = arrayOf(videoUri!!)
                    } else if (intent != null) {
                        results = getSelectedFiles(intent)
                    }
                }
                if (mUploadMessageArray != null) {
                    mUploadMessageArray!!.onReceiveValue(results)
                    mUploadMessageArray = null
                }
                handled = true
            }
            return handled
        }
    }

    private fun getSelectedFiles(data: Intent): Array<Uri>? {
        // we have one files selected
        if (data.data != null) {
            val dataString = data.dataString
            if (dataString != null) {
                return arrayOf(Uri.parse(dataString))
            }
        }
        // we have multiple files selected
        if (data.clipData != null) {
            val result = List<Uri>(
                size = data.clipData!!.itemCount,
                init = { data.clipData!!.getItemAt(it).uri })
            return result.toTypedArray()
        }
        return null
    }

//    private val platformThreadHandler: Handler = Handler(context.mainLooper)
    var resultHandler: ResultHandler = ResultHandler()

    override fun onShowFileChooser(
        webView: WebView, filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean {
        if (mUploadMessageArray != null) {
            mUploadMessageArray!!.onReceiveValue(null)
        }
        mUploadMessageArray = filePathCallback

        val acceptTypes = fileChooserParams.acceptTypes
        val intentList: MutableList<Intent> = ArrayList()
        fileUri = null
        videoUri = null
        if (acceptsImages(acceptTypes)) {
            val takePhotoIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            fileUri = getOutputFilename(MediaStore.ACTION_IMAGE_CAPTURE)
            takePhotoIntent.putExtra(MediaStore.EXTRA_OUTPUT, fileUri)
            intentList.add(takePhotoIntent)
        }
        if (acceptsVideo(acceptTypes)) {
            val takeVideoIntent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
            videoUri = getOutputFilename(MediaStore.ACTION_VIDEO_CAPTURE)
            takeVideoIntent.putExtra(MediaStore.EXTRA_OUTPUT, videoUri)
            intentList.add(takeVideoIntent)
        }

        val allowMultiple =
            fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE
        val contentSelectionIntent = fileChooserParams.createIntent()
        contentSelectionIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)

        val intentArray = intentList.toTypedArray<Intent>()

        val chooserIntent = Intent(Intent.ACTION_CHOOSER)
        chooserIntent.putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, intentArray)
        activity.startActivityForResult(chooserIntent, FILECHOOSER_RESULTCODE)
        return true
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            request.grant(request.resources)
        }
    }

    private fun getOutputFilename(intentType: String): Uri {
        var prefix = ""
        var suffix = ""

        if (intentType === MediaStore.ACTION_IMAGE_CAPTURE) {
            prefix = "image-"
            suffix = ".jpg"
        } else if (intentType === MediaStore.ACTION_VIDEO_CAPTURE) {
            prefix = "video-"
            suffix = ".mp4"
        }

        val packageName = context.packageName
        var capturedFile: File? = null
        try {
            capturedFile = createCapturedFile(prefix, suffix)
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return FileProvider.getUriForFile(
            context,
            "$packageName.fileprovider",
            capturedFile!!
        )
    }

    @SuppressLint("SimpleDateFormat")
    @Throws(IOException::class)
    private fun createCapturedFile(prefix: String, suffix: String): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val imageFileName = prefix + "_" + timeStamp
        val storageDir = context.getExternalFilesDir(null)
        return File.createTempFile(imageFileName, suffix, storageDir)
    }

    private fun acceptsImages(types: Array<String>): Boolean {
        return isArrayEmpty(types) || arrayContainsString(types, "image")
    }

    private fun acceptsVideo(types: Array<String>): Boolean {
        return isArrayEmpty(types) || arrayContainsString(types, "video")
    }

    private fun arrayContainsString(array: Array<String>, pattern: String): Boolean {
        for (content in array) {
            if (content.contains(pattern)) {
                return true
            }
        }
        return false
    }

    private fun isArrayEmpty(arr: Array<String>): Boolean {
        // when our array returned from getAcceptTypes() has no values set from the web view
        // i.e. <input type="file" />, without any "accept" attr
        // will be an array with one empty string element, afaik
        return arr.isEmpty() || (arr.size == 1 && arr[0].isEmpty())
    }


    companion object {
        private const val FILECHOOSER_RESULTCODE = 1
    }
}