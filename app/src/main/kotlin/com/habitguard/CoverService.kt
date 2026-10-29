package com.habitguard

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Only exists to own the accessibility overlay window (see Cover). It listens to no events
 * and reads no screen content. Enabled / disabled by module/scripts/a11y.sh.
 */
class CoverService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        Guard.init(applicationContext)
        Cover.attach(this)
        Guard.onCoverReady()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        Cover.detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Cover.detach()
        super.onDestroy()
    }
}
