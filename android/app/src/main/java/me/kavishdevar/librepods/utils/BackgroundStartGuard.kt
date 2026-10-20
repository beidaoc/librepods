package me.kavishdevar.librepods.utils

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.SystemClock

/** Preserve a user stop even when a ROM rebinds the notification listener. */
object BackgroundStartGuard {
    private const val PREFS = "background_start_guard"
    private var initialized = false
    private var blocked = false

    @Synchronized
    fun isBlocked(context: Context): Boolean {
        if (!initialized) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val manager = context.getSystemService(ActivityManager::class.java)
            val forceStopped = if (Build.VERSION.SDK_INT >= 35) {
                runCatching {
                    manager.getHistoricalProcessStartReasons(1).firstOrNull()
                        ?.wasForceStopped() == true
                }.getOrDefault(false)
            } else false
            val lastUserLaunch = prefs.getLong("last_user_launch", 0L)
            val bootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            val userRequestedExit = runCatching {
                manager.getHistoricalProcessExitReasons(context.packageName, 0, 8)
                    .firstOrNull { it.processName == Application.getProcessName() }
                    ?.let {
                        it.reason == ApplicationExitInfo.REASON_USER_REQUESTED &&
                            it.timestamp > maxOf(lastUserLaunch, bootTime)
                    } == true
            }.getOrDefault(false)
            blocked = prefs.getBoolean("blocked", false) || forceStopped || userRequestedExit
            // Persist before returning: another listener restart must not forget the stop.
            prefs.edit().putBoolean("blocked", blocked).commit()
            initialized = true
            ProcessLifecycleTrace.record("background_start_decision",
                "blocked=$blocked forceStopped=$forceStopped userExit=$userRequestedExit " +
                    "lastUserLaunchMs=$lastUserLaunch")
        }
        return blocked
    }

    @Synchronized
    fun onUserLaunch(context: Context) {
        ProcessLifecycleTrace.record("user_launch", "component=${context.javaClass.simpleName} previouslyBlocked=$blocked")
        initialized = true
        blocked = false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("blocked", false)
            .putLong("last_user_launch", System.currentTimeMillis())
            .commit()
    }
}
