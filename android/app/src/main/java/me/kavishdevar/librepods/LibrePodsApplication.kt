package me.kavishdevar.librepods

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import me.kavishdevar.librepods.billing.BillingManager
import me.kavishdevar.librepods.billing.BillingProviderFactory
import me.kavishdevar.librepods.keepbridge.KeepHeartRateBridge
import me.kavishdevar.librepods.milink.MiLinkAirPodsBridgeContract
import me.kavishdevar.librepods.utils.XposedServiceHolder
import me.kavishdevar.librepods.utils.XposedState

class LibrePodsApplication: Application(), XposedServiceHelper.OnServiceListener, DefaultLifecycleObserver {

    override fun onCreate() {
        XposedServiceHelper.registerListener(this)
        Log.i(KEEP_HEART_RATE_TAG, "Registered Xposed service listener")
        BillingManager.provider = BillingProviderFactory.create(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        super<Application>.onCreate()

    }

    override fun onResume(owner: LifecycleOwner) {
        BillingManager.provider.queryPurchases()
        XposedState.isAvailable = XposedServiceHolder.service != null
        XposedState.bluetoothScopeEnabled = XposedServiceHolder.service?.scope?.contains("com.google.android.bluetooth") == true || XposedServiceHolder.service?.scope?.contains("com.android.bluetooth") == true
    }

    override fun onServiceBind(service: XposedService) {
        XposedServiceHolder.service = service
        XposedState.isAvailable = true
        XposedState.bluetoothScopeEnabled = XposedServiceHolder.service?.scope?.contains("com.google.android.bluetooth") == true || XposedServiceHolder.service?.scope?.contains("com.android.bluetooth") == true
        Log.i(
            KEEP_HEART_RATE_TAG,
            "Xposed service bound: ${service.frameworkName} API ${service.apiVersion}, scope=${service.scope}"
        )
        requestKeepHeartRateScope(service)
        requestMiLinkScope(service)
    }

    override fun onServiceDied(p0: XposedService) {
        XposedServiceHolder.service = null
        XposedState.isAvailable = false
    }

    private fun requestKeepHeartRateScope(service: XposedService) {
        val keepInstalled = runCatching {
            packageManager.getApplicationInfo(KeepHeartRateBridge.KEEP_PACKAGE, 0)
        }.isSuccess
        if (!keepInstalled) {
            Log.i(KEEP_HEART_RATE_TAG, "Keep is not installed; skipping scope request")
            return
        }
        if (service.scope.contains(KeepHeartRateBridge.KEEP_PACKAGE)) {
            Log.i(KEEP_HEART_RATE_TAG, "Keep is already in the module scope")
            return
        }

        Log.i(KEEP_HEART_RATE_TAG, "Requesting Keep scope")
        runCatching {
            service.requestScope(
                listOf(KeepHeartRateBridge.KEEP_PACKAGE),
                object : XposedService.OnScopeEventListener {
                    override fun onScopeRequestApproved(scope: List<String>) {
                        Log.i(KEEP_HEART_RATE_TAG, "Keep scope approved: $scope")
                    }

                    override fun onScopeRequestFailed(message: String) {
                        Log.w(KEEP_HEART_RATE_TAG, "Keep scope request failed: $message")
                    }
                }
            )
        }.onFailure {
            Log.e(KEEP_HEART_RATE_TAG, "Keep scope request threw", it)
        }
    }

    private fun requestMiLinkScope(service: XposedService) {
        val packageName = MiLinkAirPodsBridgeContract.MI_LINK_PACKAGE
        val installed = runCatching { packageManager.getApplicationInfo(packageName, 0) }.isSuccess
        if (!installed || service.scope.contains(packageName)) return

        Log.i(MI_LINK_TAG, "Requesting MiLink scope")
        runCatching {
            service.requestScope(
                listOf(packageName),
                object : XposedService.OnScopeEventListener {
                    override fun onScopeRequestApproved(scope: List<String>) {
                        Log.i(MI_LINK_TAG, "MiLink scope approved: $scope")
                    }

                    override fun onScopeRequestFailed(message: String) {
                        Log.w(MI_LINK_TAG, "MiLink scope request failed: $message")
                    }
                },
            )
        }.onFailure {
            Log.e(MI_LINK_TAG, "MiLink scope request threw", it)
        }
    }

    private companion object {
        const val KEEP_HEART_RATE_TAG = "LibrePodsKeepHR"
        const val MI_LINK_TAG = "LibrePodsMiLink"
    }
}
