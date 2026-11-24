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
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.gazelock.app.config.GazeConfig
import com.gazelock.app.config.GazeConfigSnapshot
import com.gazelock.app.media.VideoFeedRepository
import com.gazelock.app.overlay.minigames.ImageReconstructionView
import com.gazelock.app.overlay.minigames.MemoryFlashView
import com.gazelock.app.overlay.minigames.MovingSequenceView
import com.gazelock.app.overlay.minigames.SlidingPuzzleView
import com.gazelock.app.overlay.minigames.SpotIntruderView
import kotlin.random.Random

/**
 * Fullscreen Interactive 3-Minute (180s) Imposed Overlay Unlock Portal (v4.0 ULTRA):
 * - Continuous 0.1s (10 FPS) folder image spam -> accelerates to 0.05s (20 FPS!) during #17 Overload Sprint (00:45->00:00)!
 * - #22 Gaze-Gated Portal: when live camera gaze is attached, looking away freezes the 3-min timer (+5s after 2.2s)!
 * - Timed interactive Pop-Ups (2x faster in Sprint! Timeout in Sprint resets countdown to 01:00!).
 * - #16 6-Game Visual Pool (NO dictation!): Sliding Puzzle 3x3, Swap Reconstruction 3x3, Rotate Alignment 3x3,
 *   Memory Flash 4x3, Moving Sequence 1->9, and Spot the Intruder (3 rounds) — 3 distinct games drawn per run!
 */
class AccessCodeWaitSpamView(
    context: Context,
    private val cfg: GazeConfigSnapshot,
    private val gazeLookingSupplier: (() -> Boolean)? = null,
    private val onOverloadSprintChanged: ((Boolean) -> Unit)? = null,
    private val onIssueCode: () -> String,
    private val onCodeUnlockedAndExit: ((String) -> Unit)? = null,
    private val onCancelWait: () -> Unit
) : FrameLayout(context) {

    private enum class GameType { SLIDING, SWAP, ROTATE, MEMORY, SEQUENCE, INTRUDER }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

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

    private val bitmapPool = ArrayList<Bitmap>()
    private var running = false
    private var remainingWaitMs = GazeConfig.ACCESS_CODE_WAIT_SEC * 1000L
    private val totalWaitMs = GazeConfig.ACCESS_CODE_WAIT_SEC * 1000L
    private var flashCounter = 0
    private var lastBmpIdx = -1
    private var lastTickElapsedMs = 0L

    // #17 Overload Sprint State
    private var inOverloadSprint = false
    private var sprintMistakesInFinalGame = 0

    // #22 Gaze-Gate State
    private var portalAwayMs = 0L
    private var portalAwayPenaltyApplied = false

    // 6-Game Pool State
    private val scheduledGames = ArrayList<GameType>(3)
    private var activeMinigameRelease: (() -> Unit)? = null
    private var minigameActive = false
    private var triggeredStage1 = false
    private var triggeredStage2 = false
    private var triggeredStage3 = false
    private var minigamesSolvedCount = 0
    private val totalMinigames = if (cfg.unlockMinigamesEnabled) 3 else 0

    // Interactive Pop-ups State
    private var popupActive = false
    private var popupExpireElapsedMs = 0L
    private var popupDurationMs = 8_500L
    private var popupProgressBar: ProgressBar? = null
    private var nextPopupAtRemainingMs = 165_000L
    private var popupsClearedCount = 0

    private var alertUntilElapsedMs = 0L

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (running && event != null &&
            (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_APP_SWITCH)
        ) {
            showTransientAlert("🔒 Annule en bas si tu veux quitter (remet les 3 min à zéro) !", isError = true)
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

            // #22 Gaze-Gated 3-Min Portal Check!
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

            // 1) Advance 3-minute countdown ONLY when looking at screen AND no mandatory mini-game is blocking!
            if (!minigameActive && gazeOk) {
                remainingWaitMs = (remainingWaitMs - dt).coerceAtLeast(0L)
            }

            // 2) #17 Final 45-Second Overload Sprint Toggle (00:45 -> 00:00)
            val shouldSprint = cfg.overloadSprintEnabled && remainingWaitMs <= 45_000L && remainingWaitMs > 0L
            if (shouldSprint != inOverloadSprint) {
                inOverloadSprint = shouldSprint
                onOverloadSprintChanged?.invoke(shouldSprint)
                updatePillStyle(sprint = shouldSprint, gazeFrozen = !gazeOk)
                if (shouldSprint) {
                    vibrateMs(160L)
                    showTransientAlert("🔥 SPRINT SURCHARGE ! Spam 20 FPS (0,05 s) + Échec = Retour 01:00 !", isError = true)
                    nextPopupAtRemainingMs = (remainingWaitMs - 4_500L).coerceAtLeast(6_000L)
                }
            } else if (!gazeOk) {
                updatePillStyle(sprint = inOverloadSprint, gazeFrozen = true)
            }

            // 3) Update HUD Timer & Progress
            val elapsedMs = (totalWaitMs - remainingWaitMs).coerceIn(0L, totalWaitMs)
            progressBar.progress = ((elapsedMs * 1000L) / totalWaitMs.coerceAtLeast(1L)).toInt()
            val remSec = ((remainingWaitMs + 999L) / 1000L).toInt()
            countdownTv.text = when {
                !gazeOk -> String.format("👁️ REGARDE L'ÉCRAN ! %02d:%02d", remSec / 60, remSec % 60)
                minigameActive -> String.format("⏸️ DÉFI REQUIS : %02d:%02d", remSec / 60, remSec % 60)
                inOverloadSprint -> String.format("🔥 SURCHARGE 20 FPS : %02d:%02d", remSec / 60, remSec % 60)
                else -> String.format("⏳ PORTAIL 3 MIN : %02d:%02d", remSec / 60, remSec % 60)
            }

            // 4) Rapid Folder Image-Spam (0.1s normal -> 0.05s / 20 FPS in Overload Sprint!)
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
                val rateStr = if (inOverloadSprint) "0,05 s (20 FPS)" else "0,1 s"
                val gameTag = if (totalMinigames > 0) " · 🧩 $minigamesSolvedCount/$totalMinigames" else ""
                val popTag = if (cfg.unlockPopupsEnabled) " · 🔔 $popupsClearedCount" else ""
                subTv.text = "🖼️ Spam $rateStr (#$flashCounter)$gameTag$popTag"
            } else {
                setBackgroundColor(if (flashCounter++ % 2 == 0) Color.rgb(36, 8, 28) else Color.rgb(16, 8, 34))
                subTv.text = "⏳ Chargement des images du dossier… Attente obligatoire 3 min"
            }

            // 5) Alert auto-hide
            if (flashAlertTv.visibility == View.VISIBLE && now >= alertUntilElapsedMs) {
                flashAlertTv.visibility = View.GONE
            }

            // 6) Active Pop-Up Timeout
            if (popupActive) {
                val pRem = (popupExpireElapsedMs - now).coerceAtLeast(0L)
                popupProgressBar?.progress = ((pRem * 1000L) / popupDurationMs.coerceAtLeast(1L)).toInt()
                if (pRem <= 0L) {
                    dismissCurrentPopup()
                    if (inOverloadSprint) {
                        kickBackToOneMinute("🔥 Pop-up raté en SURCHARGE ! Chrono renvoyé à 01:00 !")
                    } else {
                        addPenaltySeconds(10, "⏰ Pop-up ignoré ! +10 s ajoutées aux 3 min !")
                    }
                }
            }

            // 7) Scheduled Mandatory Mini-Games (from 6-game visual pool!)
            if (cfg.unlockMinigamesEnabled && !minigameActive && gazeOk) {
                when {
                    !triggeredStage1 && remainingWaitMs <= 145_000L && scheduledGames.size > 0 -> {
                        triggeredStage1 = true
                        dismissCurrentPopup()
                        launchMiniGame(scheduledGames[0], stageNumber = 1, isFinalSprintGame = false)
                    }
                    !triggeredStage2 && remainingWaitMs <= 82_000L && scheduledGames.size > 1 -> {
                        triggeredStage2 = true
                        dismissCurrentPopup()
                        launchMiniGame(scheduledGames[1], stageNumber = 2, isFinalSprintGame = false)
                    }
                    !triggeredStage3 && remainingWaitMs <= 34_000L && scheduledGames.size > 2 -> {
                        triggeredStage3 = true
                        dismissCurrentPopup()
                        launchMiniGame(scheduledGames[2], stageNumber = 3, isFinalSprintGame = inOverloadSprint)
                    }
                }
            }

            // 8) Periodic Interactive Pop-Ups (2x faster in Overload Sprint!)
            if (cfg.unlockPopupsEnabled && !minigameActive && !popupActive && gazeOk &&
                remainingWaitMs in 7_500L..nextPopupAtRemainingMs
            ) {
                val gap = if (inOverloadSprint) Random.nextLong(7_000L, 11_000L) else Random.nextLong(15_000L, 22_000L)
                nextPopupAtRemainingMs = (remainingWaitMs - gap).coerceAtLeast(0L)
                showInteractivePopup()
            }

            // 9) Completion!
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
            setPadding(dp(14), dp(36), dp(14), dp(8))
        }
        pillBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        updatePillStyle(sprint = false, gazeFrozen = false)

        countdownTv = TextView(context).apply {
            text = "⏳ PORTAIL 3 MIN : 03:00"
            setTextColor(Color.WHITE); textSize = 18f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
        }
        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = 0
            progressTintList = ColorStateList.valueOf(Color.rgb(225, 29, 72))
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(90, 255, 255, 255))
        }
        subTv = TextView(context).apply {
            text = "⏳ Préparation du spam d'images (0,1 s) & mini-jeux…"
            setTextColor(Color.rgb(253, 224, 71)); textSize = 11f; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0)
        }
        pillBox.addView(countdownTv)
        pillBox.addView(progressBar, LinearLayout.LayoutParams(dp(240), dp(6)).apply { topMargin = dp(5) })
        pillBox.addView(subTv)
        topBox.addView(pillBox)

        flashAlertTv = TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            visibility = View.GONE
        }
        topBox.addView(flashAlertTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        addView(topBox, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        popupLayer = FrameLayout(context).apply { visibility = View.GONE }
        addView(popupLayer, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        minigameLayer = FrameLayout(context).apply {
            visibility = View.GONE
            setPadding(dp(14), dp(112), dp(14), dp(78))
        }
        addView(minigameLayer, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        actionBtn = Button(context).apply {
            text = "↩ Annuler le portail (remet les 3 min à zéro)"
            isAllCaps = false; textSize = 11.6f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(Color.argb(215, 42, 34, 62))
                setStroke(dp(1), Color.argb(120, 255, 255, 255))
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener {
                stopAndRelease()
                onCancelWait()
            }
        }
        addView(
            actionBtn,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(24)
            }
        )

        revealCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(246, 10, 8, 22))
            setPadding(dp(26), dp(26), dp(26), dp(26))
            visibility = View.GONE
        }
        revealCard.addView(TextView(context).apply {
            text = "🔓 CRÉNEAU DE 3 MIN ACCOMPLI !"
            setTextColor(Color.rgb(134, 239, 172)); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        revealCard.addView(TextView(context).apply {
            text = "Tu as survécu au spam 0,1 s/20 FPS, aux pop-ups et aux 3 mini-jeux. Voici ton code d'accès unique (valable 2 min) :"
            setTextColor(Color.rgb(215, 215, 235)); textSize = 13f; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(14))
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
        revealCard.addView(Button(context).apply {
            text = if (onCodeUnlockedAndExit != null) "🔓 RETIRER LES VIDÉOS MAINTENANT" else "✅ FERMER"
            isAllCaps = false; textSize = 14.2f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.rgb(16, 185, 129)) }
            setPadding(dp(20), dp(14), dp(20), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) }
            setOnClickListener {
                val c = revealedCodeTv.text?.toString()?.replace(" ", "").orEmpty()
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
        remainingWaitMs = GazeConfig.ACCESS_CODE_WAIT_SEC * 1000L
        lastTickElapsedMs = SystemClock.elapsedRealtime()
        nextPopupAtRemainingMs = 165_000L
        inOverloadSprint = false; sprintMistakesInFinalGame = 0
        portalAwayMs = 0L; portalAwayPenaltyApplied = false
        triggeredStage1 = false; triggeredStage2 = false; triggeredStage3 = false
        minigamesSolvedCount = 0; popupsClearedCount = 0

        // Pick 3 distinct visual mini-games: 1 classic puzzle/reconstruction + 2 new v4.0 intense games!
        scheduledGames.clear()
        val classic = listOf(GameType.SLIDING, GameType.SWAP, GameType.ROTATE).shuffled(Random).first()
        val intense = listOf(GameType.MEMORY, GameType.SEQUENCE, GameType.INTRUDER).shuffled(Random).take(2)
        scheduledGames.addAll((listOf(classic) + intense).shuffled(Random))

        Thread {
            val loaded = VideoFeedRepository.loadSpamBitmapPool(context.applicationContext, cfg, maxPoolSize = 36)
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

    private fun pickSafeSourceBitmap(): Bitmap? {
        val valid = bitmapPool.filter { !it.isRecycled }
        return if (valid.isNotEmpty()) valid[Random.nextInt(valid.size)] else null
    }

    private fun launchMiniGame(type: GameType, stageNumber: Int, isFinalSprintGame: Boolean) {
        clearMinigameViews()
        minigameActive = true
        sprintMistakesInFinalGame = 0
        vibrateMs(95L)

        val onGameSolved: () -> Unit = {
            minigamesSolvedCount++
            vibrateMs(125L)
            clearMinigameViews()
            minigameActive = false
            showTransientAlert("✅ Défi #$stageNumber réussi ! Décompte repris !", isError = false)
        }
        val onGamePenalty: (Int) -> Unit = { sec ->
            addPenaltySeconds(sec, "⚠️ Erreur / Aide : +$sec s ajoutées !")
        }
        val onGameMistake: () -> Unit = {
            if (isFinalSprintGame && cfg.overloadSprintEnabled) {
                sprintMistakesInFinalGame++
                if (sprintMistakesInFinalGame >= 3) {
                    sprintMistakesInFinalGame = 0
                    clearMinigameViews()
                    minigameActive = false
                    triggeredStage3 = false // Must re-pass final challenge when reaching 34s again!
                    kickBackToOneMinute("🔥 3 erreurs en SURCHARGE ! Renvoi à 01:00 !")
                }
            }
        }

        val view: View = when (type) {
            GameType.SLIDING -> {
                showTransientAlert("🧩 DÉFI #$stageNumber : Puzzle à glissement 3×3 !", isError = false)
                SlidingPuzzleView(context, pickSafeSourceBitmap(), onGamePenalty, onGameSolved).also { v ->
                    activeMinigameRelease = { v.release() }
                }
            }
            GameType.SWAP -> {
                showTransientAlert("🖼️ DÉFI #$stageNumber : Reconstitution par échange 3×3 !", isError = false)
                ImageReconstructionView(context, pickSafeSourceBitmap(), ImageReconstructionView.Mode.SWAP_TILES, onGamePenalty, onGameSolved).also { v ->
                    activeMinigameRelease = { v.release() }
                }
            }
            GameType.ROTATE -> {
                showTransientAlert("🔄 DÉFI #$stageNumber : Redressement par rotation 3×3 !", isError = false)
                ImageReconstructionView(context, pickSafeSourceBitmap(), ImageReconstructionView.Mode.ROTATE_TILES, onGamePenalty, onGameSolved).also { v ->
                    activeMinigameRelease = { v.release() }
                }
            }
            GameType.MEMORY -> {
                showTransientAlert("🧠 DÉFI #$stageNumber : Memory Flash (6 paires) !", isError = false)
                MemoryFlashView(context, bitmapPool, onGamePenalty, onGameMistake, onGameSolved).also { v ->
                    activeMinigameRelease = { v.release() }
                }
            }
            GameType.SEQUENCE -> {
                showTransientAlert("🎯 DÉFI #$stageNumber : Séquence Fuyante 1 → 9 !", isError = false)
                MovingSequenceView(context, pickSafeSourceBitmap(), onGamePenalty, onGameMistake, onGameSolved).also { v ->
                    activeMinigameRelease = { v.release() }
                }
            }
            GameType.INTRUDER -> {
                showTransientAlert("🔍 DÉFI #$stageNumber : Trouve l'Intrus Éclair (3 manches) !", isError = false)
                SpotIntruderView(context, bitmapPool, onGamePenalty, onGameMistake, onGameSolved).also { v ->
                    activeMinigameRelease = { v.release() }
                }
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
        showTransientAlert(reason, isError = true)
    }

    // ==================== TIMED INTERACTIVE POP-UPS ====================
    private fun showInteractivePopup() {
        dismissCurrentPopup()
        popupActive = true
        popupDurationMs = if (inOverloadSprint) 6_000L else 8_500L
        popupExpireElapsedMs = SystemClock.elapsedRealtime() + popupDurationMs
        vibrateMs(55L)

        val kind = Random.nextInt(3)
        val gravityPick = listOf(
            Gravity.CENTER,
            Gravity.CENTER_HORIZONTAL or Gravity.TOP,
            Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        )[Random.nextInt(3)]

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(240, 22, 12, 36))
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

        val thumb = pickSafeSourceBitmap()
        if (thumb != null) {
            card.addView(ImageView(context).apply {
                setImageBitmap(thumb); scaleType = ImageView.ScaleType.CENTER_CROP
            }, LinearLayout.LayoutParams(dp(116), dp(80)).apply { bottomMargin = dp(8) })
        }

        when (kind) {
            0 -> {
                card.addView(TextView(context).apply {
                    text = if (inOverloadSprint) "🔥 POP-UP SURCHARGE !" else "⚡ POP-UP PRÉSENCE"
                    setTextColor(Color.rgb(249, 168, 212)); textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                })
                card.addView(TextView(context).apply {
                    text = if (inOverloadSprint) "Clique vite ! Timeout = RENVOI À 01:00 !" else "Confirme que tu regardes (sinon +10 s) !"
                    setTextColor(Color.WHITE); textSize = 11.6f; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(10))
                })
                var tapsLeft = 2
                val btn = Button(context).apply {
                    text = "👁️ JE REGARDE (2 clics)"
                    isAllCaps = false; textSize = 12.2f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.rgb(219, 39, 119)) }
                    setPadding(dp(14), dp(9), dp(14), dp(9))
                }
                btn.setOnClickListener {
                    tapsLeft--
                    if (tapsLeft > 0) {
                        btn.text = "⚡ ENCORE 1 CLIC !"
                        btn.translationX = (if (Random.nextBoolean()) 1 else -1) * dp(28).toFloat()
                    } else {
                        popupsClearedCount++; dismissCurrentPopup()
                    }
                }
                card.addView(btn)
            }
            1 -> {
                card.addView(TextView(context).apply {
                    text = "🎭 POP-UP PIÈGE"
                    setTextColor(Color.rgb(253, 224, 71)); textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                })
                card.addView(TextView(context).apply {
                    text = "Choisis le BON bouton pour poursuivre le décompte :"
                    setTextColor(Color.WHITE); textSize = 11.6f; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(10))
                })
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                val goodBtn = Button(context).apply {
                    text = "✅ Poursuivre"
                    isAllCaps = false; textSize = 11.8f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.rgb(79, 70, 229)) }
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    setOnClickListener { popupsClearedCount++; dismissCurrentPopup() }
                }
                val trapBtn = Button(context).apply {
                    text = "⚡ Quitter (+8 s)"
                    isAllCaps = false; textSize = 11.8f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.rgb(16, 185, 129)) }
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    setOnClickListener {
                        dismissCurrentPopup()
                        if (inOverloadSprint) kickBackToOneMinute("😈 Piège déclenché en SURCHARGE ! Renvoi à 01:00 !")
                        else addPenaltySeconds(8, "😈 Piège déclenché ! +8 s ajoutées !")
                    }
                }
                val lpA = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
                val lpB = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) }
                if (Random.nextBoolean()) { row.addView(goodBtn, lpA); row.addView(trapBtn, lpB) }
                else { row.addView(trapBtn, lpA); row.addView(goodBtn, lpB) }
                card.addView(row, LinearLayout.LayoutParams(dp(245), ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            else -> {
                val mantra = cfg.mantraList().random()
                card.addView(TextView(context).apply {
                    text = "✨ $mantra"
                    setTextColor(Color.rgb(216, 180, 254)); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                })
                card.addView(TextView(context).apply {
                    text = "Touche 3 fois rapidement pour dissiper :"
                    setTextColor(Color.WHITE); textSize = 11.4f; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(10))
                })
                var remTaps = 3
                val btn = Button(context).apply {
                    text = "🔓 DISSIPER (3)"
                    isAllCaps = false; textSize = 12.2f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.rgb(147, 51, 234)) }
                    setPadding(dp(16), dp(9), dp(16), dp(9))
                }
                btn.setOnClickListener {
                    remTaps--
                    if (remTaps > 0) btn.text = "🔓 DISSIPER ($remTaps)"
                    else { popupsClearedCount++; dismissCurrentPopup() }
                }
                card.addView(btn)
            }
        }

        val lp = LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT, gravityPick).apply {
            topMargin = dp(148); bottomMargin = dp(95)
        }
        popupLayer.addView(card, lp)
        popupLayer.visibility = View.VISIBLE
    }

    private fun dismissCurrentPopup() {
        popupActive = false; popupProgressBar = null
        popupLayer.removeAllViews(); popupLayer.visibility = View.GONE
    }

    private fun addPenaltySeconds(sec: Int, msg: String) {
        remainingWaitMs = (remainingWaitMs + sec * 1000L).coerceAtMost(totalWaitMs + 150_000L)
        vibrateMs(140L)
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
        val freshCode = onIssueCode()
        val pretty = if (freshCode.length == 6) "${freshCode.substring(0, 3)} ${freshCode.substring(3)}" else freshCode
        revealedCodeTv.text = pretty
        actionBtn.visibility = View.GONE
        revealCard.visibility = View.VISIBLE
        vibrateMs(150L)
    }

    @Suppress("DEPRECATION")
    private fun vibrateMs(ms: Long) {
        try {
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else v.vibrate(ms)
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
