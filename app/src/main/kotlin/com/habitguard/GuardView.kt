package com.habitguard

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/** Black screen, optionally with a bright countdown. Consumes all touches. */
class GuardView(ctx: Context, showText: Boolean) : FrameLayout(ctx) {
    private val label = TextView(ctx)

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        keepScreenOn = true
        if (showText) {
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
        }
    }

    fun setSeconds(s: Int) {
        if (label.parent != null) label.text = s.toString()
    }
}
