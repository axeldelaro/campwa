package com.sentinelcam.app.control

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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
 * Unified Fullscreen Parental Control & Lost Mode Lock Shield (v13):
 * - Timed app lock, daily quota, global pause, scheduled night curfew, school whitelist,
 *   anti-bypass (Settings/Play Store), and Lost Mode with emergency call button.
 * - Supports "+15 min" parent extension requests and Admin PIN unlock with intruder capture.
 */
class AppBlockActivity : AppCompatActivity() {

    private lateinit var prefs: PortalPreferences
    private val handler = Handler(Looper.getMainLooper())

    private var blockMode: String = MODE_APP_LOCK
    private var blockedPackage: String = ""
    private var blockedAppName: String = ""
    private var blockedUntilMs: Long = 0L
    private var wrongPinAttempts = 0

    private lateinit var badgeView: TextView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var countdownView: TextView
    private lateinit var callOwnerBtn: Button
    private lateinit var requestMoreBtn: Button
    private lateinit var homeBtn: Button
    private lateinit var pinRow: LinearLayout
    private lateinit var pinInput: EditText
    private lateinit var pinErrorView: TextView

    private val tickRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val stillBlocked = when (blockMode) {
                MODE_LOST -> prefs.lostModeEnabled
                MODE_BYPASS -> (prefs.blockSettingsAndStore || prefs.surgicalSettingsPinEnabled || prefs.backdoorGuardEnabled) &&
                    prefs.settingsUnlockUntilMs <= now
                MODE_REELS -> {
                    blockedUntilMs = prefs.reelsCooldownUntilMs
                    blockedUntilMs > now && prefs.parentFreeModeUntilMs <= now
                }
                MODE_PAUSE -> {
                    blockedUntilMs = prefs.screenPausedUntilMs
                    blockedUntilMs > now && prefs.parentFreeModeUntilMs <= now
                }
                MODE_CURFEW -> {
                    if (prefs.parentFreeModeUntilMs <= now && prefs.isCurfewActiveNow(now)) {
                        blockedUntilMs = prefs.getCurfewEndTimestampMs(now)
                        true
                    } else {
                        false
                    }
                }
                MODE_SCHOOL -> {
                    blockedUntilMs = prefs.schoolModeUntilMs
                    blockedUntilMs > now && prefs.parentFreeModeUntilMs <= now
                }
                MODE_QUOTA -> {
                    if (blockedPackage.startsWith("group:")) {
                        val gid = blockedPackage.removePrefix("group:")
                        val g = prefs.getAppGroupQuotas().firstOrNull { it.id == gid }
                        if (g == null) {
                            false
                        } else {
                            val map = ScreenTimeHelper.computeTodayUsageSecByPackage(applicationContext)
                            var sum = 0L
                            for (p in g.packages) sum += map[p] ?: 0L
                            val allowed = (g.dailyLimitMinutes + prefs.getDailyBonusMinutes(blockedPackage)) * 60L
                            sum >= allowed
                        }
                    } else {
                        val q = prefs.getAppQuotas().firstOrNull { it.packageName == blockedPackage }
                        if (q == null) {
                            false
                        } else {
                            val used = ScreenTimeHelper.computeTodayUsageSecByPackage(applicationContext)[blockedPackage] ?: 0L
                            val allowed = (q.dailyLimitMinutes + prefs.getDailyBonusMinutes(blockedPackage)) * 60L
                            used >= allowed
                        }
                    }
                }
                else -> {
                    blockedUntilMs = prefs.getActiveAppLockRules(now)
                        .firstOrNull { it.packageName == blockedPackage }?.blockedUntilMs ?: 0L
                    blockedUntilMs > now
                }
            }

            if (!stillBlocked) {
                finish()
                return
            }

            if (blockMode == MODE_LOST || blockMode == MODE_BYPASS) {
                countdownView.visibility = View.GONE
            } else {
                countdownView.visibility = View.VISIBLE
                countdownView.text = formatRemainingTime((blockedUntilMs - now).coerceAtLeast(0L))
            }

            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PortalPreferences(this)
        readIntentExtras(intent)
        setContentView(buildUi())
        refreshVisualState()
        handler.post(tickRunnable)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntentExtras(intent)
        refreshVisualState()
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android API 33")
    override fun onBackPressed() {
        if (blockMode != MODE_LOST) {
            goHome()
        }
    }

    private fun readIntentExtras(src: Intent?) {
        blockMode = src?.getStringExtra(EXTRA_MODE).orEmpty().ifBlank { MODE_APP_LOCK }
        blockedPackage = src?.getStringExtra(EXTRA_PACKAGE).orEmpty()
        blockedAppName = src?.getStringExtra(EXTRA_APP_NAME).orEmpty()
        blockedUntilMs = src?.getLongExtra(EXTRA_UNTIL_MS, 0L) ?: 0L
    }

    private fun refreshVisualState() {
        val label = blockedAppName.ifBlank { "Application" }
        when (blockMode) {
            MODE_LOST -> {
                badgeView.text = "MODE PERDU"
                badgeView.setTextColor(Color.parseColor("#FB7185"))
                titleView.text = "Téléphone verrouillé"
                subtitleView.text = prefs.lostModeMessage.ifBlank {
                    "Cet appareil a été déclaré perdu. Merci de contacter le propriétaire."
                }
                subtitleView.visibility = View.VISIBLE
                val phone = prefs.lostModePhone.trim()
                if (phone.isNotEmpty()) {
                    callOwnerBtn.text = "Appeler $phone"
                    callOwnerBtn.visibility = View.VISIBLE
                } else {
                    callOwnerBtn.visibility = View.GONE
                }
                requestMoreBtn.visibility = View.GONE
                homeBtn.visibility = View.GONE
            }

            MODE_BYPASS -> {
                badgeView.text = "PROTECTION"
                badgeView.setTextColor(Color.parseColor("#FBBF24"))
                titleView.text = "$label protégé"
                subtitleView.text = "Le code administrateur est requis pour accéder à ces réglages."
                subtitleView.visibility = View.VISIBLE
                callOwnerBtn.visibility = View.GONE
                requestMoreBtn.visibility = View.GONE
                homeBtn.visibility = View.VISIBLE
            }

            MODE_CURFEW -> {
                badgeView.text = "MODE NUIT"
                badgeView.setTextColor(Color.parseColor("#818CF8"))
                titleView.text = "Couvre-feu actif"
                subtitleView.text = "L’appareil sera de nouveau disponible à la fin de la plage horaire."
                subtitleView.visibility = View.VISIBLE
                callOwnerBtn.visibility = View.GONE
                requestMoreBtn.visibility = View.VISIBLE
                requestMoreBtn.text = "Demander +15 min au parent"
                requestMoreBtn.isEnabled = true
                homeBtn.visibility = View.VISIBLE
            }

            MODE_SCHOOL -> {
                badgeView.text = "MODE ÉCOLE"
                badgeView.setTextColor(Color.parseColor("#38BDF8"))
                titleView.text = "$label en pause"
                subtitleView.text = "Seules les applications scolaires autorisées sont accessibles."
                subtitleView.visibility = View.VISIBLE
                callOwnerBtn.visibility = View.GONE
                requestMoreBtn.visibility = View.VISIBLE
                requestMoreBtn.text = "Demander l’accès (+15 min)"
                requestMoreBtn.isEnabled = true
                homeBtn.visibility = View.VISIBLE
            }

            MODE_QUOTA -> {
                badgeView.text = "QUOTA ATTEINT"
                badgeView.setTextColor(Color.parseColor("#FB923C"))
                titleView.text = "Temps épuisé sur $label"
                subtitleView.text = "La limite quotidienne est atteinte jusqu’à minuit."
                subtitleView.visibility = View.VISIBLE
                callOwnerBtn.visibility = View.GONE
                requestMoreBtn.visibility = View.VISIBLE
                requestMoreBtn.text = "Demander +15 min au parent"
                requestMoreBtn.isEnabled = true
                homeBtn.visibility = View.VISIBLE
            }

            MODE_REELS -> {
                badgeView.text = "PAUSE REELS / SHORTS"
                badgeView.setTextColor(Color.parseColor("#D97706"))
                titleView.text = "Pause vidéos courtes"
                subtitleView.text = "Tu as atteint la limite de ${prefs.reelsSwipeLimit} vidéos d’affilée. Petite pause avant de reprendre."
                subtitleView.visibility = View.VISIBLE
                callOwnerBtn.visibility = View.GONE
                requestMoreBtn.visibility = View.VISIBLE
                requestMoreBtn.text = "Demander +15 min au parent"
                requestMoreBtn.isEnabled = true
                homeBtn.visibility = View.VISIBLE
            }

            MODE_PAUSE -> {
                badgeView.text = "EN PAUSE"
                badgeView.setTextColor(Color.parseColor("#34D399"))
                titleView.text = "Appareil en pause"
                subtitleView.visibility = View.GONE
                callOwnerBtn.visibility = View.GONE
                requestMoreBtn.visibility = View.VISIBLE
                requestMoreBtn.text = "Demander +15 min au parent"
                requestMoreBtn.isEnabled = true
                homeBtn.visibility = View.VISIBLE
            }

            else -> {
                badgeView.text = "VERROUILLÉ"
                badgeView.setTextColor(Color.parseColor("#34D399"))
                titleView.text = "$label verrouillée"
                subtitleView.visibility = View.GONE
                callOwnerBtn.visibility = View.GONE
                requestMoreBtn.visibility = View.VISIBLE
                requestMoreBtn.text = "Demander +15 min au parent"
                requestMoreBtn.isEnabled = true
                homeBtn.visibility = View.VISIBLE
            }
        }
    }

    private fun buildUi(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F4F7FB"))
            setPadding(dp(24), dp(36), dp(24), dp(36))
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.parseColor("#E2E8F0"))
            }
            elevation = dp(6).toFloat()
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }

        badgeView = TextView(this).apply {
            textSize = 11f
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(dp(14), dp(5), dp(14), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F1F5F9"))
                cornerRadius = dp(99).toFloat()
            }
        }
        card.addView(badgeView)

        titleView = TextView(this).apply {
            textSize = 21f
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(6))
        }
        card.addView(titleView)

        subtitleView = TextView(this).apply {
            textSize = 13.5f
            setTextColor(Color.parseColor("#475569"))
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(14))
        }
        card.addView(subtitleView)

        countdownView = TextView(this).apply {
            textSize = 28f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(20))
        }
        card.addView(countdownView)

        callOwnerBtn = Button(this).apply {
            isAllCaps = false
            textSize = 15f
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(Color.parseColor("#09090B"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#34D399"))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(20), dp(13), dp(20), dp(13))
            visibility = View.GONE
            setOnClickListener {
                val phone = prefs.lostModePhone.trim()
                if (phone.isNotEmpty()) {
                    try {
                        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        card.addView(
            callOwnerBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        )

        homeBtn = Button(this).apply {
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

        requestMoreBtn = Button(this).apply {
            text = "Demander +15 min au parent"
            isAllCaps = false
            textSize = 13f
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(Color.parseColor("#E4E4E7"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#27272A"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#3F3F46"))
            }
            setPadding(dp(18), dp(11), dp(18), dp(11))
            setOnClickListener {
                val targetPkg = if (blockMode == MODE_PAUSE || blockMode == MODE_CURFEW) {
                    "__global__"
                } else {
                    blockedPackage.ifBlank { "__global__" }
                }
                val targetName = if (blockMode == MODE_PAUSE || blockMode == MODE_CURFEW) {
                    "Appareil"
                } else {
                    blockedAppName.ifBlank { "Application" }
                }
                CameraForegroundService.submitTimeExtensionRequest(
                    applicationContext,
                    targetPkg,
                    targetName,
                    15
                )
                isEnabled = false
                text = "Demande envoyée au parent ✓"
            }
        }
        card.addView(
            requestMoreBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
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
                        wrongPinAttempts = 0
                        val now = System.currentTimeMillis()
                        when (blockMode) {
                            MODE_LOST -> prefs.lostModeEnabled = false
                            MODE_BYPASS -> prefs.settingsUnlockUntilMs = now + 5 * 60_000L
                            MODE_PAUSE -> prefs.screenPausedUntilMs = 0L
                            MODE_CURFEW -> prefs.curfewBypassUntilMs = now + 30 * 60_000L
                            MODE_SCHOOL -> prefs.schoolModeUntilMs = 0L
                            MODE_REELS -> {
                                prefs.reelsCooldownUntilMs = 0L
                                prefs.resetReelsSessionSwipes()
                            }
                            MODE_QUOTA -> if (blockedPackage.isNotEmpty()) prefs.addDailyBonusMinutes(blockedPackage, 30)
                            else -> if (blockedPackage.isNotEmpty()) prefs.removeAppLockRule(blockedPackage)
                        }
                        CameraForegroundService.syncAccessChannels(applicationContext)
                        finish()
                    } else {
                        wrongPinAttempts++
                        pinErrorView.text = "Code administrateur incorrect."
                        pinErrorView.visibility = View.VISIBLE
                        if (wrongPinAttempts >= 2) {
                            CameraForegroundService.reportAdminPinIntrusion(
                                applicationContext,
                                wrongPinAttempts,
                                blockedAppName.ifBlank { blockMode }
                            )
                        }
                    }
                }
            }
            pinRow.addView(
                confirmBtn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            )
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
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_PACKAGE = "extra_pkg"
        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_UNTIL_MS = "extra_until_ms"

        const val MODE_APP_LOCK = "app_lock"
        const val MODE_QUOTA = "quota"
        const val MODE_PAUSE = "pause"
        const val MODE_CURFEW = "curfew"
        const val MODE_SCHOOL = "school"
        const val MODE_BYPASS = "bypass"
        const val MODE_LOST = "lost"
        const val MODE_REELS = "reels"
    }
}
