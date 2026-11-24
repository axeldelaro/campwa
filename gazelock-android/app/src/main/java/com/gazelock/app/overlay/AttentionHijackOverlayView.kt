package com.gazelock.app.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * #2 Sudden Attention Hijack Overlay ("Coupure d'Emprise") for Ghost Mode:
 * - Mounts fullscreen touch-capturing over ALL apps while video opacity spikes to 96%!
 * - User MUST keep unbroken eye contact for [requiredSec] seconds to reclaim touch control!
 * - Looking away resets the hijack focus bar back to 0!
 */
class AttentionHijackOverlayView(
    context: Context,
    private val requiredSec: Int,
    private val onOpenThreeMinPortal: () -> Unit
) : FrameLayout(context) {

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val cardBox: LinearLayout
    private val titleTv: TextView
    private val timerTv: TextView
    private val subTv: TextView
    private val progressBar: ProgressBar

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event != null &&
            (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_APP_SWITCH)
        ) return true
        return super.dispatchKeyEvent(event)
    }

    init {
        setBackgroundColor(Color.argb(42, 18, 4, 24)) // Transparent enough so the 96% video blasts right through!
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true

        cardBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.argb(215, 26, 6, 36))
                setStroke(dp(2), Color.rgb(236, 72, 153))
            }
        }

        titleTv = TextView(context).apply {
            text = "⚡ COUPURE D'EMPRISE — TACTILE CONFISQUÉ !"
            setTextColor(Color.rgb(249, 168, 212)); textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        timerTv = TextView(context).apply {
            text = String.format("👁️ FIXE L'ÉCRAN : %02d.0 s", requiredSec)
            setTextColor(Color.WHITE); textSize = 21f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
        }
        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = 0
            progressTintList = ColorStateList.valueOf(Color.rgb(16, 185, 129))
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(90, 255, 255, 255))
        }
        subTv = TextView(context).apply {
            text = "Ne détourne pas les yeux pendant $requiredSec s pour récupérer le tactile de ton téléphone !"
            setTextColor(Color.rgb(225, 220, 240)); textSize = 11.4f; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0)
        }

        cardBox.addView(titleTv)
        cardBox.addView(timerTv)
        cardBox.addView(progressBar, LinearLayout.LayoutParams(dp(245), dp(7)))
        cardBox.addView(subTv)

        addView(
            cardBox,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
                topMargin = dp(44); marginStart = dp(18); marginEnd = dp(18)
            }
        )

        val portalBtn = Button(context).apply {
            text = "🔓 Portail 3 min"
            isAllCaps = false; textSize = 11.2f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(Color.argb(190, 162, 28, 175))
            }
            minHeight = 0; minimumHeight = 0; setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnClickListener { onOpenThreeMinPortal() }
        }
        addView(
            portalBtn,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                bottomMargin = dp(26); marginEnd = dp(16)
            }
        )
    }

    fun updateHijackState(focusedMs: Long, requiredMs: Long, isLooking: Boolean, reason: String) {
        val remMs = (requiredMs - focusedMs).coerceAtLeast(0L)
        progressBar.progress = ((focusedMs * 1000L) / requiredMs.coerceAtLeast(1L)).toInt().coerceIn(0, 1000)
        if (isLooking) {
            timerTv.text = String.format("👁️ FIXE L'ÉCRAN : %.1f s", remMs / 1000f)
            timerTv.setTextColor(Color.rgb(167, 243, 208))
            progressBar.progressTintList = ColorStateList.valueOf(Color.rgb(16, 185, 129))
            subTv.text = "Parfait ! Maintiens ton regard fixe pour récupérer tes applis…"
        } else {
            timerTv.text = "🚨 REGARD PERDU ! RESET !"
            timerTv.setTextColor(Color.rgb(252, 165, 165))
            progressBar.progressTintList = ColorStateList.valueOf(Color.rgb(244, 63, 94))
            subTv.text = "$reason — Fixe bien l'écran pour relancer les ${requiredMs / 1000L} s !"
        }
    }
}
