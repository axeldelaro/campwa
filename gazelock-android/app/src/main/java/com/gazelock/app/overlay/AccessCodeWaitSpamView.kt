package com.gazelock.app.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.gazelock.app.audio.PavlovSoundEngine
import com.gazelock.app.config.GazeConfig
import com.gazelock.app.config.GazeConfigSnapshot
import com.gazelock.app.media.VideoFeedRepository
import com.gazelock.app.overlay.minigames.ColorTintOddOneView
import com.gazelock.app.overlay.minigames.DualCompareDiffView
import com.gazelock.app.overlay.minigames.FlashTargetHuntView
import com.gazelock.app.overlay.minigames.ImageReconstructionView
import com.gazelock.app.overlay.minigames.JigsawEdgeSnapView
import com.gazelock.app.overlay.minigames.MemoryFlashView
import com.gazelock.app.overlay.minigames.MosaicPairChainView
import com.gazelock.app.overlay.minigames.MovingSequenceView
import com.gazelock.app.overlay.minigames.PhotoPinRecallView
import com.gazelock.app.overlay.minigames.PrecisionGaugeStopView
import com.gazelock.app.overlay.minigames.RapidMathImageGridView
import com.gazelock.app.overlay.minigames.ScratchRevealMatchView
import com.gazelock.app.overlay.minigames.SlidingPuzzleView
import com.gazelock.app.overlay.minigames.SpotIntruderView
import com.gazelock.app.overlay.minigames.TargetTapBlitzView
import com.gazelock.app.overlay.minigames.ZoomDetailMatchView
import kotlin.random.Random

/**
 * Fullscreen Interactive Overlay Unlock Portal (v7.0 TITAN):
 * - #70 Daily Escalating Portal Duration (3 min -> 4 -> 5 -> 6 min).
 * - Continuous 0.1s (10 FPS) folder image spam -> accelerates to 0.05s (20 FPS!) in #17 Final 45s Overload Sprint!
 * - #22 Gaze-Gated Portal: looking away freezes countdown (+5s after 2.2s).
 * - 18 DISTINCT VISUAL MINI-GAMES (zero dictation!) with configurable 3..12 challenges per portal.
 * - v7.0 TITAN Hardening: Cancel Tax (+30s), Manual 6-Digit Code Typing Verification, Hardcore Double Mistakes
 *   (+10s + extra trap pop-ups), and ON FIRE Combo Streak (+5s if broken)!
 */
class AccessCodeWaitSpamView(
    context: Context,
    private val cfg: GazeConfigSnapshot,
    private val gazeLookingSupplier: (() -> Boolean)? = null,
    private val onOverloadSprintChanged: ((Boolean) -> Unit)? = null,
    private val onIssueCode: () -> String,
    private val onRecordDailyPortalCompletion: (() -> Unit)? = null,
    private val onCodeUnlockedAndExit: ((String) -> Unit)? = null,
    private val onCancelWait: () -> Unit
) : FrameLayout(context) {

    private enum class GameType {
        SLIDING_3X3, SLIDING_4X4_HELL, SWAP, ROTATE, MEMORY, SEQUENCE, INTRUDER, FLASH_HUNT_9FPS,
        SCRATCH_REVEAL, MAHJONG_CHAIN, ALTERED_COPY, SYMBOL_MATH, PRECISION_GAUGE, STRIP_SNAP,
        TARGET_BLITZ, PHOTO_PIN, ZOOM_DETAIL, DUAL_COMPARE
    }

    private data class ScheduledChallenge(
        val type: GameType,
        val triggerAtRemainingMs: Long,
        var triggered: Boolean = false
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val portalWaitSec = cfg.currentPortalWaitSec()
    private val totalWaitMs = portalWaitSec * 1000L
    private val portalMinLabel = "${portalWaitSec / 60} min"
    private val totalMinigames = if (cfg.unlockMinigamesEnabled) cfg.portalMinigameCount.coerceIn(3, 12) else 0

    private lateinit var imageView: ImageView
    private val pillBox: LinearLayout
    private lateinit var countdownTv: TextView
    private lateinit var subTv: TextView
    private lateinit var flashAlertTv: TextView
    private lateinit var progressBar: ProgressBar
    private val popupLayer: FrameLayout
    private val minigameLayer: FrameLayout
    private val actionBtn: Button
    private val revealCard: LinearLayout
    private val revealedCodeTv: TextView
    private var codeInputEt: EditText? = null
    private var codeInputErrTv: TextView? = null
    private var issuedRawCode: String = ""
    private var totalMistakesInPortal = 0
    private var pendingTrapPopupAfterGame = false

    private val bitmapPool = ArrayList<Bitmap>()
    private var running = false
    private var remainingWaitMs = totalWaitMs
    private var flashCounter = 0
    private var lastBmpIdx = -1
    private var lastTickElapsedMs = 0L

    private var inOverloadSprint = false
    private var sprintMistakesInFinalGame = 0
    private var portalAwayMs = 0L
    private var portalAwayPenaltyApplied = false

    // Dynamic 3..12 Mini-Game Schedule + Combo Streak
    private val schedule = ArrayList<ScheduledChallenge>()
    private var activeMinigameRelease: (() -> Unit)? = null
    private var minigameActive = false
    private var minigamesSolvedCount = 0
    private var currentGameHadMistake = false
    private var flawlessComboStreak = 0

    private var popupActive = false
    private var popupExpireElapsedMs = 0L
    private var popupDurationMs = 8_500L
    private var popupProgressBar: ProgressBar? = null
    private var nextPopupAtRemainingMs = totalWaitMs - 14_000L
    private var popupsClearedCount = 0
    private var alertUntilElapsedMs = 0L

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (running && event != null &&
            (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_APP_SWITCH)
        ) {
            showTransientAlert("🔒 Annule en bas si tu veux quitter (remet les $portalMinLabel à zéro) !", isError = true)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private val spamTick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            val dt = (now - lastTickElapsedMs).coerceIn(30L, 350L)
            lastTickElapsedMs = now

            val gazeOk = if (cfg.gazeGatedPortalEnabled && gazeLookingSupplier != null) {
                gazeLookingSupplier.invoke()
            } else true

            if (!gazeOk) {
                portalAwayMs += dt
                if (portalAwayMs >= 2_200L && !portalAwayPenaltyApplied) {
                    portalAwayPenaltyApplied = true
                    addPenaltySeconds(5, "👁️ Regard détourné dans le portail ! +5 s !")
                }
            } else {
                portalAwayMs = 0L
                portalAwayPenaltyApplied = false
            }

            if (!minigameActive && gazeOk) {
                remainingWaitMs = (remainingWaitMs - dt).coerceAtLeast(0L)
            }

            val shouldSprint = cfg.overloadSprintEnabled && remainingWaitMs <= 45_000L && remainingWaitMs > 0L
            if (shouldSprint != inOverloadSprint) {
                inOverloadSprint = shouldSprint
                onOverloadSprintChanged?.invoke(shouldSprint)
                updatePillStyle(sprint = shouldSprint, gazeFrozen = !gazeOk)
                if (shouldSprint) {
                    vibrateMs(160L)
                    if (cfg.pavlovSoundEnabled) PavlovSoundEngine.playPunishmentTone()
                    showTransientAlert("🔥 SPRINT SURCHARGE ! Spam 20 FPS (0,05 s) + Échec = Retour 01:00 !", isError = true)
                    nextPopupAtRemainingMs = (remainingWaitMs - 4_500L).coerceAtLeast(6_000L)
                }
            } else if (!gazeOk) {
                updatePillStyle(sprint = inOverloadSprint, gazeFrozen = true)
            }

            val elapsedMs = (totalWaitMs - remainingWaitMs).coerceIn(0L, totalWaitMs)
            progressBar.progress = ((elapsedMs * 1000L) / totalWaitMs.coerceAtLeast(1L)).toInt()
            val remSec = ((remainingWaitMs + 999L) / 1000L).toInt()
            val dayTag = if (cfg.dailyEscalatingPortalEnabled && cfg.portalUsesToday > 0) " (#${cfg.portalUsesToday + 1})" else ""
            countdownTv.text = when {
                !gazeOk -> String.format("👁️ REGARDE L'ÉCRAN ! %02d:%02d", remSec / 60, remSec % 60)
                minigameActive -> String.format("⏸️ DÉFI %d/%d : %02d:%02d", minigamesSolvedCount + 1, totalMinigames, remSec / 60, remSec % 60)
                inOverloadSprint -> String.format("🔥 SURCHARGE 20 FPS : %02d:%02d", remSec / 60, remSec % 60)
                else -> String.format("⏳ PORTAIL$dayTag : %02d:%02d", remSec / 60, remSec % 60)
            }

            if (bitmapPool.isNotEmpty()) {
                var idx = Random.nextInt(bitmapPool.size)
                if (bitmapPool.size > 1 && idx == lastBmpIdx) idx = (idx + 1) % bitmapPool.size
                lastBmpIdx = idx
                val bmp = bitmapPool[idx]
                if (!bmp.isRecycled) {
                    imageView.setImageBitmap(bmp)
                    val sc = if (inOverloadSprint) (1.0f + (flashCounter % 4) * 0.045f) else (1.0f + (flashCounter % 3) * 0.03f)
                    imageView.scaleX = sc; imageView.scaleY = sc
                }
                flashCounter++
                val rateStr = if (inOverloadSprint) "0,05 s" else "0,1 s"
                val gameTag = if (totalMinigames > 0) " · 🧩 $minigamesSolvedCount/$totalMinigames" else ""
                val comboTag = when {
                    flawlessComboStreak >= 3 -> " · 🔥×$flawlessComboStreak"
                    flawlessComboStreak == 2 -> " · ⚡×2"
                    else -> ""
                }
                val popTag = if (cfg.unlockPopupsEnabled) " · 🔔 $popupsClearedCount" else ""
                subTv.text = "🖼️ Spam $rateStr$gameTag$comboTag$popTag"
            } else {
                setBackgroundColor(if (flashCounter++ % 2 == 0) Color.rgb(36, 8, 28) else Color.rgb(16, 8, 34))
                subTv.text = "⏳ Chargement des images… Attente obligatoire $portalMinLabel"
            }

            if (flashAlertTv.visibility == View.VISIBLE && now >= alertUntilElapsedMs) {
                flashAlertTv.visibility = View.GONE
            }

            if (popupActive) {
                val pRem = (popupExpireElapsedMs - now).coerceAtLeast(0L)
                popupProgressBar?.progress = ((pRem * 1000L) / popupDurationMs.coerceAtLeast(1L)).toInt()
                if (pRem <= 0L) {
                    dismissCurrentPopup()
                    if (inOverloadSprint) kickBackToOneMinute("🔥 Pop-up raté en SURCHARGE ! Renvoi à 01:00 !")
                    else addPenaltySeconds(10, "⏰ Pop-up ignoré ! +10 s ajoutées !")
                }
            }

            // Trigger next scheduled visual mini-game cleanly!
            if (cfg.unlockMinigamesEnabled && !minigameActive && gazeOk) {
                val nextIdx = schedule.indexOfFirst { !it.triggered && remainingWaitMs <= it.triggerAtRemainingMs }
                if (nextIdx >= 0) {
                    val ch = schedule[nextIdx]
                    ch.triggered = true
                    dismissCurrentPopup()
                    val isFinal = (nextIdx == schedule.lastIndex) && inOverloadSprint
                    launchMiniGame(ch, stageNumber = nextIdx + 1, isFinalSprintGame = isFinal)
                }
            }

            if (cfg.unlockPopupsEnabled && !minigameActive && !popupActive && gazeOk &&
                remainingWaitMs in 7_500L..nextPopupAtRemainingMs
            ) {
                val gap = if (inOverloadSprint) Random.nextLong(6_500L, 10_500L) else Random.nextLong(14_000L, 21_000L)
                nextPopupAtRemainingMs = (remainingWaitMs - gap).coerceAtLeast(0L)
                showInteractivePopup()
            }

            if (remainingWaitMs <= 0L && !minigameActive) {
                finishWaitAndRevealCode()
            } else {
                val nextDelay = if (inOverloadSprint) GazeConfig.OVERLOAD_SPAM_DELAY_MS else GazeConfig.IMAGE_SPAM_DELAY_MS
                mainHandler.postDelayed(this, nextDelay)
            }
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true; isFocusable = true; isFocusableInTouchMode = true

        imageView = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        addView(imageView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        addView(View(context).apply { setBackgroundColor(Color.argb(65, 10, 6, 20)) },
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val topBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(34), dp(14), dp(6))
        }
        pillBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(9), dp(16), dp(9))
        }
        updatePillStyle(sprint = false, gazeFrozen = false)

        countdownTv = TextView(context).apply {
            text = String.format("⏳ PORTAIL : %02d:00", portalWaitSec / 60)
            setTextColor(Color.WHITE); textSize = 18f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
        }
        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = 0
            progressTintList = ColorStateList.valueOf(Color.rgb(225, 29, 72))
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(90, 255, 255, 255))
        }
        subTv = TextView(context).apply {
            text = "⏳ Préparation du spam & des $totalMinigames mini-jeux ($portalMinLabel)…"
            setTextColor(Color.rgb(253, 224, 71)); textSize = 11f; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0)
        }
        pillBox.addView(countdownTv)
        pillBox.addView(progressBar, LinearLayout.LayoutParams(dp(240), dp(6)).apply { topMargin = dp(5) })
        pillBox.addView(subTv)
        topBox.addView(pillBox)

        flashAlertTv = TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 11.8f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6)); visibility = View.GONE
        }
        topBox.addView(flashAlertTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        addView(topBox, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        popupLayer = FrameLayout(context).apply { visibility = View.GONE }
        addView(popupLayer, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        minigameLayer = FrameLayout(context).apply {
            visibility = View.GONE
            setPadding(dp(12), dp(108), dp(12), dp(74))
        }
        addView(minigameLayer, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val cancelTaxNote = if (cfg.portalCancelTaxEnabled && onCodeUnlockedAndExit != null) " (+30 s & +10 % !)" else " (remet à zéro)"
        actionBtn = Button(context).apply {
            text = "↩ Annuler le portail$cancelTaxNote"
            isAllCaps = false; textSize = 11.4f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(Color.argb(215, 42, 34, 62))
                setStroke(dp(1), Color.argb(120, 255, 255, 255))
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { stopAndRelease(); onCancelWait() }
        }
        addView(
            actionBtn,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(22)
            }
        )

        revealCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(246, 10, 8, 22))
            setPadding(dp(26), dp(26), dp(26), dp(26)); visibility = View.GONE
        }
        revealCard.addView(TextView(context).apply {
            text = "🔓 CRÉNEAU DE $portalMinLabel ACCOMPLI !"
            setTextColor(Color.rgb(134, 239, 172)); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        val nextEscNote = if (cfg.dailyEscalatingPortalEnabled) {
            val nextMin = (3 + (cfg.portalUsesToday + 1).coerceAtMost(3))
            "\n📈 Portail progressif (#70) : ton prochain déblocage aujourd'hui durera $nextMin min !"
        } else ""
        val needManualEntry = cfg.portalRequireCodeEntryEnabled && onCodeUnlockedAndExit != null
        revealCard.addView(TextView(context).apply {
            text = if (needManualEntry) {
                "Tu as survécu aux $portalMinLabel et aux $totalMinigames mini-jeux ! Retape exactement ce code à 6 chiffres ci-dessous pour déverrouiller :$nextEscNote"
            } else {
                "Tu as complété les $portalMinLabel, les pop-ups et les $totalMinigames mini-jeux ! Voici ton code d'accès unique (valable 2 min) :$nextEscNote"
            }
            setTextColor(Color.rgb(215, 215, 235)); textSize = 12.6f; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(14))
        })
        revealedCodeTv = TextView(context).apply {
            text = "------"
            setTextColor(Color.rgb(253, 224, 71)); textSize = 36f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat(); setColor(Color.rgb(28, 22, 48))
                setStroke(dp(2), Color.rgb(250, 204, 21))
            }
            setPadding(dp(24), dp(14), dp(24), dp(14))
        }
        revealCard.addView(revealedCodeTv)

        if (needManualEntry) {
            val et = EditText(context).apply {
                hint = "Tape les 6 chiffres…"
                inputType = InputType.TYPE_NULL // Driven by built-in reliable neon keypad (no IME bugs in A11Y overlay!)
                isFocusable = false
                setTextColor(Color.WHITE); setHintTextColor(Color.rgb(150, 145, 175))
                textSize = 22f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat(); setColor(Color.rgb(34, 26, 56))
                    setStroke(dp(2), Color.rgb(168, 85, 247))
                }
                setPadding(dp(14), dp(8), dp(14), dp(8))
            }
            codeInputEt = et
            revealCard.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

            val errTv = TextView(context).apply {
                text = ""; setTextColor(Color.rgb(252, 165, 165)); textSize = 11.6f; typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER; visibility = View.GONE
            }
            codeInputErrTv = errTv
            revealCard.addView(errTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

            // Built-in 100% reliable 0-9 neon keypad (works identically inside Accessibility Overlay on Android 8-16!)
            val padRows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "✕"))
            val padBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
            for (rKeys in padRows) {
                val rLin = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                for (k in rKeys) {
                    val kb = Button(context).apply {
                        text = k; isAllCaps = false; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                        background = GradientDrawable().apply {
                            cornerRadius = dp(10).toFloat()
                            setColor(if (k == "⌫" || k == "✕") Color.rgb(58, 24, 48) else Color.rgb(38, 30, 62))
                            setStroke(dp(1), Color.argb(120, 216, 180, 254))
                        }
                        minHeight = 0; minimumHeight = 0; setPadding(dp(4), dp(10), dp(4), dp(10))
                        setOnClickListener {
                            val cur = et.text?.toString().orEmpty()
                            when (k) {
                                "⌫" -> if (cur.isNotEmpty()) et.setText(cur.dropLast(1))
                                "✕" -> et.setText("")
                                else -> if (cur.length < 6) et.setText(cur + k)
                            }
                            errTv.visibility = View.GONE
                        }
                    }
                    rLin.addView(kb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), dp(3), dp(4), dp(3)) })
                }
                padBox.addView(rLin, LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            revealCard.addView(padBox)
        }

        revealCard.addView(Button(context).apply {
            text = if (onCodeUnlockedAndExit != null) "🔓 DÉVERROUILLER MAINTENANT" else "✅ FERMER"
            isAllCaps = false; textSize = 14.2f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.rgb(16, 185, 129)) }
            setPadding(dp(20), dp(14), dp(20), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
            setOnClickListener {
                if (needManualEntry) {
                    val typed = codeInputEt?.text?.toString()?.replace(" ", "")?.trim().orEmpty()
                    if (typed != issuedRawCode) {
                        codeInputErrTv?.text = "❌ Code incorrect ! Retape exactement les 6 chiffres affichés !"
                        codeInputErrTv?.visibility = View.VISIBLE
                        vibrateMs(160L)
                        if (cfg.pavlovSoundEnabled) PavlovSoundEngine.playPunishmentTone()
                        return@setOnClickListener
                    }
                }
                val c = issuedRawCode.ifBlank { revealedCodeTv.text?.toString()?.replace(" ", "").orEmpty() }
                stopAndRelease()
                if (onCodeUnlockedAndExit != null) onCodeUnlockedAndExit.invoke(c) else onCancelWait()
            }
        })
        addView(revealCard, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun updatePillStyle(sprint: Boolean, gazeFrozen: Boolean) {
        pillBox.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            when {
                gazeFrozen -> { setColor(Color.argb(235, 90, 8, 24)); setStroke(dp(2), Color.rgb(251, 113, 133)) }
                sprint -> { setColor(Color.argb(235, 120, 12, 34)); setStroke(dp(2), Color.rgb(250, 204, 21)) }
                else -> { setColor(Color.argb(222, 22, 8, 30)); setStroke(dp(2), Color.rgb(225, 29, 72)) }
            }
        }
    }

    fun startThreeMinuteSpamWait() {
        if (running) return
        running = true
        flashCounter = 0; lastBmpIdx = -1
        remainingWaitMs = totalWaitMs
        lastTickElapsedMs = SystemClock.elapsedRealtime()
        nextPopupAtRemainingMs = (totalWaitMs - 14_000L).coerceAtLeast(14_000L)
        inOverloadSprint = false; sprintMistakesInFinalGame = 0
        portalAwayMs = 0L; portalAwayPenaltyApplied = false
        minigamesSolvedCount = 0; popupsClearedCount = 0; flawlessComboStreak = 0

        buildDynamicMinigameSchedule()

        Thread {
            val loaded = VideoFeedRepository.loadPopupModeBitmapPool(context.applicationContext, cfg, maxPoolSize = 40)
            mainHandler.post {
                if (!running) {
                    for (b in loaded) if (!b.isRecycled) b.recycle()
                    return@post
                }
                bitmapPool.addAll(loaded)
            }
        }.start()

        mainHandler.post(spamTick)
    }

    /** Schedules 3..12 distinct visual mini-games evenly across the 3-6 min countdown from our 18-game pool! */
    private fun buildDynamicMinigameSchedule() {
        schedule.clear()
        if (totalMinigames <= 0) return

        val pool = mutableListOf(
            GameType.TARGET_BLITZ,
            GameType.PHOTO_PIN,
            GameType.ZOOM_DETAIL,
            GameType.DUAL_COMPARE,
            GameType.SCRATCH_REVEAL,
            GameType.MAHJONG_CHAIN,
            GameType.ALTERED_COPY,
            GameType.SYMBOL_MATH,
            GameType.PRECISION_GAUGE,
            GameType.STRIP_SNAP,
            GameType.MEMORY,
            GameType.SEQUENCE,
            GameType.INTRUDER,
            GameType.SWAP,
            GameType.ROTATE,
            if (cfg.hellPuzzle4x4Enabled) GameType.SLIDING_4X4_HELL else GameType.SLIDING_3X3
        )
        if (cfg.flashHunt9FpsEnabled) pool.add(GameType.FLASH_HUNT_9FPS)

        val chosen = ArrayList<GameType>(totalMinigames)
        if (cfg.flashHunt9FpsEnabled) {
            chosen.add(GameType.FLASH_HUNT_9FPS)
            pool.remove(GameType.FLASH_HUNT_9FPS)
        }
        pool.shuffle(Random)
        while (chosen.size < totalMinigames && pool.isNotEmpty()) {
            chosen.add(pool.removeAt(0))
        }
        chosen.shuffle(Random)

        val startMs = (totalWaitMs - 14_000L).coerceAtLeast(30_000L)
        val endMs = 16_000L
        val span = (startMs - endMs).coerceAtLeast(10_000L)
        for (i in chosen.indices) {
            val frac = if (chosen.size == 1) 0.5 else i.toDouble() / (chosen.size - 1).toDouble()
            val trigMs = (startMs - (span * frac).toLong()).coerceIn(endMs, startMs)
            schedule.add(ScheduledChallenge(chosen[i], trigMs))
        }
    }

    private fun pickSafeSourceBitmap(): Bitmap? {
        val valid = bitmapPool.filter { !it.isRecycled }
        return if (valid.isNotEmpty()) valid[Random.nextInt(valid.size)] else null
    }

    private fun launchMiniGame(ch: ScheduledChallenge, stageNumber: Int, isFinalSprintGame: Boolean) {
        clearMinigameViews()
        minigameActive = true
        currentGameHadMistake = false
        sprintMistakesInFinalGame = 0
        vibrateMs(95L)

        val onGameSolved: () -> Unit = {
            minigamesSolvedCount++
            if (!currentGameHadMistake) flawlessComboStreak++ else flawlessComboStreak = 0
            vibrateMs(125L)
            if (cfg.pavlovSoundEnabled) PavlovSoundEngine.playRewardChime()
            clearMinigameViews()
            minigameActive = false
            val comboNote = when {
                flawlessComboStreak >= 3 -> " 🔥 ON FIRE ×$flawlessComboStreak !"
                flawlessComboStreak == 2 -> " ⚡ COMBO ×2 !"
                else -> ""
            }
            showTransientAlert("✅ Défi #$stageNumber/$totalMinigames réussi !$comboNote", isError = false)
            if (pendingTrapPopupAfterGame && cfg.unlockPopupsEnabled) {
                pendingTrapPopupAfterGame = false
                mainHandler.postDelayed({ if (running && !minigameActive && !popupActive) showInteractivePopup() }, 900L)
            }
        }
        val onGamePenalty: (Int) -> Unit = { rawSec ->
            currentGameHadMistake = true
            totalMistakesInPortal++
            val baseSec = if (cfg.portalHardcorePenaltiesEnabled) rawSec * 2 else rawSec
            if (cfg.portalHardcorePenaltiesEnabled && totalMistakesInPortal % 2 == 0) {
                pendingTrapPopupAfterGame = true
            }
            val extra = if (flawlessComboStreak >= 2) { flawlessComboStreak = 0; 5 } else 0
            val totalPen = baseSec + extra
            val hcTag = if (cfg.portalHardcorePenaltiesEnabled) " [HARDCORE ×2]" else ""
            val tag = if (extra > 0) "⚠️ Erreur + Combo brisé$hcTag : +$totalPen s !" else "⚠️ Erreur / Aide$hcTag : +$totalPen s !"
            addPenaltySeconds(totalPen, tag)
        }
        val onGameMistake: () -> Unit = {
            currentGameHadMistake = true
            if (isFinalSprintGame && cfg.overloadSprintEnabled) {
                sprintMistakesInFinalGame++
                if (sprintMistakesInFinalGame >= 3) {
                    sprintMistakesInFinalGame = 0
                    clearMinigameViews()
                    minigameActive = false
                    ch.triggered = false
                    kickBackToOneMinute("🔥 3 erreurs en SURCHARGE ! Renvoi à 01:00 !")
                }
            }
        }

        val view: View = when (ch.type) {
            GameType.SLIDING_3X3 -> {
                showTransientAlert("🧩 DÉFI #$stageNumber/$totalMinigames : Taquin 3×3 !", false)
                SlidingPuzzleView(context, pickSafeSourceBitmap(), gridSize = 3, onPenaltySec = onGamePenalty, onSolved = onGameSolved).also { v -> activeMinigameRelease = { v.release() } }
            }
            GameType.SLIDING_4X4_HELL -> {
                showTransientAlert("🔥 DÉFI #$stageNumber/$totalMinigames : Taquin 4×4 Enfer !", false)
                SlidingPuzzleView(context, pickSafeSourceBitmap(), gridSize = 4, onPenaltySec = onGamePenalty, onSolved = onGameSolved).also { v -> activeMinigameRelease = { v.release() } }
            }
            GameType.SWAP -> {
                showTransientAlert("🖼️ DÉFI #$stageNumber/$totalMinigames : Échange 3×3 !", false)
                ImageReconstructionView(context, pickSafeSourceBitmap(), ImageReconstructionView.Mode.SWAP_TILES, onGamePenalty, onGameSolved).also { v -> activeMinigameRelease = { v.release() } }
            }
            GameType.ROTATE -> {
                showTransientAlert("🔄 DÉFI #$stageNumber/$totalMinigames : Rotation 3×3 !", false)
                ImageReconstructionView(context, pickSafeSourceBitmap(), ImageReconstructionView.Mode.ROTATE_TILES, onGamePenalty, onGameSolved).also { v -> activeMinigameRelease = { v.release() } }
            }
            GameType.MEMORY -> {
                showTransientAlert("🧠 DÉFI #$stageNumber/$totalMinigames : Memory Flash 4×3 !", false)
                MemoryFlashView(context, bitmapPool, onGamePenalty, onGameMistake, onGameSolved).also { v -> activeMinigameRelease = { v.release() } }
            }
            GameType.SEQUENCE -> {
                showTransientAlert("🎯 DÉFI #$stageNumber/$totalMinigames : Séquence Fuyante 1→9 !", false)
                MovingSequenceView(context, pickSafeSourceBitmap(), onGamePenalty, onGameMistake, onGameSolved).also { v -> activeMinigameRelease = { v.release() } }
            }
            GameType.INTRUDER -> {
                showTransientAlert("🔍 DÉFI #$stageNumber/$totalMinigames : Trouve l'Intrus !", false)
                SpotIntruderView(context, bitmapPool, onGamePenalty, onGameMistake, onGameSolved).also { v -> activeMinigameRelease = { v.release() } }
            }
            GameType.FLASH_HUNT_9FPS -> {
                showTransientAlert("⚡ DÉFI #$stageNumber/$totalMinigames : Chasse Éclair 9 img/s !", false)
                FlashTargetHuntView(context, bitmapPool, onGamePenalty, onGameMistake, onGameSolved).also { v -> activeMinigameRelease = { v.release() } }
            }
            GameType.SCRATCH_REVEAL -> {
                showTransientAlert("🪙 DÉFI #$stageNumber/$totalMinigames : Gratte & Identifie !", false)
                ScratchRevealMatchView(context, bitmapPool, onGamePenalty, onGameMistake, onGameSolved).also { v -> activeMinigameRelease = { v.release() } }
            }
            GameType.MAHJONG_CHAIN -> {
                showTransientAlert("🔗 DÉFI #$stageNumber/$totalMinigames : Chaîne Mahjong 4×4 !", false)
                MosaicPairChainView(context, bitmapPool, onGamePenalty, onGameMistake, onGameSolved).also { v -> activeMinigameRelease = { v.release() } }
            }
            GameType.ALTERED_COPY -> {
                showTransientAlert("🎨 DÉFI #$stageNumber/$totalMinigames : Cherche la Copie Altérée !", false)
                ColorTintOddOneView(context, bitmapPool, onGamePenalty, onGameMistake, onGameSolved).also { v -> activeMinigameRelease = { v.release() } }
            }
            GameType.SYMBOL_MATH -> {
                showTransientAlert("🔢 DÉFI #$stageNumber/$totalMinigames : Réflexe Symboles !", false)
                RapidMathImageGridView(context, bitmapPool, onGamePenalty, onGameMistake, onGameSolved).also { v -> activeMinigameRelease = { v.release() } }
            }
            GameType.PRECISION_GAUGE -> {
                showTransientAlert("⏱️ DÉFI #$stageNumber/$totalMinigames : Verrous de Précision !", false)
                PrecisionGaugeStopView(context, pickSafeSourceBitmap(), onGamePenalty, onGameMistake, onGameSolved).also { v -> activeMinigameRelease = { v.release() } }
            }
            GameType.STRIP_SNAP -> {
                showTransientAlert("🎞️ DÉFI #$stageNumber/$totalMinigames : Réalignement Bandes !", false)
                JigsawEdgeSnapView(context, bitmapPool, onGamePenalty, onGameSolved).also { v -> activeMinigameRelease = { v.release() } }
            }
            GameType.TARGET_BLITZ -> {
                showTransientAlert("🎯 DÉFI #$stageNumber/$totalMinigames : Cibles Mouvantes !", false)
                TargetTapBlitzView(context, bitmapPool, onMistake = { onGamePenalty(5); onGameMistake() }, onSolved = onGameSolved).also { v -> activeMinigameRelease = { v.release() } }
            }
            GameType.PHOTO_PIN -> {
                showTransientAlert("🔐 DÉFI #$stageNumber/$totalMinigames : Code Visuel de Photos !", false)
                PhotoPinRecallView(context, bitmapPool, onMistake = { onGamePenalty(5); onGameMistake() }, onSolved = onGameSolved).also { v -> activeMinigameRelease = { v.release() } }
            }
            GameType.ZOOM_DETAIL -> {
                showTransientAlert("🔍 DÉFI #$stageNumber/$totalMinigames : Loupe Micro-Détail 3× !", false)
                ZoomDetailMatchView(context, bitmapPool, onMistake = { onGamePenalty(5); onGameMistake() }, onSolved = onGameSolved).also { v -> activeMinigameRelease = { v.release() } }
            }
            GameType.DUAL_COMPARE -> {
                showTransientAlert("⚖️ DÉFI #$stageNumber/$totalMinigames : Photo Jumelle ou Piège !", false)
                DualCompareDiffView(context, bitmapPool, onMistake = { onGamePenalty(4); onGameMistake() }, onSolved = onGameSolved).also { v -> activeMinigameRelease = { v.release() } }
            }
        }

        minigameLayer.addView(view, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        minigameLayer.visibility = View.VISIBLE
    }

    private fun clearMinigameViews() {
        try { activeMinigameRelease?.invoke() } catch (_: Throwable) {}
        activeMinigameRelease = null
        minigameLayer.removeAllViews()
        minigameLayer.visibility = View.GONE
    }

    private fun kickBackToOneMinute(reason: String) {
        remainingWaitMs = maxOf(remainingWaitMs, 60_000L)
        inOverloadSprint = false
        onOverloadSprintChanged?.invoke(false)
        updatePillStyle(sprint = false, gazeFrozen = false)
        vibrateMs(220L)
        if (cfg.pavlovSoundEnabled) PavlovSoundEngine.playPunishmentTone()
        showTransientAlert(reason, isError = true)
    }

    private fun showInteractivePopup() {
        dismissCurrentPopup()
        popupActive = true
        popupDurationMs = if (inOverloadSprint) 6_000L else 8_500L
        popupExpireElapsedMs = SystemClock.elapsedRealtime() + popupDurationMs
        vibrateMs(55L)

        val kind = Random.nextInt(3)
        val gravityPick = listOf(Gravity.CENTER, Gravity.CENTER_HORIZONTAL or Gravity.TOP, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM)[Random.nextInt(3)]
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat(); setColor(Color.argb(240, 22, 12, 36))
                setStroke(dp(2), if (inOverloadSprint) Color.rgb(250, 204, 21) else Color.rgb(236, 72, 153))
            }
        }
        val pBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = 1000
            progressTintList = ColorStateList.valueOf(if (inOverloadSprint) Color.rgb(244, 63, 94) else Color.rgb(250, 204, 21))
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(80, 255, 255, 255))
        }
        popupProgressBar = pBar
        card.addView(pBar, LinearLayout.LayoutParams(dp(220), dp(5)).apply { bottomMargin = dp(8) })
        pickSafeSourceBitmap()?.let { thumb ->
            card.addView(ImageView(context).apply { setImageBitmap(thumb); scaleType = ImageView.ScaleType.CENTER_CROP },
                LinearLayout.LayoutParams(dp(116), dp(80)).apply { bottomMargin = dp(8) })
        }

        when (kind) {
            0 -> {
                card.addView(TextView(context).apply {
                    text = if (inOverloadSprint) "🔥 POP-UP SURCHARGE !" else "⚡ POP-UP PRÉSENCE"
                    setTextColor(Color.rgb(249, 168, 212)); textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                })
                card.addView(TextView(context).apply {
                    text = if (inOverloadSprint) "Clique vite ! Timeout = RENVOI À 01:00 !" else "Confirme ta présence (sinon +10 s) !"
                    setTextColor(Color.WHITE); textSize = 11.6f; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(10))
                })
                var tapsLeft = 2
                val btn = Button(context).apply {
                    text = "👁️ JE REGARDE (2 clics)"; isAllCaps = false; textSize = 12.2f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.rgb(219, 39, 119)) }
                    setPadding(dp(14), dp(9), dp(14), dp(9))
                }
                btn.setOnClickListener {
                    tapsLeft--
                    if (tapsLeft > 0) { btn.text = "⚡ ENCORE 1 CLIC !"; btn.translationX = (if (Random.nextBoolean()) 1 else -1) * dp(28).toFloat() }
                    else { popupsClearedCount++; dismissCurrentPopup() }
                }
                card.addView(btn)
            }
            1 -> {
                card.addView(TextView(context).apply {
                    text = "🎭 POP-UP PIÈGE"; setTextColor(Color.rgb(253, 224, 71)); textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                })
                card.addView(TextView(context).apply {
                    text = "Choisis le BON bouton pour continuer :"; setTextColor(Color.WHITE); textSize = 11.6f; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(10))
                })
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                val goodBtn = Button(context).apply {
                    text = "✅ Poursuivre"; isAllCaps = false; textSize = 11.8f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.rgb(79, 70, 229)) }
                    setPadding(dp(12), dp(9), dp(12), dp(9)); setOnClickListener { popupsClearedCount++; dismissCurrentPopup() }
                }
                val trapBtn = Button(context).apply {
                    text = "⚡ Quitter (+8 s)"; isAllCaps = false; textSize = 11.8f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.rgb(16, 185, 129)) }
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    setOnClickListener {
                        dismissCurrentPopup()
                        if (inOverloadSprint) kickBackToOneMinute("😈 Piège en SURCHARGE ! Renvoi à 01:00 !")
                        else addPenaltySeconds(8, "😈 Piège déclenché ! +8 s ajoutées !")
                    }
                }
                val lpA = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
                val lpB = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) }
                if (Random.nextBoolean()) { row.addView(goodBtn, lpA); row.addView(trapBtn, lpB) } else { row.addView(trapBtn, lpA); row.addView(goodBtn, lpB) }
                card.addView(row, LinearLayout.LayoutParams(dp(245), ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            else -> {
                card.addView(TextView(context).apply {
                    text = "✨ ${cfg.mantraList().random()}"; setTextColor(Color.rgb(216, 180, 254)); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                })
                card.addView(TextView(context).apply {
                    text = "Touche 3 fois rapidement pour dissiper :"; setTextColor(Color.WHITE); textSize = 11.4f; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(10))
                })
                var remTaps = 3
                val btn = Button(context).apply {
                    text = "🔓 DISSIPER (3)"; isAllCaps = false; textSize = 12.2f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.rgb(147, 51, 234)) }
                    setPadding(dp(16), dp(9), dp(16), dp(9))
                }
                btn.setOnClickListener { remTaps--; if (remTaps > 0) btn.text = "🔓 DISSIPER ($remTaps)" else { popupsClearedCount++; dismissCurrentPopup() } }
                card.addView(btn)
            }
        }

        popupLayer.addView(card, LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT, gravityPick).apply { topMargin = dp(148); bottomMargin = dp(95) })
        popupLayer.visibility = View.VISIBLE
    }

    private fun dismissCurrentPopup() {
        popupActive = false; popupProgressBar = null
        popupLayer.removeAllViews(); popupLayer.visibility = View.GONE
    }

    private fun addPenaltySeconds(sec: Int, msg: String) {
        remainingWaitMs = (remainingWaitMs + sec * 1000L).coerceAtMost(totalWaitMs + 240_000L)
        vibrateMs(140L)
        if (cfg.pavlovSoundEnabled) PavlovSoundEngine.playPunishmentTone()
        showTransientAlert(msg, isError = true)
    }

    private fun showTransientAlert(msg: String, isError: Boolean) {
        flashAlertTv.text = msg
        flashAlertTv.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(if (isError) Color.argb(238, 190, 18, 60) else Color.argb(232, 16, 128, 96))
        }
        flashAlertTv.visibility = View.VISIBLE
        alertUntilElapsedMs = SystemClock.elapsedRealtime() + 3_200L
    }

    private fun finishWaitAndRevealCode() {
        running = false
        mainHandler.removeCallbacks(spamTick)
        onOverloadSprintChanged?.invoke(false)
        dismissCurrentPopup()
        clearMinigameViews()
        onRecordDailyPortalCompletion?.invoke()
        val freshCode = onIssueCode()
        issuedRawCode = freshCode
        val pretty = if (freshCode.length == 6) "${freshCode.substring(0, 3)} ${freshCode.substring(3)}" else freshCode
        revealedCodeTv.text = pretty
        codeInputEt?.setText("")
        codeInputErrTv?.visibility = View.GONE
        actionBtn.visibility = View.GONE
        revealCard.visibility = View.VISIBLE
        vibrateMs(150L)
        if (cfg.pavlovSoundEnabled) PavlovSoundEngine.playRewardChime()
    }

    @Suppress("DEPRECATION")
    private fun vibrateMs(ms: Long) {
        try {
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            else v.vibrate(ms)
        } catch (_: Throwable) {}
    }

    fun stopAndRelease() {
        running = false
        mainHandler.removeCallbacks(spamTick)
        onOverloadSprintChanged?.invoke(false)
        dismissCurrentPopup()
        clearMinigameViews()
        imageView.setImageDrawable(null)
        for (b in bitmapPool) { try { if (!b.isRecycled) b.recycle() } catch (_: Throwable) {} }
        bitmapPool.clear()
    }
}
