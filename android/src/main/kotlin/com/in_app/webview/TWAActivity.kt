package com.umongous.paidviewpoint

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.browser.customtabs.CustomTabsService
import androidx.browser.trusted.TrustedWebActivityIntentBuilder
import com.google.androidbrowserhelper.trusted.ChromeLegacyUtils
import com.google.androidbrowserhelper.trusted.LauncherActivity
import com.google.androidbrowserhelper.trusted.TwaLauncher
import io.flutter.plugin.common.MethodChannel

class OfflineFirstTWALauncherActivity(private val channel: MethodChannel) : LauncherActivity() {
    override fun getFallbackStrategy(): TwaLauncher.FallbackStrategy {
        return TwaLauncher.FallbackStrategy { _: Context, _: TrustedWebActivityIntentBuilder, _: String?, completionCallback: Runnable? ->
            channel.invokeMethod("twa_failed", null)
            completionCallback?.run()
        }
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