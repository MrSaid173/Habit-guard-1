package com.habitguard

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/** Black screen, optionally with a bright countdown. Consumes all touches. */
class GuardView(ctx: Context, showText: Boolean) : FrameLayout(ctx) {
    private val label = TextView(ctx)
    private var shown = -1

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        keepScreenOn = true
        label.setTextColor(Color.WHITE)
        label.setTypeface(Typeface.DEFAULT_BOLD)
        label.textSize = 120f
        label.includeFontPadding = false
        label.gravity = Gravity.CENTER
        addView(
            label,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setCountdown(showText)
    }

    /** The label always exists so a long-lived view (Cover) can switch the digits on and off. */
    fun setCountdown(show: Boolean) {
        label.visibility = if (show) View.VISIBLE else View.GONE
    }

    fun setSeconds(s: Int) {
        if (s == shown) return // the tick runs 5x per second: redraw only when the digit changes
        shown = s
        label.text = s.toString()
    }
}
