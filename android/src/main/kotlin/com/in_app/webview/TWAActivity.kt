//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//
package com.in_app.webview

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsCallback
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsService
import androidx.browser.trusted.TrustedWebActivityDisplayMode
import androidx.browser.trusted.TrustedWebActivityIntentBuilder
import androidx.core.content.ContextCompat
import com.google.androidbrowserhelper.trusted.ChromeLegacyUtils
import com.google.androidbrowserhelper.trusted.ChromeOsSupport
import com.google.androidbrowserhelper.trusted.ChromeUpdatePrompt
import com.google.androidbrowserhelper.trusted.LauncherActivityMetadata
import com.google.androidbrowserhelper.trusted.ManageDataLauncherActivity
import com.google.androidbrowserhelper.trusted.QualityEnforcer
import com.google.androidbrowserhelper.trusted.SharingUtils
import com.google.androidbrowserhelper.trusted.TwaLauncher
import com.google.androidbrowserhelper.trusted.TwaSharedPreferencesManager
import com.google.androidbrowserhelper.trusted.splashscreens.PwaWrapperSplashScreenStrategy
import org.json.JSONException

open class LauncherActivity : Activity() {
    private var mMetadata: LauncherActivityMetadata? = null
    private var mBrowserWasLaunched = false
    private var mSplashScreenStrategy: PwaWrapperSplashScreenStrategy? = null
    private var mTwaLauncher: TwaLauncher? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ++sLauncherActivitiesAlive
        val twaAlreadyRunning = sLauncherActivitiesAlive > 1
        val intentHasData = this.intent.data != null
        val intentHasShareData = SharingUtils.isShareIntent(this.intent)
        if (twaAlreadyRunning && !intentHasData && !intentHasShareData) {
            this.finish()
        } else if (this.restartInNewTask()) {
            this.finish()
        } else if (savedInstanceState != null && savedInstanceState.getBoolean("android.support.customtabs.trusted.BROWSER_WAS_LAUNCHED_KEY")) {
            this.finish()
        } else {
            this.mMetadata = LauncherActivityMetadata.parse(this)
            if (this.splashScreenNeeded()) {
                mMetadata?.let {
                    this.mSplashScreenStrategy = PwaWrapperSplashScreenStrategy(
                        this,
                        it.splashImageDrawableId, this.getColorCompat(
                            it.splashScreenBackgroundColorId
                        ),
                        splashImageScaleType,
                        splashImageTransformationMatrix,
                        it.splashScreenFadeOutDurationMillis, it.fileProviderAuthority
                    )
                }

            }

            if (this.shouldLaunchImmediately()) {
                this.launchTwa()
            }
        }
    }

    protected fun shouldLaunchImmediately(): Boolean {
        return true
    }

    protected fun launchTwa() {
        if (this.isFinishing) {
            Log.d("TWALauncherActivity", "Aborting launchTwa() as Activity is finishing")
        } else {
            val darkModeColorScheme = (CustomTabColorSchemeParams.Builder()).setToolbarColor(
                this.getColorCompat(
                    mMetadata!!.statusBarColorDarkId
                )
            ).setNavigationBarColor(
                this.getColorCompat(
                    mMetadata!!.navigationBarColorDarkId
                )
            ).setNavigationBarDividerColor(
                this.getColorCompat(
                    mMetadata!!.navigationBarDividerColorDarkId
                )
            ).build()
            val twaBuilder = (TrustedWebActivityIntentBuilder(
                launchingUrl
            )).setToolbarColor(this.getColorCompat(mMetadata!!.statusBarColorId))
                .setNavigationBarColor(
                    this.getColorCompat(
                        mMetadata!!.navigationBarColorId
                    )
                ).setNavigationBarDividerColor(
                    this.getColorCompat(
                        mMetadata!!.navigationBarDividerColorId
                    )
                ).setColorScheme(CustomTabsIntent.COLOR_SCHEME_SYSTEM).setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_DARK, darkModeColorScheme).setDisplayMode(
                    displayMode
                ).setScreenOrientation(mMetadata!!.screenOrientation)
            if (mMetadata!!.additionalTrustedOrigins != null) {
                twaBuilder.setAdditionalTrustedOrigins(mMetadata!!.additionalTrustedOrigins!!)
            }

            this.addShareDataIfPresent(twaBuilder)
            this.mTwaLauncher = this.createTwaLauncher()
            mTwaLauncher!!.launch(
                twaBuilder,
                customTabsCallback, this.mSplashScreenStrategy,
                { this.mBrowserWasLaunched = true },
                fallbackStrategy
            )
            if (!sChromeVersionChecked) {
                ChromeUpdatePrompt.promptIfNeeded(
                    this,
                    mTwaLauncher!!.providerPackage
                )
                sChromeVersionChecked = true
            }

            if (ChromeOsSupport.isRunningOnArc(this.applicationContext.packageManager)) {
                (TwaSharedPreferencesManager(this)).writeLastLaunchedProviderPackageName("org.chromium.arc.payment_app")
            } else {
                (TwaSharedPreferencesManager(this)).writeLastLaunchedProviderPackageName(
                    mTwaLauncher!!.providerPackage
                )
            }

            ManageDataLauncherActivity.addSiteSettingsShortcut(
                this,
                mTwaLauncher!!.providerPackage
            )
        }
    }

    protected val customTabsCallback: CustomTabsCallback
        get() = QualityEnforcer()

    protected fun createTwaLauncher(): TwaLauncher {
        return TwaLauncher(this)
    }

    private fun splashScreenNeeded(): Boolean {
        return mMetadata!!.splashImageDrawableId != 0
    }

    private fun addShareDataIfPresent(twaBuilder: TrustedWebActivityIntentBuilder) {
        val shareData = SharingUtils.retrieveShareDataFromIntent(this.intent)
        if (shareData != null) {
            if (mMetadata!!.shareTarget == null) {
                Log.d(
                    "TWALauncherActivity",
                    "Failed to share: share target not defined in the AndroidManifest"
                )
            } else {
                try {
                    val shareTarget = SharingUtils.parseShareTargetJson(
                        mMetadata!!.shareTarget!!
                    )
                    twaBuilder.setShareParams(shareTarget, shareData)
                } catch (e: JSONException) {
                    Log.d(
                        "TWALauncherActivity",
                        "Failed to parse share target json: $e"
                    )
                }
            }
        }
    }

    protected val splashImageScaleType: ImageView.ScaleType
        get() = ImageView.ScaleType.CENTER

    protected val splashImageTransformationMatrix: Matrix?
        get() = null

    private fun getColorCompat(splashScreenBackgroundColorId: Int): Int {
        return ContextCompat.getColor(this, splashScreenBackgroundColorId)
    }

    override fun onRestart() {
        super.onRestart()
        if (this.mBrowserWasLaunched) {
            this.finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        --sLauncherActivitiesAlive
        if (this.mTwaLauncher != null) {
            mTwaLauncher!!.destroy()
        }

        if (this.mSplashScreenStrategy != null) {
            mSplashScreenStrategy!!.destroy()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(
            "android.support.customtabs.trusted.BROWSER_WAS_LAUNCHED_KEY",
            this.mBrowserWasLaunched
        )
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        if (this.mSplashScreenStrategy != null) {
            mSplashScreenStrategy!!.onActivityEnterAnimationComplete()
        }
    }

    protected val launchingUrl: Uri
        get() {
            val uri = this.intent.data
            if (uri != null) {
                Log.d("TWALauncherActivity", "Using URL from Intent ($uri).")
                return uri
            } else if (mMetadata!!.defaultUrl != null) {
                Log.d(
                    "TWALauncherActivity",
                    "Using URL from Manifest (" + mMetadata!!.defaultUrl + ")."
                )
                return Uri.parse(mMetadata!!.defaultUrl)
            } else {
                return Uri.parse("https://www.example.com/")
            }
        }

    protected open val fallbackStrategy: TwaLauncher.FallbackStrategy
        get() = if ("webview".equals(
                mMetadata!!.fallbackStrategyType,
                ignoreCase = true
            )
        ) TwaLauncher.WEBVIEW_FALLBACK_STRATEGY else TwaLauncher.CCT_FALLBACK_STRATEGY

    protected val displayMode: TrustedWebActivityDisplayMode
        get() = mMetadata!!.displayMode

    @SuppressLint("WrongConstant")
    private fun restartInNewTask(): Boolean {
        val hasNewTask = (this.intent.flags and 268435456) != 0
        val hasNewDocument = (this.intent.flags and 524288) != 0
        if (hasNewTask && !hasNewDocument) {
            return false
        } else {
            val newIntent = Intent(this.intent)
            var flags = this.intent.flags
            flags = flags or 268435456
            flags = flags and -524289
            newIntent.setFlags(flags)
            this.startActivity(newIntent)
            return true
        }
    }

    companion object {
        private const val TAG = "TWALauncherActivity"
        private const val BROWSER_WAS_LAUNCHED_KEY =
            "android.support.customtabs.trusted.BROWSER_WAS_LAUNCHED_KEY"
        private const val FALLBACK_TYPE_WEBVIEW = "webview"
        private var sChromeVersionChecked = false
        private var sLauncherActivitiesAlive = 0
    }
}

fun isTwaSupported(pm: PackageManager): Boolean {
    val services = pm.queryIntentServices(
        Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION),
        PackageManager.GET_RESOLVED_FILTER)
    val customTabsServices: MutableMap<String, Int> = HashMap()
    for (service in services) {
        val packageName = service.serviceInfo.packageName
        if (ChromeLegacyUtils.supportsTrustedWebActivities(pm, packageName)) {
            // Chrome 72-74 support Trusted Web Activities but don't yet have the TWA category on
            // their CustomTabsService.
            return true
        }
    }
    return false
}