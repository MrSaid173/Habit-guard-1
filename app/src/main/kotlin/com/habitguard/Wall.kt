package com.habitguard

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * Device-admin receiver. It exists only so that the app may switch the lock screen's biometric and
 * Smart Lock unlock off for a while (see Wall). It uses no other policy and reads nothing.
 * Made an active admin by module/scripts/admin.sh (root), no screen in Settings is needed.
 */
class Admin : DeviceAdminReceiver()

/**
 * The "hard wall": KEYGUARD_DISABLE_BIOMETRICS (face, fingerprint, iris) + KEYGUARD_DISABLE_TRUST_AGENTS
 * (Smart Lock) while the phone is asleep or a delay is running. PIN / pattern / password keep working.
 * Main thread only. Every failure is traced and swallowed: no wall is better than a crash.
 */
object Wall {
    private const val FEATURES = DevicePolicyManager.KEYGUARD_DISABLE_BIOMETRICS or
        DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS

    /** What we last set in this process; null = unknown (the policy outlives the process). */
    var up: Boolean? = null
        private set

    private var idle = false // retired: nothing to clean up until the wall is wanted again

    fun set(ctx: Context, on: Boolean): Boolean {
        idle = false
        if (up == on) return true
        try {
            val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
            val cn = ComponentName(ctx, Admin::class.java)
            if (dpm == null || !dpm.isAdminActive(cn)) {
                Trace.t("wall: admin not active (run scripts/admin.sh on)")
                up = null
                return false
            }
            dpm.setKeyguardDisabledFeatures(cn, if (on) FEATURES else 0)
            up = on
            Trace.t(if (on) "wall UP" else "wall down")
            return true
        } catch (e: Throwable) {
            Log.w("HabitGuard", "wall set failed", e)
            Trace.t("wall FAILED: $e")
            up = null
            return false
        }
    }

    /** The option is off: leave nothing behind (lift the wall, give the admin rights back). Once per process. */
    fun retire(ctx: Context) {
        if (idle) return
        idle = true
        try {
            val dpm = ctx.getSystemService(DevicePolicyManager::class.java) ?: return
            val cn = ComponentName(ctx, Admin::class.java)
            if (!dpm.isAdminActive(cn)) {
                up = null
                return
            }
            dpm.setKeyguardDisabledFeatures(cn, 0)
            dpm.removeActiveAdmin(cn)
            up = null
            Trace.t("wall: retired (admin removed)")
        } catch (e: Throwable) {
            Log.w("HabitGuard", "wall retire failed", e)
        }
    }
}
