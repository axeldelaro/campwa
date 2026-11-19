package com.sentinelcam.app.control

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.sentinelcam.app.service.CameraForegroundService
import com.sentinelcam.app.storage.PortalPreferences

/**
 * Fullscreen Minimalist Lock Shield displayed when a timed app lock or global screen pause is active.
 * Returns cleanly to Android Home Screen on Back or Home press, and supports early unlock with Admin PIN.
 */
class AppBlockActivity : AppCompatActivity() {

    private lateinit var prefs: PortalPreferences
    private val handler = Handler(Looper.getMainLooper())

    private var blockedPackage: String = ""
    private var blockedAppName: String = ""
    private var isGlobalPause: Boolean = false
    private var blockedUntilMs: Long = 0L

    private lateinit var titleView: TextView
    private lateinit var countdownView: TextView
    private lateinit var pinRow: LinearLayout
    private lateinit var pinInput: EditText
    private lateinit var pinErrorView: TextView

    private val tickRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val effectiveUntil = if (isGlobalPause) {
                prefs.screenPausedUntilMs
            } else {
                prefs.getActiveAppLockRules(now).firstOrNull { it.packageName == blockedPackage }?.blockedUntilMs ?: 0L
            }
            blockedUntilMs = effectiveUntil

            val remaining = effectiveUntil - now
            if (remaining <= 0L) {
                finish()
                return
            }

            countdownView.text = formatRemainingTime(remaining)
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PortalPreferences(this)
        readIntentExtras(intent)
        setContentView(buildUi())
        handler.post(tickRunnable)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntentExtras(intent)
        updateHeaderText()
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android API 33")
    override fun onBackPressed() {
        goHome()
    }

    private fun readIntentExtras(src: Intent?) {
        blockedPackage = src?.getStringExtra(EXTRA_PACKAGE).orEmpty()
        blockedAppName = src?.getStringExtra(EXTRA_APP_NAME).orEmpty()
        isGlobalPause = src?.getBooleanExtra(EXTRA_GLOBAL_PAUSE, false) ?: false
        blockedUntilMs = src?.getLongExtra(EXTRA_UNTIL_MS, 0L) ?: 0L
    }

    private fun updateHeaderText() {
        titleView.text = if (isGlobalPause) {
            "Appareil en pause"
        } else {
            "${blockedAppName.ifBlank { "Application" }} verrouillée"
        }
    }

    private fun buildUi(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#09090B"))
            setPadding(dp(28), dp(40), dp(28), dp(40))
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#18181B"))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.parseColor("#27272A"))
            }
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }

        val badge = TextView(this).apply {
            text = "VERROUILLÉ"
            textSize = 11f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#34D399"))
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1034D399"))
                cornerRadius = dp(99).toFloat()
            }
        }
        card.addView(badge)

        titleView = TextView(this).apply {
            textSize = 20f
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(6))
        }
        updateHeaderText()
        card.addView(titleView)

        countdownView = TextView(this).apply {
            text = formatRemainingTime((blockedUntilMs - System.currentTimeMillis()).coerceAtLeast(0L))
            textSize = 26f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#E4E4E7"))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(22))
        }
        card.addView(countdownView)

        val homeBtn = Button(this).apply {
            text = "Retour à l’accueil"
            isAllCaps = false
            textSize = 14f
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(Color.parseColor("#09090B"))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setOnClickListener { goHome() }
        }
        card.addView(
            homeBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        if (prefs.hasAdminPin()) {
            val unlockLink = TextView(this).apply {
                text = "Déverrouiller avec le code administrateur"
                textSize = 12f
                setTextColor(Color.parseColor("#A1A1AA"))
                gravity = Gravity.CENTER
                setPadding(0, dp(18), 0, dp(4))
                setOnClickListener {
                    pinRow.visibility = if (pinRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
            }
            card.addView(unlockLink)

            pinRow = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(0, dp(12), 0, 0)
            }

            pinInput = EditText(this).apply {
                hint = "Code administrateur"
                setHintTextColor(Color.parseColor("#71717A"))
                setTextColor(Color.WHITE)
                textSize = 14f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#09090B"))
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), Color.parseColor("#3F3F46"))
                }
            }
            pinRow.addView(
                pinInput,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            pinErrorView = TextView(this).apply {
                textSize = 11f
                setTextColor(Color.parseColor("#FB7185"))
                visibility = View.GONE
                setPadding(0, dp(6), 0, 0)
            }
            pinRow.addView(pinErrorView)

            val confirmBtn = Button(this).apply {
                text = "Débloquer"
                isAllCaps = false
                textSize = 13f
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#10B981"))
                    cornerRadius = dp(10).toFloat()
                }
                setOnClickListener {
                    val code = pinInput.text?.toString().orEmpty()
                    if (prefs.verifyAdminPin(code)) {
                        if (isGlobalPause) {
                            prefs.screenPausedUntilMs = 0L
                        } else if (blockedPackage.isNotEmpty()) {
                            prefs.removeAppLockRule(blockedPackage)
                        }
                        CameraForegroundService.syncAccessChannels(applicationContext)
                        finish()
                    } else {
                        pinErrorView.text = "Code administrateur incorrect."
                        pinErrorView.visibility = View.VISIBLE
                    }
                }
            }
            val btnParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
            pinRow.addView(confirmBtn, btnParams)
            card.addView(
                pinRow,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        } else {
            pinRow = LinearLayout(this).apply { visibility = View.GONE }
            pinInput = EditText(this)
            pinErrorView = TextView(this)
        }

        root.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        return root
    }

    private fun goHome() {
        try {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(home)
        } catch (_: Throwable) {
        }
        finish()
    }

    private fun formatRemainingTime(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return if (h > 0L) {
            String.format("%dh %02dm %02ds", h, m, s)
        } else {
            String.format("%02dm %02ds", m, s)
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "extra_pkg"
        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_GLOBAL_PAUSE = "extra_global_pause"
        const val EXTRA_UNTIL_MS = "extra_until_ms"
    }
}
