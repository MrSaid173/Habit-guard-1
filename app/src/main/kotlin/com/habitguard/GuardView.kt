package com.habitguard

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/** Black screen, optionally with a dim countdown. Consumes all touches. */
class GuardView(ctx: Context, showText: Boolean) : FrameLayout(ctx) {
    private val label = TextView(ctx)

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        if (showText) {
            label.setTextColor(0xFF777777.toInt())
            label.textSize = 56f
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
