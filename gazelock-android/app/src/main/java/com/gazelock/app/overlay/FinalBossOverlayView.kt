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
 * #76 Final Boss Overlay (Final 30 seconds of timed session):
 * - Locks ALL touches at 100% video opacity + accelerated 118 BPM binaural & spiral!
 * - Zero-Fault Rule: looking away > 1.0s during the 30s Final Boss -> BOSS STRIKE (+60s penalty!).
 * - Surviving to 00:00 vanquishes the Boss and finishes the session cleanly!
 */
class FinalBossOverlayView(
    context: Context,
    private val onOpenPortal: () -> Unit
) : FrameLayout(context) {

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val titleTv: TextView
    private val hpBar: ProgressBar
    private val timerTv: TextView
    private val subTv: TextView

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event != null &&
            (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_APP_SWITCH)
        ) return true
        return super.dispatchKeyEvent(event)
    }

    init {
        setBackgroundColor(Color.argb(36, 36, 4, 16))
        isClickable = true; isFocusable = true; isFocusableInTouchMode = true

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.argb(226, 42, 6, 18))
                setStroke(dp(2), Color.rgb(250, 204, 21))
            }
        }

        titleTv = TextView(context).apply {
            text = "👑 BOSS FINAL — 30 s ZÉRO FAUTE !"
            setTextColor(Color.rgb(253, 224, 71)); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        timerTv = TextView(context).apply {
            text = "⚔️ SURVIE : 30.0 s"
            setTextColor(Color.WHITE); textSize = 22f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(6))
        }
        hpBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = 1000
            progressTintList = ColorStateList.valueOf(Color.rgb(244, 63, 94))
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(90, 255, 255, 255))
        }
        subTv = TextView(context).apply {
            text = "100 % verrouillé ! Un seul écart (> 1,0 s) = +60 s de pénalité !"
            setTextColor(Color.rgb(254, 202, 202)); textSize = 11.4f; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0)
        }

        card.addView(titleTv)
        card.addView(timerTv)
        card.addView(hpBar, LinearLayout.LayoutParams(dp(250), dp(8)))
        card.addView(subTv)

        addView(
            card,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
                topMargin = dp(42); marginStart = dp(18); marginEnd = dp(18)
            }
        )

        addView(
            Button(context).apply {
                text = "🔓 Portail"
                isAllCaps = false; textSize = 11f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.argb(185, 162, 28, 175)) }
                minHeight = 0; minimumHeight = 0; setPadding(dp(12), dp(7), dp(12), dp(7))
                setOnClickListener { onOpenPortal() }
            },
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                bottomMargin = dp(24); marginEnd = dp(16)
            }
        )
    }

    fun updateBoss(remainingMs: Long, totalBossMs: Long, isLooking: Boolean, awayMs: Long, reason: String) {
        val ratio = (remainingMs.toFloat() / totalBossMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
        hpBar.progress = (ratio * 1000f).toInt()
        if (isLooking) {
            timerTv.text = String.format("⚔️ SURVIE : %.1f s", remainingMs / 1000f)
            timerTv.setTextColor(Color.rgb(253, 224, 71))
            hpBar.progressTintList = ColorStateList.valueOf(Color.rgb(244, 63, 94))
            subTv.text = "Ne détourne pas les yeux ! Tiens jusqu'à 0.0 s pour vaincre le Boss !"
        } else {
            val leftGrace = (1_000L - awayMs).coerceAtLeast(0L)
            timerTv.text = String.format("💀 FRAPPE BOSS dans %.1f s !", leftGrace / 1000f)
            timerTv.setTextColor(Color.rgb(251, 113, 133))
            hpBar.progressTintList = ColorStateList.valueOf(Color.rgb(250, 204, 21))
            subTv.text = "$reason — Échec = +60 s ajoutées !"
        }
    }
}
