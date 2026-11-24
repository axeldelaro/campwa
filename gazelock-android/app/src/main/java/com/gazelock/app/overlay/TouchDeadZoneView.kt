package com.gazelock.app.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * #9 Punitive Confiscated Touch Dead-Zone for Ghost Mode:
 * - Mounted as a localized touch-blocking window over the center/lower screen after a look-away fault!
 * - Blocks taps ONLY inside its own footprint; shrinks & disappears as the user holds steady eye contact!
 */
class TouchDeadZoneView(context: Context) : LinearLayout(context) {

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val titleTv: TextView
    private val subTv: TextView
    private val shrinkBar: ProgressBar

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        isClickable = true // Deliberately swallows touches ONLY within this confiscated zone!
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(Color.argb(196, 136, 19, 55))
            setStroke(dp(2), Color.rgb(251, 113, 133))
        }

        titleTv = TextView(context).apply {
            text = "🚫 ZONE TACTILE CONFISQUÉE"
            setTextColor(Color.WHITE); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        subTv = TextView(context).apply {
            text = "Fixe l'écran (18 s) pour libérer cette zone !"
            setTextColor(Color.rgb(254, 202, 202)); textSize = 11f; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(6))
        }
        shrinkBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = 1000
            progressTintList = ColorStateList.valueOf(Color.rgb(251, 113, 133))
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(85, 255, 255, 255))
        }

        addView(titleTv)
        addView(subTv)
        addView(shrinkBar, LayoutParams(dp(200), dp(6)))

        setOnClickListener {
            // Playful refusal shake when user tries to tap through the confiscated zone!
            animate().translationX(dp(8).toFloat()).setDuration(45L).withEndAction {
                animate().translationX(-dp(8).toFloat()).setDuration(45L).withEndAction {
                    animate().translationX(0f).setDuration(45L).start()
                }.start()
            }.start()
        }
    }

    fun updateRemaining(remainingMs: Long, totalMs: Long, isLooking: Boolean) {
        val ratio = (remainingMs.toFloat() / totalMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
        shrinkBar.progress = (ratio * 1000f).toInt()
        val sec = ((remainingMs + 999L) / 1000L).coerceAtLeast(0L)
        subTv.text = if (isLooking) {
            "👁️ Libération en cours… ${sec} s"
        } else {
            "🚨 REGARD DÉTOURNÉ ! Zone verrouillée (${sec} s)"
        }
        val visualScale = 0.68f + 0.32f * ratio
        scaleX = visualScale
        scaleY = visualScale
    }
}
