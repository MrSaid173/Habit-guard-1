package com.habitguard

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Only exists to own the accessibility overlay window (see Cover). It listens to no events
 * and reads no screen content. Enabled / disabled by module/scripts/a11y.sh.
 */
class CoverService : AccessibilityService() {
    companion object {
        @Volatile private var instance: CoverService? = null

        /**
         * Lock the device right now (GLOBAL_ACTION_LOCK_SCREEN, API 28+): the keyguard is locked at once
         * instead of after the "lock after screen timeout" delay, so a wake-up that was put back to sleep
         * cannot leave the phone unlocked. False if the service is not connected or the ROM refused.
         */
        fun lockNow(): Boolean {
            val s = instance ?: return false
            return try {
                s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            } catch (_: Throwable) {
                false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Guard.init(applicationContext)
        Cover.attach(this)
        Guard.onCoverReady()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        Cover.detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        Cover.detach()
        super.onDestroy()
    }
}
