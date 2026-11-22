package com.demonpet.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.demonpet.app.ai.WitchSpriteEngine
import com.demonpet.app.config.DemonConfigSnapshot
import com.demonpet.app.config.DemonSettings
import com.demonpet.app.mdm.DemonMdmManager
import com.demonpet.app.media.MediaFolderHelper
import kotlin.random.Random

/**
 * Fullscreen Monster Girl Quest-inspired Femdom Witch RPG Battle & Drain End-Scene Controller:
 * - #93/#95/#96 Fullscreen auto-segmented Witch sprites + Live2D-lite animations + magic circle
 * - #101 Triple Gauge (PV Volonté / PM Résistance / Emprise % / Niveau & Rang d'Obéissance)
 * - #102 3 Phases (Approche -> Capturé/Entravé -> Drainage Intense)
 * - #103 Fast QTE "Se débattre" (10 taps in 3.5s) to break Witch capture
 * - #104/#105/#106/#107 Status effects (Charmed, Hypnotized, Sealed), Guard vs Ultimate, RP dialogue choices
 * - #108 Duo Witch Reinforcement in Strict/Tyrant mode
 * - #113/#114/#115 Multi-step Cinematic Drain End-Scene + MGQ-style sarcastic post-defeat screen
 * - Real MDM Phone Damage by app usage order on defeat, fully restored on Victory/End Game or Code 100205!
 */
class WitchRpgBattleOverlay(
    private val context: Context,
    private val settings: DemonSettings,
    private val onMountFullscreen: (rootView: View) -> Unit,
    private val onDismissFullscreen: () -> Unit,
    private val onApplyVisualDrain: (drainPct: Int, cfg: DemonConfigSnapshot) -> Unit,
    private val onClearVisualDrain: () -> Unit,
    private val onSayPet: (msg: String) -> Unit,
    private val onSpawnTrappedAltar: (cfg: DemonConfigSnapshot) -> Unit,
    private val onVibrate: (ms: Long) -> Unit,
    private val onSecretEmergencyExit: () -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var activeWitch: WitchSpriteEngine.LoadedWitch? = null
    private var secondWitch: WitchSpriteEngine.LoadedWitch? = null
    private var typewriterRunnable: Runnable? = null
    private var qteTickRunnable: Runnable? = null
    private var sessionActive = false

    fun launchBattle(cfg: DemonConfigSnapshot) {
        if (sessionActive) return
        sessionActive = true
        Thread {
            val witch1 = WitchSpriteEngine.loadRandomWitch(context, cfg)
            if (witch1 == null) {
                sessionActive = false
                return@Thread
            }
            // Preload optional #108 Duo reinforcement in Strict/Tyrant mode
            val witch2 = if (cfg.chaosLevel >= 8) {
                WitchSpriteEngine.loadRandomWitch(context, cfg, excludePath = witch1.sourceFilePath)
            } else null

            mainHandler.post {
                if (!sessionActive) {
                    witch1.recycle()
                    witch2?.recycle()
                    return@post
                }
                activeWitch = witch1
                secondWitch = witch2
                mountBattleUi(witch1, witch2, cfg)
            }
        }.start()
    }

    fun forceCleanup() {
        sessionActive = false
        typewriterRunnable?.let { mainHandler.removeCallbacks(it) }
        typewriterRunnable = null
        qteTickRunnable?.let { mainHandler.removeCallbacks(it) }
        qteTickRunnable = null
        activeWitch?.recycle()
        activeWitch = null
        secondWitch?.recycle()
        secondWitch = null
    }

    private fun obedienceRankFor(level: Int): String = when {
        level >= 25 -> "Rebelle"
        level >= 15 -> "Apprenti"
        level >= 8 -> "Captif"
        level >= 3 -> "Serviteur"
        else -> "Soumis Absolu"
    }

    private fun mountBattleUi(
        initialWitch: WitchSpriteEngine.LoadedWitch,
        reinforcement: WitchSpriteEngine.LoadedWitch?,
        cfg: DemonConfigSnapshot
    ) {
        var curWitch = initialWitch
        var duoSpawned = false
        val snap0 = settings.snapshot()

        // Triple RPG Gauges (#101)
        var playerLv = snap0.playerLevel
        var hp = snap0.playerHp.coerceAtLeast(65)
        var mp = 60
        var embrace = 0                 // 0..100%
        var witchResolve = 100          // 100..0%
        var captured = false            // #102 Phase 2 Capture
        var guarding = false            // #106 Guard
        var ultimateCharging = false    // #105/106 Telegraphed Ultimate
        var status = ""                 // CHARMED / HYPNOTIZED / SEALED
        var statusTurns = 0
        var drainPct = snap0.activeDrainPct
        var inEndScene = false

        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.rgb(10, 6, 16))
        }

        // Layer 1: Fullscreen 60 FPS Live2D-lite Stage (#95, #96, #98, #99, #119, #120)
        val stage = WitchSpriteStageView(context).apply {
            setWitchBitmaps(curWitch.spriteBitmap, curWitch.backdropBitmap)
            hpRatio = hp / 100f
            drainIntensity = drainPct / 100f
        }
        root.addView(stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Layer 2: Semi-transparent Top RPG HUD (with built-in 20-tap emergency exit on NV badge!)
        val topHud = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(38), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.argb(185, 14, 8, 24))
                setStroke(dp(1), Color.argb(110, 217, 70, 239))
            }
        }
        val bossTitleTv = TextView(context).apply {
            setTextColor(Color.rgb(245, 208, 254))
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        var emergTaps = 0
        var emergTapMs = 0L
        val playerStatsTv = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 11.8f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(2))
            setOnClickListener {
                val now = System.currentTimeMillis()
                emergTaps = if (now - emergTapMs <= 1600L) emergTaps + 1 else 1
                emergTapMs = now
                if (emergTaps >= DemonSettings.REQUIRED_EXIT_TAPS) {
                    emergTaps = 0
                    forceCleanup()
                    onDismissFullscreen()
                    onSecretEmergencyExit()
                }
            }
        }
        val alertBannerTv = TextView(context).apply {
            setTextColor(Color.rgb(253, 224, 71))
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(4), 0, 0)
        }
        topHud.addView(bossTitleTv)
        topHud.addView(playerStatsTv)
        topHud.addView(alertBannerTv)
        root.addView(topHud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // Layer 3: Bottom Visual Novel Dialogue Box + Dynamic Phase Controls
        val bottomPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(22))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.argb(228, 16, 10, 26))
                setStroke(dp(2), Color.argb(190, 217, 70, 239))
            }
        }

        val speakerTv = TextView(context).apply {
            setTextColor(Color.rgb(244, 114, 182))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
        val dialogueTv = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            minLines = 3
            setPadding(0, dp(4), 0, dp(10))
        }
        val actionsBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        bottomPanel.addView(speakerTv)
        bottomPanel.addView(dialogueTv)
        bottomPanel.addView(actionsBox)

        root.addView(
            bottomPanel,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                marginStart = dp(10); marginEnd = dp(10); bottomMargin = dp(12)
            }
        )

        fun typeDialogue(speaker: String, fullText: String) {
            speakerTv.text = speaker
            typewriterRunnable?.let { mainHandler.removeCallbacks(it) }
            var idx = 0
            val run = object : Runnable {
                override fun run() {
                    if (!sessionActive) return
                    idx = (idx + 3).coerceAtMost(fullText.length)
                    dialogueTv.text = fullText.substring(0, idx)
                    if (idx < fullText.length) {
                        mainHandler.postDelayed(this, 22L)
                    }
                }
            }
            typewriterRunnable = run
            mainHandler.post(run)
        }

        fun refreshHud() {
            val phaseLabel = when {
                inEndScene -> "💋 DRAINAGE FINAL"
                captured -> "⛓️ CAPTURÉ (Phase 2)"
                embrace >= 75 -> "🔥 EMPRISE CRITIQUE"
                else -> "⚔️ DUEL"
            }
            val mdmTag = when {
                DemonMdmManager.isDeviceOwner(context) -> " • 👑 MDM"
                DemonMdmManager.isShizukuActive() -> " • ⚡ SHIZUKU"
                else -> ""
            }
            bossTitleTv.text = "${curWitch.profile.name} • NV ${curWitch.profile.bossLevel} (${curWitch.profile.element}) • $phaseLabel$mdmTag"
            val stStr = if (status.isNotEmpty()) " • ⚠️ $status" else ""
            playerStatsTv.text = "NV $playerLv (${obedienceRankFor(playerLv)}) | PV $hp/100 | PM $mp/60 | Emprise $embrace% | Boss $witchResolve%$stStr"

            stage.hpRatio = hp / 100f
            stage.drainIntensity = (drainPct / 100f).coerceAtLeast(if (captured) 0.45f else 0f)
            stage.captureZoomFactor = when {
                inEndScene -> 1.0f
                captured -> 0.65f
                embrace >= 65 -> 0.35f
                else -> 0f
            }

            if (ultimateCharging) {
                alertBannerTv.text = "⚠️ ${curWitch.profile.name} concentre son SORT ULTIME ! Utilise 🛡️ GARDE !"
                alertBannerTv.visibility = View.VISIBLE
            } else if (captured) {
                alertBannerTv.text = "⛓️ ENTRAVÉ ! Utilise ⚡ SE DÉBATTRE (QTE) pour te libérer !"
                alertBannerTv.visibility = View.VISIBLE
            } else {
                alertBannerTv.visibility = View.GONE
            }
        }

        lateinit var renderTurnControls: () -> Unit

        fun finishBattleVictory() {
            settings.adjustPlayerStats(levelDelta = +2, hpDelta = 100 - hp, drainDelta = -100)
            settings.adjustMood(-18)
            onClearVisualDrain()
            typeDialogue("✨ VICTOIRE !", "Tu as brisé l'emprise de ${curWitch.profile.name} ! (+2 NV, Luminosité & Qualité restaurées)…")
            actionsBox.removeAllViews()
            actionsBox.addView(makeActionBtn("Continuer (Victoire 🏆)", Color.rgb(16, 185, 129)) {
                forceCleanup()
                onDismissFullscreen()
                onSayPet("Grr… tu t'en sors cette fois ! (+2 NV) 😤")
            })
            Thread {
                val healedApp = DemonMdmManager.healOneSuspendedApp(context)
                if (healedApp != null) {
                    mainHandler.post {
                        if (sessionActive) {
                            typeDialogue("✨ VICTOIRE !", "Tu as brisé l'emprise de ${curWitch.profile.name} ! (+2 NV)\n🕊️ Récompense Shizuku/MDM : « $healedApp » est réactivée !")
                        }
                    }
                }
            }.start()
        }

        // ==================== #113 / #114 / #115 CINEMATIC DRAIN END-SCENE + REAL SHIZUKU/MDM DAMAGE ====================
        fun startCinematicDrainEndScene() {
            inEndScene = true
            stage.captureZoomFactor = 1.0f
            stage.drainIntensity = 1.0f
            onApplyVisualDrain(85, cfg)
            refreshHud()

            val severe = playerLv <= 6 || cfg.chaosLevel >= 9
            val totalLevelsLost = if (playerLv <= 4) 1 else Random.nextInt(2, 5)
            val targetLv = (playerLv - totalLevelsLost).coerceAtLeast(1)

            // Apply victorious Witch as wallpaper + REAL Shizuku/MDM damage asynchronously off UI thread!
            val wPath = curWitch.sourceFilePath
            val wUri = curWitch.sourceUri
            val mdmSummaryRef = java.util.concurrent.atomic.AtomicReference("⏳ Application de la malédiction système…")
            val mdmAffectedAppRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
            Thread {
                MediaFolderHelper.applySpecificWallpaper(context, cfg, wPath, wUri)
                val rep = DemonMdmManager.applyDefeatMdmDamage(context, playerLv, severe)
                mdmSummaryRef.set(rep.summary)
                mdmAffectedAppRef.set(rep.affectedAppLabel)
            }.start()

            // Animated cascading Level Drain!
            val drainStep = object : Runnable {
                override fun run() {
                    if (!sessionActive) return
                    if (playerLv > targetLv) {
                        playerLv--
                        settings.adjustPlayerStats(levelDelta = -1, hpDelta = 0, drainDelta = +10)
                        onVibrate(80L)
                        stage.triggerWitchAttackLunge(intense = true)
                        refreshHud()
                        mainHandler.postDelayed(this, 420L)
                    }
                }
            }
            mainHandler.postDelayed(drainStep, 350L)

            val mono = curWitch.profile.defeatMonologue
            var sceneIdx = 0
            val totalSteps = 4

            fun showSceneStep() {
                val (spk, txt) = when (sceneIdx) {
                    0 -> curWitch.profile.name to mono[0]
                    1 -> curWitch.profile.name to (mono[1] + "\n\n" + mdmSummaryRef.get())
                    2 -> if (targetLv <= 1) {
                        "💀 BAD END ULTIME — NIVEAU 1" to "Tu es tombé au NIVEAU 1 (Soumis Absolu) ! Plus aucune volonté… La Sorcière règne sur ton écran et garde ton fond d'écran en trophée ! 👑💜"
                    } else {
                        "💋 DÉFAITE — ÉNERGIE DRAINÉE" to mono[2]
                    }
                    else -> "🔮 Conseil Post-Défaite (MGQ)" to curWitch.profile.mgqAdviceLine
                }
                typeDialogue(spk, txt)
                stage.triggerWitchAttackLunge(intense = sceneIdx < 2)
                onVibrate(60L)
                actionsBox.removeAllViews()
                if (sceneIdx < totalSteps - 1) {
                    actionsBox.addView(makeActionBtn("Suite ▶ (${sceneIdx + 1}/$totalSteps)", Color.rgb(162, 28, 175)) {
                        sceneIdx++
                        showSceneStep()
                    })
                } else {
                    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    row.addView(makeActionBtn("⚔️ Revanche !", Color.rgb(225, 29, 72)) {
                        settings.update { putInt("player_hp", 80) }
                        forceCleanup()
                        onDismissFullscreen()
                        mainHandler.postDelayed({ launchBattle(settings.snapshot()) }, 250L)
                    }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
                    row.addView(makeActionBtn("😈 Accepter la Malédiction", Color.rgb(64, 24, 78)) {
                        settings.update { putInt("player_hp", 65) }
                        settings.adjustMood(+18)
                        forceCleanup()
                        onDismissFullscreen()
                        onSayPet("💋 Drain terminé ! ${mdmAffectedAppRef.get()?.let { "$it suspendue !" } ?: "Tu m'appartiens !"}")
                        if (cfg.unremovableAltarEnabled) onSpawnTrappedAltar(cfg)
                    }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) })
                    actionsBox.addView(row)
                }
            }
            showSceneStep()
        }

        fun executeWitchTurn(bonusMult: Float = 1f) {
            if (!sessionActive || inEndScene) return
            val wasGuarding = guarding
            guarding = false

            // #108 Duo Witch Reinforcement when Boss drops below 35%!
            if (!duoSpawned && reinforcement != null && witchResolve <= 35) {
                duoSpawned = true
                curWitch = reinforcement
                witchResolve = 75
                stage.setWitchBitmaps(curWitch.spriteBitmap, curWitch.backdropBitmap)
                stage.triggerWitchAttackLunge(intense = true)
                onVibrate(95L)
                typeDialogue("⚡ RENFORT DUO !", "${curWitch.profile.name} surgit en renfort ! « Tu croyais vraiment t'en sortir si facilement ?! 😈💜 »")
                refreshHud()
                renderTurnControls()
                return
            }

            // Telegraphed Ultimate Execution (#106)
            val attack = if (ultimateCharging) {
                ultimateCharging = false
                curWitch.profile.attacks.last()
            } else {
                // 25% chance to charge Ultimate for next turn
                if (!captured && Random.nextFloat() < 0.26f) {
                    ultimateCharging = true
                    typeDialogue(curWitch.profile.name, "« Je concentre toute ma magie… prépare-toi à tomber ! ⚠️ » (Utilise 🛡️ GARDE au prochain tour !)")
                    refreshHud()
                    renderTurnControls()
                    return
                }
                curWitch.profile.attacks.random()
            }

            val guardMul = if (wasGuarding) 0.35f else 1.0f
            val captureMul = if (captured) 1.35f else 1.0f
            val dmg = (attack.hpDamage * bonusMult * guardMul * captureMul).toInt().coerceAtLeast(6)
            val embGain = (attack.embraceGain * bonusMult * guardMul).toInt().coerceAtLeast(8)
            val lvLoss = if (wasGuarding) 0 else attack.levelDrain

            hp = (hp - dmg).coerceAtLeast(0)
            embrace = (embrace + embGain).coerceAtMost(100)
            if (lvLoss > 0) playerLv = (playerLv - lvLoss).coerceAtLeast(1)
            drainPct = (drainPct + (if (wasGuarding) 6 else 18)).coerceAtMost(95)

            // Apply status / Phase 2 Capture (#102, #104, #105)
            if (attack.statusEffect == "CAPTURE" || embrace >= 55) {
                captured = true
            } else if (attack.statusEffect.isNotEmpty() && !wasGuarding) {
                status = attack.statusEffect
                statusTurns = 2
            } else if (statusTurns > 0) {
                statusTurns--
                if (statusTurns == 0) status = ""
            }

            settings.adjustPlayerStats(levelDelta = -lvLoss, hpDelta = -dmg, drainDelta = +15)
            onApplyVisualDrain(drainPct, cfg)
            stage.triggerWitchAttackLunge(intense = !wasGuarding)
            onVibrate(if (wasGuarding) 30L else 85L)

            val guardTxt = if (wasGuarding) " (🛡️ Paré -65% !)" else ""
            val lvTxt = if (lvLoss > 0) " • -$lvLoss NV" else ""
            typeDialogue(
                curWitch.profile.name,
                "${attack.dialogue}\n💥 « ${attack.name} » : -$dmg PV$lvTxt • Emprise $embrace%$guardTxt"
            )
            refreshHud()

            if (hp <= 0 || embrace >= 100) {
                mainHandler.postDelayed({ startCinematicDrainEndScene() }, 950L)
            } else {
                renderTurnControls()
            }
        }

        // ==================== #103 QTE "SE DÉBATTRE" (10 TAPS IN 3.5s!) ====================
        fun startStruggleQte() {
            actionsBox.removeAllViews()
            var taps = 0
            val required = 10
            val endElapsed = SystemClock.elapsedRealtime() + 3_600L

            val qteBtn = makeActionBtn("⚡ TAPE VITE POUR TE LIBÉRER ! (0/$required)", Color.rgb(234, 88, 12)) {}
            val timerTv = TextView(context).apply {
                text = "⏳ 3.6s restantes !"
                setTextColor(Color.rgb(253, 224, 71))
                textSize = 12.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            }
            actionsBox.addView(qteBtn)
            actionsBox.addView(timerTv)

            qteBtn.setOnClickListener {
                taps++
                onVibrate(18L)
                qteBtn.text = "⚡ TAPE VITE POUR TE LIBÉRER ! ($taps/$required)"
                if (taps >= required) {
                    qteTickRunnable?.let { mainHandler.removeCallbacks(it) }
                    captured = false
                    embrace = (embrace - 40).coerceAtLeast(0)
                    witchResolve = (witchResolve - 20).coerceAtLeast(0)
                    stage.triggerWitchHitShake()
                    typeDialogue("💥 LIBÉRÉ !", "Tu brises les chaînes magiques de ${curWitch.profile.name} ! Emprise -40 % !")
                    refreshHud()
                    if (witchResolve <= 0) finishBattleVictory() else renderTurnControls()
                }
            }

            val tick = object : Runnable {
                override fun run() {
                    if (!sessionActive) return
                    val remMs = endElapsed - SystemClock.elapsedRealtime()
                    if (remMs <= 0L) {
                        typeDialogue(curWitch.profile.name, "« Trop lent ! Tu ne t'échapperas pas si facilement… 😈💋 »")
                        executeWitchTurn(bonusMult = 1.35f)
                    } else {
                        timerTv.text = String.format("⏳ %.1fs restantes !", remMs / 1000f)
                        mainHandler.postDelayed(this, 100L)
                    }
                }
            }
            qteTickRunnable = tick
            mainHandler.postDelayed(tick, 100L)
        }

        // ==================== #107 RP DIALOGUE CHOICES ====================
        fun showRpDialogueChoices() {
            actionsBox.removeAllViews()
            actionsBox.addView(makeActionBtn("🔥 Défier : « Tu ne m'auras jamais ! » (+25% Dégâts mais elle s'énerve)", Color.rgb(225, 29, 72)) {
                witchResolve = (witchResolve - 24).coerceAtLeast(0)
                stage.triggerWitchHitShake()
                if (witchResolve <= 0) finishBattleVictory()
                else executeWitchTurn(bonusMult = 1.25f)
            })
            actionsBox.addView(makeActionBtn("🥺 Se soumettre : « Pardon Maîtresse… » (Drain rapide, -Colère)", Color.rgb(126, 34, 206)) {
                settings.adjustMood(-12)
                executeWitchTurn(bonusMult = 1.45f)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(6)
                }
            })
        }

        renderTurnControls = {
            actionsBox.removeAllViews()
            val isCharmed = status == "CHARMED"
            val isSealed = status == "SEALED"

            if (captured) {
                // Phase 2 — Captured MGQ Controls (#102, #103)
                val r1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                r1.addView(makeActionBtn("⚡ Se débattre (QTE)", Color.rgb(234, 88, 12)) {
                    startStruggleQte()
                }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5) })
                r1.addView(makeActionBtn("🛡️ Endurer (+15 PM)", Color.rgb(59, 130, 246)) {
                    guarding = true
                    mp = (mp + 15).coerceAtMost(60)
                    executeWitchTurn(bonusMult = 0.85f)
                }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(5) })
                actionsBox.addView(r1)

                actionsBox.addView(makeActionBtn("💖 Céder au Drainage Final", Color.rgb(162, 28, 175)) {
                    embrace = 100
                    hp = 0
                    startCinematicDrainEndScene()
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(6) }
                })
            } else {
                // Phase 1 — Duel Controls (#101, #104, #105, #106, #107)
                val r1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                val atkLabel = when {
                    isSealed -> "🔒 Scellé !"
                    isCharmed -> "💖 Obéir (Charmé)"
                    else -> "⚔️ Riposte (-10 PM)"
                }
                r1.addView(makeActionBtn(atkLabel, if (isSealed) Color.rgb(65, 65, 78) else Color.rgb(225, 29, 72)) {
                    when {
                        isSealed -> {
                            onVibrate(40L)
                            executeWitchTurn(1.0f)
                        }
                        isCharmed -> {
                            status = ""; statusTurns = 0
                            executeWitchTurn(1.3f)
                        }
                        mp < 10 -> {
                            typeDialogue("⚡ PM insuffisants", "Utilise 🛡️ Garde pour récupérer +15 PM !")
                        }
                        else -> {
                            mp -= 10
                            val dmgBoss = Random.nextInt(28, 42)
                            witchResolve = (witchResolve - dmgBoss).coerceAtLeast(0)
                            stage.triggerWitchHitShake()
                            onVibrate(30L)
                            if (witchResolve <= 0) {
                                finishBattleVictory()
                            } else {
                                executeWitchTurn(1.0f)
                            }
                        }
                    }
                }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5) })

                r1.addView(makeActionBtn("🛡️ Garde (+15 PM)", Color.rgb(37, 99, 235)) {
                    guarding = true
                    mp = (mp + 15).coerceAtMost(60)
                    executeWitchTurn(1.0f)
                }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(5) })
                actionsBox.addView(r1)

                val r2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                r2.addView(makeActionBtn("💬 Réplique RP", Color.rgb(126, 34, 206)) {
                    showRpDialogueChoices()
                }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(5); topMargin = dp(6) })
                r2.addView(makeActionBtn("💖 Céder (Drain)", Color.rgb(98, 22, 108)) {
                    executeWitchTurn(1.65f)
                }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(5); topMargin = dp(6) })
                actionsBox.addView(r2)
            }
        }

        onMountFullscreen(root)
        refreshHud()
        typeDialogue(curWitch.profile.name, curWitch.profile.introLine)
        renderTurnControls()
        onVibrate(70L)
    }

    private fun makeActionBtn(label: String, bgColor: Int, onClick: () -> Unit): Button = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 11.8f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(bgColor)
            setStroke(dp(1), Color.argb(85, 255, 255, 255))
        }
        minHeight = 0; minimumHeight = 0
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setOnClickListener { onClick() }
    }
}
