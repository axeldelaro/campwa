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
import com.demonpet.app.rpg.WitchAiDialogueEngine
import com.demonpet.app.rpg.WitchCatalog
import com.demonpet.app.rpg.WitchRpgState
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * Fullscreen 50-Witch Monster Girl Quest-inspired Dark-Fantasy RPG Battle & Drain Controller (v5.0):
 * - Supports BOTH direct in-app fullscreen play (Explore / Boss / Bestiary) AND live Accessibility overlay ambushes!
 * - Auto-segmented HD Witch sprites from the user's chosen media folder (`WitchSpriteEngine`) + 60 FPS Live2D-lite Stage.
 * - Instant 0 ms Non-Repetitive AI Dialogue (`WitchAiDialogueEngine`) + optional live Gemini API cloud enhancement.
 * - Full MGQ Mechanics: PV / PM / SP (0..100) / Emprise % / Niveau, Frappe, Lame Spirituelle, Garde (-70% vs Ultime),
 *   Sceau d'Exorcisme (50 SP), 4 Consumable Relics (Élixir, Cristal, Talisman, Amulette), RP Choices,
 *   Phase 2 Capture + Fast QTE "Se débattre" (10 taps / 3.6s), Duo Witch Reinforcement,
 *   Phase 3 Cinematic 4-Step Drain End-Scene + MGQ Post-Defeat Oracle Advice.
 * - Real Shizuku / MDM system punishments by app usage order on defeat, restored on Victory / End Game / Code 100205!
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
    private val onSecretEmergencyExit: () -> Unit,
    private val onBattleFinished: (() -> Unit)? = null
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val rpgState = WitchRpgState(context)
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var activeWitch: WitchSpriteEngine.LoadedWitch? = null
    private var secondWitch: WitchSpriteEngine.LoadedWitch? = null
    private var typewriterRunnable: Runnable? = null
    private var qteTickRunnable: Runnable? = null
    @Volatile
    private var sessionActive = false

    fun isBattleActive(): Boolean = sessionActive

    fun launchBattle(cfg: DemonConfigSnapshot) {
        val def = WitchCatalog.randomWitchForChapter(rpgState.selectedChapter, includeBoss = false)
        launchBattleForWitch(cfg, def)
    }

    fun launchBattleForWitch(cfg: DemonConfigSnapshot, witchDef: WitchCatalog.WitchDef) {
        if (sessionActive) return
        sessionActive = true
        rpgState.markEncountered(witchDef.id)

        Thread {
            val witch1 = WitchSpriteEngine.loadWitchForDef(context, cfg, witchDef)
            // Optional Duo Witch reinforcement on Bosses or high Chaos (>= 8)
            val allowDuo = (witchDef.isBoss && witchDef.chapter >= 3) || cfg.chaosLevel >= 8
            val witch2 = if (allowDuo) {
                val allyDef = WitchCatalog.witchesInChapter(witchDef.chapter)
                    .filter { it.id != witchDef.id }
                    .randomOrNull() ?: WitchCatalog.ALL_WITCHES.random()
                WitchSpriteEngine.loadWitchForDef(context, cfg, allyDef, excludePath = witch1.sourceFilePath)
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
        val apiKey = rpgState.geminiApiKey

        // Full MGQ RPG Battle Gauges
        var playerLv = snap0.playerLevel
        var hp = snap0.playerHp.coerceIn(65, 100)
        var mp = 60
        var sp = 20                              // 0..100 (50 SP -> Sceau d'Exorcisme!)
        var embrace = 0                          // 0..100%
        var witchMaxResolve = curWitch.profile.maxResolve.coerceAtLeast(80)
        var witchResolve = witchMaxResolve
        var captured = false
        var guarding = false
        var ultimateCharging = false
        var status = ""                          // CHARMED / HYPNOTIZED / SEALED
        var statusTurns = 0
        var drainPct = snap0.activeDrainPct
        var inEndScene = false
        var dialogueEpoch = 0

        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.rgb(10, 6, 16))
        }

        // Layer 1: Fullscreen 60 FPS Live2D-lite Stage
        val stage = WitchSpriteStageView(context).apply {
            setWitchBitmaps(curWitch.spriteBitmap, curWitch.backdropBitmap)
            hpRatio = hp / 100f
            drainIntensity = drainPct / 100f
        }
        root.addView(stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Layer 2: Sleek Top RPG HUD (20-tap on NV bar = secret emergency unlock 100205!)
        val topHud = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(36), dp(12), dp(9))
            background = GradientDrawable().apply {
                setColor(Color.argb(195, 14, 8, 24))
                setStroke(dp(1), Color.argb(120, 217, 70, 239))
            }
        }
        val bossTitleTv = TextView(context).apply {
            setTextColor(Color.rgb(245, 208, 254))
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        var emergTaps = 0
        var emergTapMs = 0L
        val playerStatsTv = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 11.2f
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
        val bossHpTv = TextView(context).apply {
            setTextColor(Color.rgb(253, 164, 175))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
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
        topHud.addView(bossHpTv)
        topHud.addView(alertBannerTv)
        root.addView(topHud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // Layer 3: Bottom Visual Novel AI Dialogue Box + Tactical Commands
        val bottomPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.argb(232, 16, 10, 26))
                setStroke(dp(2), Color.argb(195, 217, 70, 239))
            }
        }

        val speakerTv = TextView(context).apply {
            setTextColor(Color.rgb(244, 114, 182))
            textSize = 12.8f
            typeface = Typeface.DEFAULT_BOLD
        }
        val dialogueTv = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 12.8f
            minLines = 3
            setPadding(0, dp(4), 0, dp(8))
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
                marginStart = dp(10); marginEnd = dp(10); bottomMargin = dp(10)
            }
        )

        fun typeDialogue(speaker: String, fullText: String) {
            speakerTv.text = speaker
            typewriterRunnable?.let { mainHandler.removeCallbacks(it) }
            var idx = 0
            val run = object : Runnable {
                override fun run() {
                    if (!sessionActive) return
                    idx = (idx + 4).coerceAtMost(fullText.length)
                    dialogueTv.text = fullText.substring(0, idx)
                    if (idx < fullText.length) {
                        mainHandler.postDelayed(this, 18L)
                    }
                }
            }
            typewriterRunnable = run
            mainHandler.post(run)
        }

        fun speakAi(
            speaker: String,
            event: WitchAiDialogueEngine.EventKind,
            skillName: String = "",
            extraFooter: String = "",
            affectedApp: String? = null
        ) {
            dialogueEpoch++
            val myEpoch = dialogueEpoch
            val instant = WitchAiDialogueEngine.generateInstantLine(
                witch = curWitch.def,
                event = event,
                skillName = skillName,
                playerLevel = playerLv,
                embracePct = embrace,
                affectedApp = affectedApp
            )
            val fullLocal = if (extraFooter.isNotBlank()) "$instant\n$extraFooter" else instant
            typeDialogue(speaker, fullLocal)

            if (apiKey.isNotBlank()) {
                WitchAiDialogueEngine.generateLiveAiLineIfConfigured(
                    apiKey = apiKey,
                    witch = curWitch.def,
                    event = event,
                    skillName = skillName,
                    playerLevel = playerLv,
                    embracePct = embrace
                ) { aiLine ->
                    mainHandler.post {
                        if (sessionActive && dialogueEpoch == myEpoch) {
                            val combined = if (extraFooter.isNotBlank()) "$aiLine\n$extraFooter" else aiLine
                            speakerTv.text = "$speaker • ✨ IA"
                            dialogueTv.text = combined
                        }
                    }
                }
            }
        }

        fun refreshHud() {
            val phaseLabel = when {
                inEndScene -> "💋 DRAINAGE FINAL"
                captured -> "⛓️ CAPTURÉ (Phase 2)"
                embrace >= 75 -> "🔥 EMPRISE CRITIQUE"
                else -> "⚔️ DUEL"
            }
            val privTag = when {
                DemonMdmManager.isShizukuActive() -> " • ⚡ SHIZUKU"
                DemonMdmManager.isDeviceOwner(context) -> " • 👑 MDM"
                else -> ""
            }
            bossTitleTv.text = "#${curWitch.def.id}/50 ${curWitch.profile.name} • NV ${curWitch.profile.bossLevel} (${curWitch.profile.element})$privTag"

            val stStr = if (status.isNotEmpty()) " • ⚠️ $status" else ""
            playerStatsTv.text = "NV $playerLv (${obedienceRankFor(playerLv)}) | PV $hp | PM $mp/60 | SP $sp/100$stStr"

            val bossPct = ((witchResolve * 100f) / witchMaxResolve.coerceAtLeast(1)).toInt().coerceIn(0, 100)
            bossHpTv.text = "$phaseLabel | Boss $witchResolve/$witchMaxResolve ($bossPct%) | Emprise $embrace%"

            stage.hpRatio = hp / 100f
            stage.drainIntensity = (drainPct / 100f).coerceAtLeast(if (captured) 0.45f else 0f)
            stage.captureZoomFactor = when {
                inEndScene -> 1.0f
                captured -> 0.65f
                embrace >= 65 -> 0.35f
                else -> 0f
            }

            if (ultimateCharging) {
                alertBannerTv.text = "⚠️ SORT ULTIME « ${curWitch.def.ultimateName} » EN CHARGE ! Utilise 🛡️ GARDE !"
                alertBannerTv.visibility = View.VISIBLE
            } else if (captured) {
                alertBannerTv.text = "⛓️ ENTRAVÉ ! Tape ⚡ SE DÉBATTRE (QTE) ou utilise un 📜 Talisman !"
                alertBannerTv.visibility = View.VISIBLE
            } else if (curWitch.isPlaceholderSprite) {
                alertBannerTv.text = "💡 Astuce : choisis ton dossier photos dans « ⚙️ Dossier & IA » pour incarner les 50 Sorcières !"
                alertBannerTv.visibility = View.VISIBLE
            } else {
                alertBannerTv.visibility = View.GONE
            }
        }

        lateinit var renderTurnControls: () -> Unit

        fun closeBattleAndNotify() {
            forceCleanup()
            onDismissFullscreen()
            onBattleFinished?.invoke()
        }

        // ==================== VICTORY ====================
        fun finishBattleVictory() {
            val (levelUps, unlockedNewCh, droppedRelic) = rpgState.recordVictory(curWitch.def)
            val totalLvGain = (if (curWitch.def.isBoss) 2 else 1) + levelUps
            settings.adjustPlayerStats(levelDelta = totalLvGain, hpDelta = 100 - hp, drainDelta = -100)
            settings.adjustMood(-18)
            onClearVisualDrain()

            val rewardParts = ArrayList<String>()
            rewardParts.add("+$totalLvGain NV")
            rewardParts.add("+${curWitch.def.rewardXp} XP")
            rewardParts.add("+${curWitch.def.rewardGold} 💎")
            if (droppedRelic != null) rewardParts.add("🎁 +1 $droppedRelic")
            if (unlockedNewCh) rewardParts.add("🗺️ Chapitre ${curWitch.def.chapter + 1} DÉBLOQUÉ !")
            val rewardFooter = "🏆 Récompenses : ${rewardParts.joinToString(" • ")}"

            speakAi("✨ VICTOIRE — #${curWitch.def.id}/50 VAINCUE !", WitchAiDialogueEngine.EventKind.VICTORY, extraFooter = rewardFooter)
            actionsBox.removeAllViews()
            actionsBox.addView(makeActionBtn("🏆 Continuer l'aventure", Color.rgb(16, 185, 129)) {
                closeBattleAndNotify()
                onSayPet("Bien joué ! ${curWitch.def.name} vaincue (+${curWitch.def.rewardGold} 💎) 🏆")
            })

            Thread {
                val healedApp = DemonMdmManager.healOneSuspendedApp(context)
                if (healedApp != null) {
                    mainHandler.post {
                        if (sessionActive) {
                            dialogueTv.text = "${dialogueTv.text}\n🕊️ Shizuku/MDM : « $healedApp » est libérée !"
                        }
                    }
                }
            }.start()
        }

        // ==================== PHASE 3 : CINEMATIC DRAIN END-SCENE + SYSTEM PUNISHMENTS ====================
        fun startCinematicDrainEndScene() {
            inEndScene = true
            stage.captureZoomFactor = 1.0f
            stage.drainIntensity = 1.0f
            onApplyVisualDrain(85, cfg)
            rpgState.recordDefeat(curWitch.def)
            refreshHud()

            val severe = playerLv <= 6 || cfg.chaosLevel >= 8 || curWitch.def.isBoss
            val totalLevelsLost = when {
                playerLv <= 3 -> 1
                curWitch.def.isBoss -> Random.nextInt(3, 6)
                else -> Random.nextInt(2, 4)
            }
            val targetLv = (playerLv - totalLevelsLost).coerceAtLeast(1)

            // Apply victorious Witch as Trophy Wallpaper + REAL Shizuku/MDM punishment asynchronously
            val wPath = curWitch.sourceFilePath
            val wUri = curWitch.sourceUri
            val mdmSummaryRef = AtomicReference("⏳ Application du sceau système…")
            val mdmAffectedAppRef = AtomicReference<String?>(null)
            Thread {
                if (wPath != null || wUri != null) {
                    MediaFolderHelper.applySpecificWallpaper(context, cfg, wPath, wUri)
                }
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
                        onVibrate(75L)
                        stage.triggerWitchAttackLunge(intense = true)
                        refreshHud()
                        mainHandler.postDelayed(this, 420L)
                    }
                }
            }
            mainHandler.postDelayed(drainStep, 350L)

            var sceneIdx = 0
            val totalSteps = 4

            fun showSceneStep() {
                stage.triggerWitchAttackLunge(intense = sceneIdx < 2)
                onVibrate(55L)
                when (sceneIdx) {
                    0 -> speakAi(
                        speaker = "💋 ${curWitch.profile.name} — Phase Finale (1/4)",
                        event = WitchAiDialogueEngine.EventKind.DRAIN_STAGE_1
                    )
                    1 -> speakAi(
                        speaker = "🔒 Malédiction Système (2/4)",
                        event = WitchAiDialogueEngine.EventKind.DRAIN_STAGE_2,
                        extraFooter = mdmSummaryRef.get(),
                        affectedApp = mdmAffectedAppRef.get()
                    )
                    2 -> {
                        if (targetLv <= 1) {
                            typeDialogue(
                                "💀 BAD END ULTIME — NIVEAU 1 (3/4)",
                                "Ton Niveau atteint 1 (Soumis Absolu) ! Ton mana est totalement épuisé face à ${curWitch.profile.name}. Son portrait règne sur ton fond d'écran en trophée ! 👑💜"
                            )
                        } else {
                            speakAi(
                                speaker = "🌙 Défaite — Énergie Drainée (3/4)",
                                event = WitchAiDialogueEngine.EventKind.DRAIN_STAGE_3
                            )
                        }
                    }
                    else -> {
                        typeDialogue("🔮 Oracle Post-Défaite (MGQ)", curWitch.profile.mgqAdviceLine)
                    }
                }

                actionsBox.removeAllViews()
                if (sceneIdx < totalSteps - 1) {
                    actionsBox.addView(makeActionBtn("Suite ▶ (${sceneIdx + 1}/$totalSteps)", Color.rgb(162, 28, 175)) {
                        sceneIdx++
                        showSceneStep()
                    })
                } else {
                    val rematchDef = curWitch.def
                    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    row.addView(makeActionBtn("⚔️ Revanche !", Color.rgb(225, 29, 72)) {
                        settings.update { putInt("player_hp", 85) }
                        forceCleanup()
                        onDismissFullscreen()
                        mainHandler.postDelayed({ launchBattleForWitch(settings.snapshot(), rematchDef) }, 220L)
                    }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })

                    row.addView(makeActionBtn("😈 Accepter le Sort", Color.rgb(64, 24, 78)) {
                        settings.update { putInt("player_hp", 70) }
                        settings.adjustMood(+18)
                        closeBattleAndNotify()
                        onSayPet("💋 Défaite face à ${curWitch.def.name} ! ${mdmAffectedAppRef.get()?.let { "$it scellée !" } ?: "Énergie drainée !"}")
                        if (cfg.unremovableAltarEnabled) onSpawnTrappedAltar(cfg)
                    }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(5) })
                    actionsBox.addView(row)
                }
            }
            showSceneStep()
        }

        // ==================== WITCH TURN ====================
        fun executeWitchTurn(bonusMult: Float = 1f) {
            if (!sessionActive || inEndScene) return
            val wasGuarding = guarding
            guarding = false

            // Duo Witch Reinforcement when Boss drops below 35% resolve!
            if (!duoSpawned && reinforcement != null && witchResolve <= (witchMaxResolve * 0.35f).toInt()) {
                duoSpawned = true
                curWitch = reinforcement
                witchMaxResolve = (curWitch.profile.maxResolve * 0.72f).toInt().coerceAtLeast(65)
                witchResolve = witchMaxResolve
                rpgState.markEncountered(curWitch.def.id)
                stage.setWitchBitmaps(curWitch.spriteBitmap, curWitch.backdropBitmap)
                stage.triggerWitchAttackLunge(intense = true)
                onVibrate(95L)
                speakAi(
                    speaker = "⚡ RENFORT DUO — ${curWitch.profile.name} !",
                    event = WitchAiDialogueEngine.EventKind.INTRO,
                    extraFooter = "Une 2e Sorcière du Chapitre ${curWitch.def.chapter} rejoint le duel !"
                )
                refreshHud()
                renderTurnControls()
                return
            }

            // Telegraphed Ultimate Attack
            val attack = if (ultimateCharging) {
                ultimateCharging = false
                curWitch.profile.ultimateAttack
            } else {
                val ultChance = if (curWitch.def.isBoss) 0.30f else 0.22f
                if (!captured && Random.nextFloat() < ultChance) {
                    ultimateCharging = true
                    speakAi(curWitch.profile.name, WitchAiDialogueEngine.EventKind.ULTIMATE_WARN, curWitch.def.ultimateName)
                    refreshHud()
                    renderTurnControls()
                    return
                }
                curWitch.profile.attacks.random()
            }

            val guardMul = if (wasGuarding) 0.30f else 1.0f
            val captureMul = if (captured) 1.32f else 1.0f
            val dmg = (attack.hpDamage * bonusMult * guardMul * captureMul).toInt().coerceAtLeast(5)
            val embGain = (attack.embraceGain * bonusMult * guardMul).toInt().coerceAtLeast(6)
            val lvLoss = if (wasGuarding) 0 else attack.levelDrain

            hp = (hp - dmg).coerceAtLeast(0)
            embrace = (embrace + embGain).coerceAtMost(100)
            sp = (sp + if (wasGuarding) 12 else 10).coerceAtMost(100)
            if (lvLoss > 0) playerLv = (playerLv - lvLoss).coerceAtLeast(1)
            drainPct = (drainPct + (if (wasGuarding) 5 else 16)).coerceAtMost(95)

            val triggeredCapture = !wasGuarding && (attack.statusEffect == "CAPTURE" || embrace >= 58)
            if (triggeredCapture) {
                captured = true
            } else if (attack.statusEffect.isNotEmpty() && !wasGuarding) {
                status = attack.statusEffect
                statusTurns = 2
            } else if (statusTurns > 0) {
                statusTurns--
                if (statusTurns == 0) status = ""
            }

            settings.adjustPlayerStats(levelDelta = -lvLoss, hpDelta = -dmg, drainDelta = +12)
            onApplyVisualDrain(drainPct, cfg)
            stage.triggerWitchAttackLunge(intense = !wasGuarding)
            onVibrate(if (wasGuarding) 28L else 80L)

            val guardTxt = if (wasGuarding) " • 🛡️ Paré (-70 %)" else ""
            val lvTxt = if (lvLoss > 0) " • -$lvLoss NV" else ""
            val statsLine = "💥 « ${attack.name} » : -$dmg PV$lvTxt • Emprise $embrace%$guardTxt"

            val ev = when {
                wasGuarding -> WitchAiDialogueEngine.EventKind.GUARDED
                triggeredCapture -> WitchAiDialogueEngine.EventKind.CAPTURE
                else -> WitchAiDialogueEngine.EventKind.ATTACK
            }
            speakAi(curWitch.profile.name, ev, skillName = attack.name, extraFooter = statsLine)
            refreshHud()

            if (hp <= 0 || embrace >= 100) {
                mainHandler.postDelayed({ startCinematicDrainEndScene() }, 950L)
            } else {
                renderTurnControls()
            }
        }

        // ==================== QTE "SE DÉBATTRE" (10 TAPS IN 3.6s) ====================
        fun startStruggleQte() {
            actionsBox.removeAllViews()
            var taps = 0
            val required = if (curWitch.def.isBoss) 11 else 10
            val endElapsed = SystemClock.elapsedRealtime() + 3_600L

            val qteBtn = makeActionBtn("⚡ TAPE VITE POUR TE LIBÉRER ! (0/$required)", Color.rgb(234, 88, 12)) {}
            val timerTv = TextView(context).apply {
                text = "⏳ 3.6s restantes !"
                setTextColor(Color.rgb(253, 224, 71))
                textSize = 12.2f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            }
            actionsBox.addView(qteBtn)
            actionsBox.addView(timerTv)

            qteBtn.setOnClickListener {
                taps++
                onVibrate(16L)
                qteBtn.text = "⚡ TAPE VITE POUR TE LIBÉRER ! ($taps/$required)"
                if (taps >= required) {
                    qteTickRunnable?.let { mainHandler.removeCallbacks(it) }
                    captured = false
                    embrace = (embrace - 40).coerceAtLeast(0)
                    sp = (sp + 15).coerceAtMost(100)
                    witchResolve = (witchResolve - 24).coerceAtLeast(0)
                    stage.triggerWitchHitShake()
                    speakAi("💥 LIBÉRÉ !", WitchAiDialogueEngine.EventKind.QTE_BREAK, extraFooter = "Emprise -40 % • +15 SP !")
                    refreshHud()
                    if (witchResolve <= 0) finishBattleVictory() else renderTurnControls()
                }
            }

            val tick = object : Runnable {
                override fun run() {
                    if (!sessionActive) return
                    val remMs = endElapsed - SystemClock.elapsedRealtime()
                    if (remMs <= 0L) {
                        speakAi(curWitch.profile.name, WitchAiDialogueEngine.EventKind.QTE_FAIL)
                        mainHandler.postDelayed({ executeWitchTurn(bonusMult = 1.32f) }, 450L)
                    } else {
                        timerTv.text = String.format("⏳ %.1fs restantes !", remMs / 1000f)
                        mainHandler.postDelayed(this, 100L)
                    }
                }
            }
            qteTickRunnable = tick
            mainHandler.postDelayed(tick, 100L)
        }

        // ==================== RELIC INVENTORY SUB-MENU ====================
        fun showItemsMenu() {
            val inv = rpgState.snapshot()
            actionsBox.removeAllViews()

            val r1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            r1.addView(makeActionBtn("🧪 Élixir +60 PV (${inv.elixirs})", if (inv.elixirs > 0) Color.rgb(16, 185, 129) else Color.rgb(55, 55, 68)) {
                if (rpgState.consumeItem("elixir")) {
                    hp = (hp + 60).coerceAtMost(100)
                    typeDialogue("🧪 Élixir de Volonté", "Tu récupères +60 PV ! (PV : $hp/100)")
                    refreshHud()
                    executeWitchTurn(0.9f)
                }
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) })

            r1.addView(makeActionBtn("💎 Cristal +40 PM (${inv.crystals})", if (inv.crystals > 0) Color.rgb(37, 99, 235) else Color.rgb(55, 55, 68)) {
                if (rpgState.consumeItem("crystal")) {
                    mp = (mp + 40).coerceAtMost(60)
                    sp = (sp + 20).coerceAtMost(100)
                    typeDialogue("💎 Cristal de Mana", "Tu récupères +40 PM et +20 SP !")
                    refreshHud()
                    executeWitchTurn(0.9f)
                }
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(4) })
            actionsBox.addView(r1)

            val r2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            r2.addView(makeActionBtn("📜 Talisman (${inv.talismans})", if (inv.talismans > 0) Color.rgb(217, 119, 6) else Color.rgb(55, 55, 68)) {
                if (rpgState.consumeItem("talisman")) {
                    captured = false
                    embrace = (embrace - 50).coerceAtLeast(0)
                    typeDialogue("📜 Talisman de Rupture", "Les liens de ${curWitch.def.name} explosent ! Emprise -50 % !")
                    stage.triggerWitchHitShake()
                    refreshHud()
                    renderTurnControls()
                }
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4); topMargin = dp(5) })

            r2.addView(makeActionBtn("🔮 Amulette (${inv.amulets})", if (inv.amulets > 0) Color.rgb(147, 51, 234) else Color.rgb(55, 55, 68)) {
                if (rpgState.consumeItem("amulet")) {
                    status = ""; statusTurns = 0
                    drainPct = 0
                    onClearVisualDrain()
                    typeDialogue("🔮 Amulette de Clarté", "Statuts purifiés & drainage visuel annulé ! ✨")
                    refreshHud()
                    renderTurnControls()
                }
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(4); topMargin = dp(5) })
            actionsBox.addView(r2)

            actionsBox.addView(makeActionBtn("↩ Retour aux commandes", Color.rgb(42, 36, 56)) {
                renderTurnControls()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)).apply { topMargin = dp(5) }
            })
        }

        // ==================== RP DIALOGUE CHOICES ====================
        fun showRpDialogueChoices() {
            actionsBox.removeAllViews()
            actionsBox.addView(makeActionBtn("🔥 Défier : « Je briserai ton sort ! » (+Dégâts, elle s'énerve)", Color.rgb(225, 29, 72)) {
                val dmgBoss = 26 + (playerLv / 3)
                witchResolve = (witchResolve - dmgBoss).coerceAtLeast(0)
                sp = (sp + 18).coerceAtMost(100)
                stage.triggerWitchHitShake()
                if (witchResolve <= 0) {
                    finishBattleVictory()
                } else {
                    speakAi(curWitch.profile.name, WitchAiDialogueEngine.EventKind.RP_DEFY)
                    mainHandler.postDelayed({ executeWitchTurn(bonusMult = 1.25f) }, 650L)
                }
            })
            actionsBox.addView(makeActionBtn("🥺 S'incliner : « Votre aura est trop forte… » (-Colère, +Emprise)", Color.rgb(126, 34, 206)) {
                settings.adjustMood(-12)
                speakAi(curWitch.profile.name, WitchAiDialogueEngine.EventKind.RP_SUBMIT)
                mainHandler.postDelayed({ executeWitchTurn(bonusMult = 1.45f) }, 650L)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) }
            })
            actionsBox.addView(makeActionBtn("↩ Retour", Color.rgb(42, 36, 56)) {
                renderTurnControls()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)).apply { topMargin = dp(5) }
            })
        }

        // ==================== DYNAMIC TURN CONTROLS ====================
        renderTurnControls = {
            actionsBox.removeAllViews()
            val isCharmed = status == "CHARMED"
            val isSealed = status == "SEALED"
            val inv = rpgState.snapshot()

            if (captured) {
                // Phase 2 — Captured Controls
                val r1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                r1.addView(makeActionBtn("⚡ Se débattre (QTE)", Color.rgb(234, 88, 12)) {
                    startStruggleQte()
                }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(4) })

                if (inv.talismans > 0) {
                    r1.addView(makeActionBtn("📜 Talisman (${inv.talismans})", Color.rgb(217, 119, 6)) {
                        if (rpgState.consumeItem("talisman")) {
                            captured = false
                            embrace = (embrace - 50).coerceAtLeast(0)
                            stage.triggerWitchHitShake()
                            speakAi("📜 LIBÉRATION INSTANTANÉE !", WitchAiDialogueEngine.EventKind.QTE_BREAK)
                            refreshHud()
                            renderTurnControls()
                        }
                    }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(4) })
                } else {
                    r1.addView(makeActionBtn("🛡️ Endurer (+20 PM)", Color.rgb(59, 130, 246)) {
                        guarding = true
                        mp = (mp + 20).coerceAtMost(60)
                        executeWitchTurn(bonusMult = 0.85f)
                    }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(4) })
                }
                actionsBox.addView(r1)

                val r2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                r2.addView(makeActionBtn("🎒 Objets", Color.rgb(79, 70, 229)) {
                    showItemsMenu()
                }, LinearLayout.LayoutParams(0, dp(38), 0.42f).apply { marginEnd = dp(4); topMargin = dp(5) })
                r2.addView(makeActionBtn("💖 Céder au Drainage Final", Color.rgb(162, 28, 175)) {
                    embrace = 100
                    hp = 0
                    startCinematicDrainEndScene()
                }, LinearLayout.LayoutParams(0, dp(38), 0.58f).apply { marginStart = dp(4); topMargin = dp(5) })
                actionsBox.addView(r2)
            } else {
                // Phase 1 — Complete MGQ Tactical Duel Controls
                val r1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                val strikeLabel = when {
                    isCharmed -> "💖 Obéir (Charmé)"
                    else -> "⚔️ Frappe (+15 SP)"
                }
                r1.addView(makeActionBtn(strikeLabel, if (isCharmed) Color.rgb(192, 38, 211) else Color.rgb(225, 29, 72)) {
                    if (isCharmed) {
                        status = ""; statusTurns = 0
                        executeWitchTurn(1.25f)
                    } else {
                        sp = (sp + 15).coerceAtMost(100)
                        val dmgBoss = Random.nextInt(18, 28) + (playerLv / 3)
                        witchResolve = (witchResolve - dmgBoss).coerceAtLeast(0)
                        stage.triggerWitchHitShake()
                        onVibrate(24L)
                        if (witchResolve <= 0) finishBattleVictory() else executeWitchTurn(1.0f)
                    }
                }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) })

                val bladeLabel = when {
                    isSealed -> "🔒 Lame Scellée"
                    else -> "🔥 Lame (-15 PM)"
                }
                r1.addView(makeActionBtn(bladeLabel, if (isSealed || mp < 15) Color.rgb(62, 58, 76) else Color.rgb(234, 88, 12)) {
                    when {
                        isSealed -> {
                            onVibrate(35L)
                            typeDialogue("🔒 Compétence Scellée", "Tes sorts sont scellés ! Utilise ⚔️ Frappe, 🛡️ Garde ou une 🔮 Amulette.")
                        }
                        mp < 15 -> {
                            typeDialogue("⚡ PM insuffisants", "Utilise 🛡️ Garde (+20 PM) ou un 💎 Cristal de Mana !")
                        }
                        else -> {
                            mp -= 15
                            sp = (sp + 10).coerceAtMost(100)
                            val dmgBoss = Random.nextInt(36, 52) + (playerLv / 2)
                            witchResolve = (witchResolve - dmgBoss).coerceAtLeast(0)
                            stage.triggerWitchHitShake()
                            onVibrate(38L)
                            if (witchResolve <= 0) finishBattleVictory() else executeWitchTurn(1.0f)
                        }
                    }
                }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(4) })
                actionsBox.addView(r1)

                val r2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                r2.addView(makeActionBtn("🛡️ Garde (+20 PM)", Color.rgb(37, 99, 235)) {
                    guarding = true
                    mp = (mp + 20).coerceAtMost(60)
                    executeWitchTurn(1.0f)
                }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4); topMargin = dp(5) })

                val canExorcise = sp >= 50
                r2.addView(makeActionBtn(
                    if (canExorcise) "✨ EXORCISME ! (50 SP)" else "✨ Exorcisme ($sp/50 SP)",
                    if (canExorcise) Color.rgb(217, 119, 6) else Color.rgb(55, 50, 68)
                ) {
                    if (!canExorcise) {
                        typeDialogue("✨ SP insuffisants", "Charge 50 SP avec ⚔️ Frappe et 🛡️ Garde pour libérer le Sceau d'Exorcisme !")
                    } else {
                        sp -= 50
                        status = ""; statusTurns = 0
                        embrace = (embrace - 45).coerceAtLeast(0)
                        val dmgBoss = Random.nextInt(48, 66) + playerLv
                        witchResolve = (witchResolve - dmgBoss).coerceAtLeast(0)
                        stage.triggerWitchHitShake()
                        onVibrate(55L)
                        if (witchResolve <= 0) {
                            finishBattleVictory()
                        } else {
                            typeDialogue("✨ SCEAU D'EXORCISME !", "Explosion sacrée : -$dmgBoss Résolution Boss • Emprise -45 % • Statuts purifiés !")
                            refreshHud()
                            mainHandler.postDelayed({ executeWitchTurn(0.85f) }, 700L)
                        }
                    }
                }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(4); topMargin = dp(5) })
                actionsBox.addView(r2)

                val r3 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                val totalItems = inv.elixirs + inv.crystals + inv.talismans + inv.amulets
                r3.addView(makeActionBtn("🎒 Objets ($totalItems)", Color.rgb(79, 70, 229)) {
                    showItemsMenu()
                }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(4); topMargin = dp(5) })

                r3.addView(makeActionBtn("💬 RP", Color.rgb(126, 34, 206)) {
                    showRpDialogueChoices()
                }, LinearLayout.LayoutParams(0, dp(36), 0.78f).apply { marginEnd = dp(4); topMargin = dp(5) })

                r3.addView(makeActionBtn("💖 Céder", Color.rgb(98, 22, 108)) {
                    executeWitchTurn(1.70f)
                }, LinearLayout.LayoutParams(0, dp(36), 0.85f).apply { topMargin = dp(5) })
                actionsBox.addView(r3)
            }
        }

        onMountFullscreen(root)
        refreshHud()
        speakAi(curWitch.profile.name, WitchAiDialogueEngine.EventKind.INTRO)
        renderTurnControls()
        onVibrate(65L)
    }

    private fun makeActionBtn(label: String, bgColor: Int, onClick: () -> Unit): Button = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 11.4f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(11).toFloat()
            setColor(bgColor)
            setStroke(dp(1), Color.argb(85, 255, 255, 255))
        }
        minHeight = 0; minimumHeight = 0
        setPadding(dp(6), dp(6), dp(6), dp(6))
        setOnClickListener { onClick() }
    }
}
