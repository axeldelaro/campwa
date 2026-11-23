package com.demonpet.app.ui

import android.Manifest
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.demonpet.app.ai.AvatarCutoutEngine
import com.demonpet.app.config.AvatarStyle
import com.demonpet.app.config.DemonConfigSnapshot
import com.demonpet.app.config.DemonSettings
import com.demonpet.app.config.FaceCensorMode
import com.demonpet.app.config.GenderFilter
import com.demonpet.app.config.SeverityPreset
import com.demonpet.app.config.WallpaperTarget
import com.demonpet.app.mdm.DemonMdmManager
import com.demonpet.app.mdm.DemonShizukuBridge
import com.demonpet.app.media.MediaFolderHelper
import com.demonpet.app.overlay.DemonPetView
import com.demonpet.app.overlay.WitchRpgBattleOverlay
import com.demonpet.app.receiver.DemonDeviceAdminReceiver
import com.demonpet.app.rpg.WitchCatalog
import com.demonpet.app.rpg.WitchCombatSystem
import com.demonpet.app.rpg.WitchLoreAndCampaign
import com.demonpet.app.rpg.WitchRpgState
import com.demonpet.app.rpg.WitchStoryEngine
import com.demonpet.app.service.DemonAccessibilityService
import com.demonpet.app.service.DemonCompanionService
import rikka.shizuku.Shizuku
import kotlin.random.Random

/**
 * DemonMom RPG v6.0 — Complete 50-Witch Tactical Dark-Fantasy RPG & Reactive Shizuku Punishment Hub:
 * - Tab 0: 🗺️ Aventure (RPG Profile, 5 Chapters, Explore, Boss, Infinite Witch Tower, Claimable Quests, Live Shizuku Status)
 * - Tab 1: 📖 Grimoire (Complete 50-Witch Bestiary with Chapter/Boss filters & 1-Tap Battle Launch)
 * - Tab 2: 🎒 Forge & Sac (Permanent Soul Forge Upgrades, 6 Relics Shop, Living Demon Overlay & Punishments)
 * - Tab 3: ⚙️ Dossier & IA (Hidden Folder .* Picker, Instant AI + Optional Gemini Key, Shizuku 1-Tap Test & Permissions)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var settings: DemonSettings
    private lateinit var rpgState: WitchRpgState
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var rootFrame: FrameLayout
    private lateinit var mainContentHost: FrameLayout
    private lateinit var battleHostFrame: FrameLayout
    private lateinit var inAppBattle: WitchRpgBattleOverlay

    private var lastRenderedLockState: Boolean? = null
    private var activeTab = 0
    private var grimoireFilter = 1 // 1..5 = Chapter 1..5, 0 = Bosses (10)

    private var previewPetView: DemonPetView? = null
    private var lockedPetAnimView: DemonPetView? = null
    private var lockedTapHintTv: TextView? = null
    private var headerPillTv: TextView? = null
    private var masterSwitch: Switch? = null
    private var mediaSummaryTv: TextView? = null
    private var restoreWallBtn: Button? = null
    private var tabButtons = ArrayList<Button>()
    private var tabContentContainer: FrameLayout? = null

    private val animTick = object : Runnable {
        override fun run() {
            val v = lockedPetAnimView ?: previewPetView
            if (v != null) {
                v.animPhase += 0.11f
                v.invalidate()
                mainHandler.postDelayed(this, 32L)
            }
        }
    }

    private val cfgListener: (DemonConfigSnapshot) -> Unit = { snap ->
        mainHandler.post {
            if (inAppBattle.isBattleActive()) {
                // Never rebuild or disturb the active battle/end-scene view!
                updateHeaderPill(snap)
                return@post
            }
            if (lastRenderedLockState != snap.challengeLocked) {
                renderScreen(snap)
            } else {
                previewPetView?.updateConfig(snap)
                updateHeaderPill(snap)
                if (masterSwitch?.isChecked != snap.enabled) masterSwitch?.isChecked = snap.enabled
                refreshMediaSummaryAsync(snap)
            }
        }
    }

    private val reactiveUiRefresh: () -> Unit = {
        mainHandler.post {
            if (!settings.challengeLocked && !inAppBattle.isBattleActive()) {
                renderActiveTab(settings.snapshot())
            }
        }
    }

    private val pickAvatarLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        Toast.makeText(this, "Détourage HD photo-réaliste en cours… ✨", Toast.LENGTH_SHORT).show()
        val includeHair = settings.cutoutIncludeHair
        Thread {
            val outPath = AvatarCutoutEngine.createAvatarFromUri(applicationContext, uri, includeHair)
            mainHandler.post {
                if (outPath != null) {
                    settings.avatarPath = outPath
                    Toast.makeText(this, "Portrait du compagnon prêt ! 😈", Toast.LENGTH_SHORT).show()
                    renderActiveTab(settings.snapshot())
                } else {
                    Toast.makeText(this, "Impossible de lire cette photo.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private val pickSafTreeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {
        }
        settings.update {
            putString("media_folder", "")
            putString("media_tree_uri", uri.toString())
        }
        Toast.makeText(this, "Dossier photos SAF sélectionné ✅", Toast.LENGTH_SHORT).show()
        refreshMediaSummaryAsync(settings.snapshot())
    }

    private val shizukuPermListener = Shizuku.OnRequestPermissionResultListener { reqCode, grantResult ->
        if (reqCode == DemonShizukuBridge.REQ_CODE_SHIZUKU) {
            mainHandler.post {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "⚡ Shizuku autorisé ! Liaison et activation UsageStats…", Toast.LENGTH_LONG).show()
                    Thread {
                        DemonShizukuBridge.ensureBound()
                        DemonShizukuBridge.autoGrantSafeAppOps()
                        mainHandler.post {
                            if (!settings.challengeLocked && !inAppBattle.isBattleActive()) {
                                renderActiveTab(settings.snapshot())
                            }
                        }
                    }.start()
                } else {
                    Toast.makeText(this, "Autorisation Shizuku refusée.", Toast.LENGTH_SHORT).show()
                    if (!settings.challengeLocked && !inAppBattle.isBattleActive()) {
                        renderActiveTab(settings.snapshot())
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = DemonSettings(applicationContext)
        rpgState = WitchRpgState(applicationContext)

        // Initialize Shizuku sticky binder listeners IMMEDIATELY on startup!
        DemonShizukuBridge.init(applicationContext)

        rootFrame = FrameLayout(this).apply { setBackgroundColor(BG_DARK) }
        mainContentHost = FrameLayout(this)
        battleHostFrame = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.rgb(10, 6, 16))
            isClickable = true
            isFocusable = true
        }
        rootFrame.addView(mainContentHost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        rootFrame.addView(battleHostFrame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(rootFrame)

        inAppBattle = WitchRpgBattleOverlay(
            context = this,
            settings = settings,
            onMountFullscreen = { battleView ->
                battleHostFrame.removeAllViews()
                battleHostFrame.addView(
                    battleView,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                battleHostFrame.visibility = View.VISIBLE
                if (rpgState.forcedFullscreen || rpgState.shizukuTaskPinning) setImmersiveFullscreen(true)
            },
            onDismissFullscreen = {
                battleHostFrame.removeAllViews()
                battleHostFrame.visibility = View.GONE
                clearWindowBrightnessDrain()
                setImmersiveFullscreen(false)
            },
            onApplyVisualDrain = { pct, cfg -> applyWindowBrightnessDrain(pct, cfg) },
            onClearVisualDrain = { clearWindowBrightnessDrain() },
            onSayPet = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() },
            onSpawnTrappedAltar = { _ -> DemonAccessibilityService.activeInstance?.triggerManualTest("altar") },
            onVibrate = { ms -> vibrateShort(ms) },
            onSecretEmergencyExit = { showExitPinDialog() },
            onBattleFinished = {
                if (!settings.challengeLocked) renderActiveTab(settings.snapshot())
            }
        )

        requestBasicRuntimePermissionsIfNeeded()
        renderScreen(settings.snapshot())
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (inAppBattle.isBattleActive()) {
            Toast.makeText(this, "Duel en cours ! Termine le combat (ou 20 taps barre NV + 100205).", Toast.LENGTH_SHORT).show()
            return
        }
        super.onBackPressed()
    }

    override fun onStart() {
        super.onStart()
        DemonSettings.addListener(cfgListener)
        DemonAccessibilityService.addStateListener(reactiveUiRefresh)
        DemonShizukuBridge.addStateListener(reactiveUiRefresh)
        try { Shizuku.addRequestPermissionResultListener(shizukuPermListener) } catch (_: Throwable) {}
    }

    override fun onResume() {
        super.onResume()
        DemonShizukuBridge.init(applicationContext)
        val snap = settings.snapshot()
        if (DemonShizukuBridge.hasShizukuPermission()) {
            Thread {
                DemonShizukuBridge.ensureBound()
                DemonShizukuBridge.autoGrantSafeAppOps()
            }.start()
        }
        if (lastRenderedLockState != snap.challengeLocked) {
            renderScreen(snap)
        } else if (!snap.challengeLocked && !inAppBattle.isBattleActive()) {
            renderActiveTab(snap)
        }
        if (snap.enabled && !DemonCompanionService.isRunning) {
            DemonCompanionService.start(applicationContext)
        }
    }

    override fun onStop() {
        super.onStop()
        DemonSettings.removeListener(cfgListener)
        DemonAccessibilityService.removeStateListener(reactiveUiRefresh)
        DemonShizukuBridge.removeStateListener(reactiveUiRefresh)
        try { Shizuku.removeRequestPermissionResultListener(shizukuPermListener) } catch (_: Throwable) {}
        mainHandler.removeCallbacks(animTick)
    }

    override fun onDestroy() {
        inAppBattle.forceCleanup()
        Thread { try { DemonMdmManager.setTaskPinningViaShizuku(0, false) } catch (_: Throwable) {} }.start()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun applyWindowBrightnessDrain(pct: Int, cfg: DemonConfigSnapshot) {
        if (!cfg.drainBrightnessEnabled) return
        try {
            val lp = window.attributes
            lp.screenBrightness = (1f - (pct / 100f) * 0.85f).coerceIn(0.08f, 1f)
            window.attributes = lp
        } catch (_: Throwable) {}
    }

    private fun clearWindowBrightnessDrain() {
        try {
            val lp = window.attributes
            lp.screenBrightness = -1f
            window.attributes = lp
        } catch (_: Throwable) {}
    }

    @Suppress("DEPRECATION")
    private fun vibrateShort(ms: Long) {
        if (!settings.vibrateOnPrank) return
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vib.vibrate(ms)
            }
        } catch (_: Throwable) {}
    }

    @Suppress("DEPRECATION")
    private fun setImmersiveFullscreen(enable: Boolean) {
        try {
            if (enable) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.insetsController?.let { ctrl ->
                        ctrl.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                        ctrl.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                } else {
                    window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        )
                }
                if (rpgState.shizukuTaskPinning && DemonMdmManager.isShizukuActive()) {
                    val tid = taskId
                    Thread { try { DemonMdmManager.setTaskPinningViaShizuku(tid, true) } catch (_: Throwable) {} }.start()
                }
            } else {
                Thread { try { DemonMdmManager.setTaskPinningViaShizuku(0, false) } catch (_: Throwable) {} }.start()
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                } else {
                    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                }
            }
        } catch (_: Throwable) {}
    }

    private fun startInAppDuel(
        witchDef: WitchCatalog.WitchDef,
        battleMods: WitchStoryEngine.BattleModifiers = WitchStoryEngine.BattleModifiers(),
        storyMilestoneIndex: Int = -1
    ) {
        if (inAppBattle.isBattleActive()) return
        // Optional Forced System-Wide Overlay Battle Mode (blocks entire OS via Accessibility Overlay!)
        if (rpgState.forcedOverlayBattle && DemonAccessibilityService.isServiceActive()) {
            val ok = DemonAccessibilityService.activeInstance?.launchForcedFullscreenWitchBattle(witchDef) == true
            if (ok) {
                Toast.makeText(this, "🔒 Embuscade Plein Écran Forcé : ${witchDef.name} !", Toast.LENGTH_SHORT).show()
                return
            }
        }
        Toast.makeText(this, "⚔️ Invocation de ${witchDef.name} (NV ${witchDef.level})…", Toast.LENGTH_SHORT).show()
        inAppBattle.launchBattleForWitch(
            cfg = settings.snapshot(),
            witchDef = witchDef,
            battleMods = battleMods,
            storyMilestoneIndex = storyMilestoneIndex
        )
    }

    /** Launches an atmospheric 5-Act Story Milestone OR an unpredictable suspense encounter (Fate Wheel, 3 Chests, Fog, Raid)! */
    private fun launchChapterExploration(chapter: Int) {
        if (inAppBattle.isBattleActive()) return
        val rSnap = rpgState.snapshot()
        val ev = WitchStoryEngine.generateExplorationEvent(chapter, rSnap.storyStep, settings.snapshot().playerLevel)
        showStoryEncounterDialog(ev, chapter)
    }

    private fun showStoryEncounterDialog(ev: WitchStoryEngine.ExplorationEvent, chapter: Int) {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(16, 11, 28))
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        wrap.addView(TextView(this).apply {
            text = ev.badge
            setTextColor(Color.rgb(240, 171, 252)); textSize = 11.5f; typeface = Typeface.DEFAULT_BOLD
        })
        wrap.addView(TextView(this).apply {
            text = ev.title
            setTextColor(Color.WHITE); textSize = 17.5f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(3), 0, dp(8))
        })
        wrap.addView(TextView(this).apply {
            text = ev.narration
            setTextColor(Color.rgb(225, 220, 240)); textSize = 13f; setPadding(0, 0, 0, dp(14))
        })

        val dlg = AlertDialog.Builder(this).setView(wrap).setCancelable(true).create()
        for (choice in ev.choices) {
            wrap.addView(makeAccentButton(choice.label, choice.colorHex) {
                dlg.dismiss()
                if (choice.corruptionDelta != 0) rpgState.adjustCorruption(choice.corruptionDelta)
                if (choice.rewardGold != 0 || choice.rewardItemKey != null) {
                    rpgState.grantReward(choice.rewardGold, choice.rewardItemKey)
                }
                when (choice.outcomeType) {
                    WitchStoryEngine.OutcomeType.START_BATTLE -> {
                        val target = choice.targetWitchId?.let { WitchCatalog.getById(it) }
                            ?: WitchCatalog.randomWitchForChapter(chapter, includeBoss = false)
                        startInAppDuel(target, choice.battleMods, ev.storyMilestoneIndex)
                    }
                    WitchStoryEngine.OutcomeType.FATE_SPIN -> handleFateWheelSpin(chapter)
                    WitchStoryEngine.OutcomeType.CHEST_PICK -> handleThreeChestsPick(chapter)
                    WitchStoryEngine.OutcomeType.GRANT_REWARD_ONLY -> {
                        AlertDialog.Builder(this)
                            .setTitle("✨ Destin du Coven")
                            .setMessage(choice.resultNarration)
                            .setPositiveButton("Continuer") { _, _ -> renderActiveTab(settings.snapshot()) }
                            .show()
                    }
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(7)
                }
            })
        }
        dlg.show()
    }

    private fun handleFateWheelSpin(chapter: Int) {
        when (Random.nextInt(5)) {
            0 -> {
                rpgState.grantReward(140, "aegis")
                AlertDialog.Builder(this)
                    .setTitle("🎰 JACKPOT DE L'ÉCLIPSE !")
                    .setMessage("La Roue s'arrête sur l'Or Runique !\nTu remportes +140 💎 et 1 🛡️ Voile d'Égide !")
                    .setPositiveButton("Super !") { _, _ -> renderActiveTab(settings.snapshot()) }
                    .show()
            }
            1 -> {
                Thread {
                    val freed = DemonMdmManager.healOneSuspendedApp(applicationContext)
                    mainHandler.post {
                        settings.update { putInt("player_hp", 100); putInt("active_drain_pct", 0) }
                        val appMsg = freed?.let { "\n🕊️ Shizuku : « $it » a été dégrisée !" } ?: ""
                        AlertDialog.Builder(this)
                            .setTitle("🎰 GRÂCE INESPÉRÉE !")
                            .setMessage("Une vague lumineuse restaure 100 % de tes PV et dissipe ton drainage !$appMsg")
                            .setPositiveButton("Continuer") { _, _ -> renderActiveTab(settings.snapshot()) }
                            .show()
                    }
                }.start()
            }
            2 -> {
                rpgState.grantReward(50, "incense")
                rpgState.grantReward(0, "crystal")
                AlertDialog.Builder(this)
                    .setTitle("🎰 PACTE DU MANA !")
                    .setMessage("Tu obtiens +50 💎, 1 🕯️ Encens de Furie et 1 💎 Cristal de Mana !")
                    .setPositiveButton("Continuer") { _, _ -> renderActiveTab(settings.snapshot()) }
                    .show()
            }
            3 -> {
                val elite = WitchCatalog.witchesInChapter(chapter).firstOrNull { it.isMiniBoss }
                    ?: WitchCatalog.randomWitchForChapter(chapter)
                Toast.makeText(this, "🎰 PIÈGE DE LA ROUE ! Embuscade Élite : ${elite.name} !", Toast.LENGTH_LONG).show()
                startInAppDuel(
                    elite,
                    WitchStoryEngine.BattleModifiers(startEmbrace = 24, forcedAppRaid = true, introContextLine = "La Roue du Destin t'a piégé devant ${elite.name} !")
                )
            }
            else -> {
                rpgState.grantReward(110, "talisman")
                settings.adjustPlayerStats(levelDelta = -1, hpDelta = 0, drainDelta = 0)
                AlertDialog.Builder(this)
                    .setTitle("🎰 TRIBUT DES OMBRES")
                    .setMessage("La Roue aspire -1 NV, mais te récompense de +110 💎 et 1 📜 Talisman de Rupture !")
                    .setPositiveButton("Continuer") { _, _ -> renderActiveTab(settings.snapshot()) }
                    .show()
            }
        }
    }

    private fun handleThreeChestsPick(chapter: Int) {
        val isMimicTrap = Random.nextInt(3) == 0 // 1 in 3 chance of a Mimic Witch Trap!
        if (isMimicTrap) {
            val w = WitchCatalog.randomWitchForChapter(chapter, includeBoss = false)
            Toast.makeText(this, "📦 PIÈGE ! Une Sorcière Mimique bondit du coffre !", Toast.LENGTH_LONG).show()
            startInAppDuel(
                w,
                WitchStoryEngine.BattleModifiers(
                    startEmbrace = 26,
                    startCaptured = true,
                    introContextLine = "PIÈGE DU COFFRE ! ${w.name} t'attrape par surprise dès le 1er tour !"
                )
            )
        } else {
            val prize = listOf("elixir" to "🧪 Élixir", "talisman" to "📜 Talisman", "aegis" to "🛡️ Voile d'Égide", "incense" to "🕯️ Encens de Furie").random()
            val gold = Random.nextInt(75, 135)
            rpgState.grantReward(gold, prize.first)
            AlertDialog.Builder(this)
                .setTitle("📦 TRÉSOR DU COVEN !")
                .setMessage("Bonne pioche ! Le coffre contient +$gold 💎 et 1 ${prize.second} !")
                .setPositiveButton("Génial !") { _, _ -> renderActiveTab(settings.snapshot()) }
                .show()
        }
    }

    private fun startInfiniteTower() {
        if (inAppBattle.isBattleActive()) return
        Toast.makeText(this, "🗼 Ascension de la Tour Infinie — Étage 1 !", Toast.LENGTH_SHORT).show()
        inAppBattle.launchTowerRun(settings.snapshot(), floor = 1)
    }

    // ==================== 5-ROOM LINKED DUNGEON EXPEDITION ENGINE ====================
    private fun startChapterDungeonExpedition(chapter: Int) {
        if (inAppBattle.isBattleActive()) return
        val rooms = WitchLoreAndCampaign.generateChapterDungeon(chapter)
        val rSnap = rpgState.snapshot()
        runDungeonRoomStep(chapter, rooms, 0, rSnap.maxHp, rSnap.maxMp, 30, 0)
    }

    private fun runDungeonRoomStep(
        chapter: Int,
        rooms: List<WitchLoreAndCampaign.DungeonRoom>,
        roomIdx: Int,
        carryHp: Int,
        carryMp: Int,
        carrySp: Int,
        carryEmbrace: Int
    ) {
        if (roomIdx >= rooms.size) {
            rpgState.recordDungeonComplete()
            val bonusGold = 260 + chapter * 50
            val rareRelic = listOf("posture_bomb" to "💣 Bombe Brise-Posture", "holy_water" to "💧 Eau Bénite", "ether_elixir" to "⚡ Élixir d'Éther", "hourglass" to "⏳ Sablier de Répit").random()
            rpgState.grantReward(bonusGold, rareRelic.first)
            AlertDialog.Builder(this)
                .setTitle("🏆 EXPÉDITION 5/5 TRIOMPHANTE !")
                .setMessage("Tu as traversé toutes les salles du donjon Chapitre $chapter !\n\n🎁 Coffre Légendaire : +$bonusGold 💎 + 1 ${rareRelic.second} + Affection Démon !")
                .setPositiveButton("Incroyable !") { _, _ -> renderActiveTab(settings.snapshot()) }
                .show()
            return
        }

        val room = rooms[roomIdx]
        val rSnap = rpgState.snapshot()
        val statusBar = "❤️ PV $carryHp/${rSnap.maxHp} • 💙 PM $carryMp/${rSnap.maxMp} • ⚡ SP $carrySp • 💜 Emprise $carryEmbrace%"

        when (room.kind) {
            WitchLoreAndCampaign.RoomKind.AMBUSH,
            WitchLoreAndCampaign.RoomKind.ELITE_GUARD,
            WitchLoreAndCampaign.RoomKind.DUNGEON_BOSS -> {
                val witch = WitchCatalog.getById(room.witchId ?: 1)
                AlertDialog.Builder(this)
                    .setTitle("${room.kind.icon} ${room.title}")
                    .setMessage("${room.narration}\n\n$statusBar\nAdversaire : ${witch.displayTitle} (NV ${witch.level})")
                    .setPositiveButton("⚔️ Combattre !") { _, _ ->
                        inAppBattle.launchBattleForWitch(
                            cfg = settings.snapshot(),
                            witchDef = witch,
                            carryHp = carryHp,
                            carryMp = carryMp,
                            carrySp = carrySp,
                            carryEmbrace = carryEmbrace,
                            onExpeditionVictory = { nHp, nMp, nSp, nEmb ->
                                mainHandler.postDelayed({
                                    runDungeonRoomStep(chapter, rooms, roomIdx + 1, nHp, nMp, nSp, nEmb)
                                }, 260L)
                            }
                        )
                    }
                    .setNegativeButton("Battre en retraite", null)
                    .show()
            }
            WitchLoreAndCampaign.RoomKind.SPIRIT_ALTAR -> {
                AlertDialog.Builder(this)
                    .setTitle("🌀 ${room.title}")
                    .setMessage("${room.narration}\n\n$statusBar")
                    .setPositiveButton("💚 Restaurer 100 % PV & PM") { _, _ ->
                        runDungeonRoomStep(chapter, rooms, roomIdx + 1, rSnap.maxHp, rSnap.maxMp, (carrySp + 25).coerceAtMost(100), (carryEmbrace - 25).coerceAtLeast(0))
                    }
                    .setNeutralButton("⚡ Charger 100 SP + 1 Eau Bénite") { _, _ ->
                        rpgState.grantReward(40, "holy_water")
                        runDungeonRoomStep(chapter, rooms, roomIdx + 1, carryHp, carryMp, 100, carryEmbrace)
                    }
                    .show()
            }
            WitchLoreAndCampaign.RoomKind.TREASURE_VAULT -> {
                AlertDialog.Builder(this)
                    .setTitle("📦 ${room.title}")
                    .setMessage("${room.narration}\n\n$statusBar")
                    .setPositiveButton("📦 Ouvrir le Coffre Doré (Risque Mimique)") { _, _ ->
                        if (Random.nextInt(3) == 0) {
                            val trapW = WitchCatalog.randomWitchForChapter(chapter, includeBoss = false)
                            Toast.makeText(this, "📦 PIÈGE ! ${trapW.name} surgit du coffre !", Toast.LENGTH_SHORT).show()
                            inAppBattle.launchBattleForWitch(
                                cfg = settings.snapshot(), witchDef = trapW,
                                carryHp = carryHp, carryMp = carryMp, carrySp = carrySp, carryEmbrace = (carryEmbrace + 15).coerceAtMost(80),
                                onExpeditionVictory = { nHp, nMp, nSp, nEmb ->
                                    rpgState.grantReward(120, "posture_bomb")
                                    mainHandler.postDelayed({ runDungeonRoomStep(chapter, rooms, roomIdx + 1, nHp, nMp, nSp, nEmb) }, 260L)
                                }
                            )
                        } else {
                            rpgState.grantReward(110, "aegis")
                            Toast.makeText(this, "🎁 Trésor ! +110 💎 + 1 🛡️ Voile d'Égide !", Toast.LENGTH_LONG).show()
                            runDungeonRoomStep(chapter, rooms, roomIdx + 1, (carryHp + 35).coerceAtMost(rSnap.maxHp), carryMp, carrySp, carryEmbrace)
                        }
                    }
                    .setNeutralButton("🚶 Passer prudemment (+35 PV)") { _, _ ->
                        runDungeonRoomStep(chapter, rooms, roomIdx + 1, (carryHp + 35).coerceAtMost(rSnap.maxHp), (carryMp + 20).coerceAtMost(rSnap.maxMp), carrySp, carryEmbrace)
                    }
                    .show()
            }
            WitchLoreAndCampaign.RoomKind.DARK_MERCHANT -> {
                AlertDialog.Builder(this)
                    .setTitle("🕯️ ${room.title}")
                    .setMessage("${room.narration}\n\n$statusBar • Ton or : ${rSnap.gold} 💎")
                    .setPositiveButton("🧪 Soin complet (40 💎)") { _, _ ->
                        if (rSnap.gold >= 40) {
                            rpgState.grantReward(-40)
                            runDungeonRoomStep(chapter, rooms, roomIdx + 1, rSnap.maxHp, rSnap.maxMp, (carrySp + 35).coerceAtMost(100), 0)
                        } else {
                            Toast.makeText(this, "Pas assez d'or (40 💎) !", Toast.LENGTH_SHORT).show()
                            runDungeonRoomStep(chapter, rooms, roomIdx + 1, carryHp, carryMp, carrySp, carryEmbrace)
                        }
                    }
                    .setNeutralButton("💣 Acheter Bombe Posture (55 💎)") { _, _ ->
                        if (rpgState.buyItem("posture_bomb", 55)) Toast.makeText(this, "💣 Bombe Brise-Posture achetée !", Toast.LENGTH_SHORT).show()
                        runDungeonRoomStep(chapter, rooms, roomIdx + 1, (carryHp + 25).coerceAtMost(rSnap.maxHp), carryMp, carrySp, carryEmbrace)
                    }
                    .setNegativeButton("Continuer vers le Boss ▶") { _, _ ->
                        runDungeonRoomStep(chapter, rooms, roomIdx + 1, (carryHp + 20).coerceAtMost(rSnap.maxHp), (carryMp + 15).coerceAtMost(rSnap.maxMp), carrySp, carryEmbrace)
                    }
                    .show()
            }
        }
    }

    // ==================== LITTLE DEMON MINI-GAMES: GRIMOIRE QUIZ & RPS ====================
    private fun showGrimoireQuizDialog() {
        val q = WitchLoreAndCampaign.pickRandomQuizQuestion()
        AlertDialog.Builder(this)
            .setTitle("🧠 Quiz du Grimoire (50 Questions)")
            .setMessage(q.question)
            .setItems(q.options.toTypedArray()) { _, which ->
                if (which == q.correctIndex) {
                    rpgState.recordQuizCorrect()
                    rpgState.grantReward(55)
                    AlertDialog.Builder(this)
                        .setTitle("✅ BONNE RÉPONSE ! (+55 💎)")
                        .setMessage("${q.explanation}\n\nTon Petit Démon t'applaudit (+Affection & +15 XP Démon) !")
                        .setPositiveButton("Question suivante ▶") { _, _ -> showGrimoireQuizDialog() }
                        .setNegativeButton("Fermer") { _, _ -> renderActiveTab(settings.snapshot()) }
                        .show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("❌ RATÉ ! 😈")
                        .setMessage("La bonne réponse était : ${q.options[q.correctIndex]}\n\n${q.explanation}")
                        .setPositiveButton("Réessayer ▶") { _, _ -> showGrimoireQuizDialog() }
                        .setNegativeButton("Fermer") { _, _ -> renderActiveTab(settings.snapshot()) }
                        .show()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showDemonRpsMiniGame() {
        val choices = arrayOf("🪨 Pierre", "📄 Feuille", "✂️ Ciseaux")
        AlertDialog.Builder(this)
            .setTitle("🎲 Pierre-Feuille-Ciseaux Démoniaque")
            .setItems(choices) { _, p ->
                val d = Random.nextInt(3)
                val win = (p == 0 && d == 2) || (p == 1 && d == 0) || (p == 2 && d == 1)
                val msg = when {
                    p == d -> "Égalité (${choices[d]}) ! +15 💎 & +3 Affection."
                    win -> "Gagné contre ${choices[d]} ! 🎉 +50 💎, -10 Colère & +6 Affection !"
                    else -> "Le Petit Démon gagne avec ${choices[d]} ! 😈 +10 💎 de consolation."
                }
                if (win) {
                    rpgState.grantReward(50); settings.adjustMood(-10); rpgState.addDemonCare(+6, +4, +14)
                } else {
                    rpgState.grantReward(if (p == d) 15 else 10); rpgState.addDemonCare(+3, 0, +6)
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                renderActiveTab(settings.snapshot())
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showWitchLoreDialog(w: WitchCatalog.WitchDef) {
        val bond = rpgState.getBondLevel(w.id)
        val bio = WitchLoreAndCampaign.getBiography(w)
        val recSpirit = WitchCombatSystem.recommendedSpiritFor(w)
        val body = buildString {
            append("📖 ORIGINE\n${bio.originStory}\n\n")
            append("🌀 ESPRIT CONSEILLÉ : ${recSpirit.displayName}\n")
            append("💡 TACTIQUE : ${bio.secretWeaknessLore}\n\n")
            append("⚔️ SORTS CLÉS : ${w.skillNames.joinToString(", ")} + Ultime « ${w.ultimateName} »\n\n")
            append("♥ LIEN D'ÂME (Nv.$bond/5) :\n")
            for (i in 0 until 5) {
                if (i < bond) append("• ${bio.bondLines[i]}\n")
                else append("• 🔒 Lien ${i + 1} : Vaincs ${w.name} ${i + 1}× pour débloquer ce souvenir.\n")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("#${w.id} ${w.displayTitle}")
            .setMessage(body)
            .setPositiveButton("⚔️ Combattre") { _, _ -> startInAppDuel(w) }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun runShizukuSuspendTest() {
        Toast.makeText(this, "🧪 Test Shizuku en cours (grise 1 appli 2.5s puis restaure)…", Toast.LENGTH_SHORT).show()
        Thread {
            val report = DemonMdmManager.testShizukuSuspendDiagnostic(applicationContext)
            mainHandler.post {
                AlertDialog.Builder(this)
                    .setTitle("🧪 Diagnostic Shizuku")
                    .setMessage(report)
                    .setPositiveButton("OK") { _, _ -> renderActiveTab(settings.snapshot()) }
                    .show()
            }
        }.start()
    }

    private fun renderScreen(snap: DemonConfigSnapshot) {
        lastRenderedLockState = snap.challengeLocked
        mainHandler.removeCallbacks(animTick)
        previewPetView = null
        lockedPetAnimView = null
        lockedTapHintTv = null
        mainContentHost.removeAllViews()

        if (snap.challengeLocked) {
            mainContentHost.addView(buildLockedChallengeGate(snap))
            mainHandler.post(animTick)
        } else {
            mainContentHost.addView(buildTabbedShell(snap))
            renderActiveTab(snap)
        }
    }

    private fun updateHeaderPill(snap: DemonConfigSnapshot) {
        val rSnap = rpgState.snapshot()
        headerPillTv?.text = "NV ${snap.playerLevel} • 💎 ${rSnap.gold}"
    }

    // -------------------------------------------------------------------------
    // LOCKED CHALLENGE GATE (20 taps + 100205)
    // -------------------------------------------------------------------------
    private fun buildLockedChallengeGate(snap: DemonConfigSnapshot): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(BG_DARK)
            setPadding(dp(24), dp(36), dp(24), dp(36))
        }

        wrap.addView(TextView(this).apply {
            text = "🔒 ${snap.avatarName} verrouille les réglages"
            setTextColor(Color.WHITE); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        wrap.addView(TextView(this).apply {
            text = "Paramètres Android, Administrateur et réglages protégés.\nTu peux combattre les Sorcières, ou tapoter le démon 20× + code 100205 pour déverrouiller."
            setTextColor(Color.rgb(185, 185, 200)); textSize = 12.8f; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(18))
        })

        var tapCounter = 0
        var lastTapMs = 0L

        val petView = DemonPetView(
            context = this,
            onDragMove = { _, _ -> },
            onDragRelease = { _, _, wasTap ->
                if (wasTap) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapMs > 1800L) tapCounter = 1 else tapCounter++
                    lastTapMs = now
                    lockedTapHintTv?.text = when {
                        tapCounter >= DemonSettings.REQUIRED_EXIT_TAPS -> "🔓 Saisis le code !"
                        tapCounter >= 5 -> "Tapotes : $tapCounter / ${DemonSettings.REQUIRED_EXIT_TAPS}…"
                        else -> "Tapote le démon 20× de suite pour débloquer"
                    }
                }
            },
            onGentlePet = {},
            onSecretTwentyTaps = { showExitPinDialog() }
        ).apply {
            updateConfig(snap)
            pose = DemonPetView.PetPose.WALKING
            say("Tu ne peux pas fuir le Coven ! 😈", 4_000L)
        }
        lockedPetAnimView = petView

        val petCard = FrameLayout(this).apply {
            background = roundedBg(CARD_DARK, 28, strokeColor = Color.argb(145, 217, 70, 239), strokeDp = 2)
            addView(petView, FrameLayout.LayoutParams(dp(200), dp(228), Gravity.CENTER))
        }
        wrap.addView(petCard, LinearLayout.LayoutParams(dp(230), dp(250)))

        wrap.addView(makeAccentButton("⚔️ Lancer un Duel Sorcière (Mode Verrouillé)", Color.rgb(162, 28, 175)) {
            startInAppDuel(WitchCatalog.randomWitchForChapter(rpgState.selectedChapter, includeBoss = true))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) }
        })

        val hintTv = TextView(this).apply {
            text = "Tapote le démon 20× de suite pour débloquer"
            setTextColor(Color.rgb(253, 164, 175)); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(6))
        }
        lockedTapHintTv = hintTv
        wrap.addView(hintTv)

        wrap.addView(TextView(this).apply {
            text = "NV ${snap.playerLevel} • ${snap.mood.emoji} ${snap.mood.label} • Marche aussi sur le démon flottant"
            setTextColor(Color.rgb(145, 145, 162)); textSize = 11.5f; gravity = Gravity.CENTER
        })
        return wrap
    }

    private fun showExitPinDialog() {
        val input = EditText(this).apply {
            hint = "Code secret (100205)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(Color.WHITE); setHintTextColor(Color.rgb(145, 145, 162))
            setPadding(dp(14), dp(12), dp(14), dp(12)); background = roundedBg(Color.rgb(30, 27, 42), 12)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(14), dp(20), dp(8)); setBackgroundColor(Color.rgb(20, 18, 28))
            addView(TextView(this@MainActivity).apply {
                text = "Saisis le code pour déverrouiller les Paramètres, dégriser 100 % des applis et mettre le démon en pause :"
                setTextColor(Color.rgb(225, 225, 234)); textSize = 13f; setPadding(0, 0, 0, dp(12))
            })
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("🔓 Déverrouillage DemonMom RPG")
            .setView(box)
            .setPositiveButton("Déverrouiller") { _, _ ->
                if (settings.verifyExitPin(input.text?.toString().orEmpty())) performFullUnlock()
                else {
                    Toast.makeText(this, "Mauvais code ! 😈", Toast.LENGTH_SHORT).show()
                    lockedPetAnimView?.say("Raté ! Mauvais code 😈", 3_000L)
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun performFullUnlock() {
        Thread {
            val msg = DemonMdmManager.restoreEverything(applicationContext, releaseDeviceOwner = false)
            mainHandler.post {
                DemonDeviceAdminReceiver.removeAdminSafely(applicationContext)
                DemonAccessibilityService.activeInstance?.performSecretExitUnlock()
                settings.update {
                    putBoolean("challenge_locked", false)
                    putBoolean("strict_settings_lock", false)
                    putBoolean("enabled", false)
                    putInt("active_drain_pct", 0)
                    putInt("player_hp", 100)
                }
                DemonCompanionService.stop(applicationContext)
                Toast.makeText(this, "🔓 Code 100205 accepté ! $msg", Toast.LENGTH_LONG).show()
                renderScreen(settings.snapshot())
            }
        }.start()
    }

    private fun performEndGameRestoreAll() {
        Toast.makeText(this, "Restauration complète du téléphone en cours…", Toast.LENGTH_SHORT).show()
        Thread {
            val msg = DemonMdmManager.restoreEverything(applicationContext, releaseDeviceOwner = false)
            mainHandler.post {
                settings.update {
                    putInt("player_level", 20)
                    putInt("player_hp", 100)
                    putInt("active_drain_pct", 0)
                    putInt("mood_score", 30)
                }
                clearWindowBrightnessDrain()
                Toast.makeText(this, "🕊️ Fin du jeu ! $msg", Toast.LENGTH_LONG).show()
                renderActiveTab(settings.snapshot())
            }
        }.start()
    }

    private fun handleShizukuAction() {
        DemonShizukuBridge.init(applicationContext)
        when {
            DemonShizukuBridge.hasShizukuPermission() -> {
                Thread {
                    DemonShizukuBridge.ensureBound()
                    DemonShizukuBridge.autoGrantSafeAppOps()
                    mainHandler.post {
                        Toast.makeText(this, "⚡ Shizuku connecté ! ${DemonShizukuBridge.getDiagnostics(this)}", Toast.LENGTH_LONG).show()
                        renderActiveTab(settings.snapshot())
                    }
                }.start()
            }
            DemonShizukuBridge.isShizukuRunning() -> {
                DemonShizukuBridge.requestShizukuPermission()
            }
            DemonShizukuBridge.isShizukuInstalled(this) -> {
                Toast.makeText(this, "Lance Shizuku via « Débogage sans fil » (statut: en cours), puis reviens ici !", Toast.LENGTH_LONG).show()
                DemonShizukuBridge.openShizukuApp(this)
            }
            else -> {
                Toast.makeText(this, "Installe d'abord l'app gratuite Shizuku (Play Store ou GitHub) !", Toast.LENGTH_LONG).show()
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases/latest"))) } catch (_: Throwable) {}
            }
        }
    }

    private fun copyTermuxMdmScript() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("DemonMom Termux MDM", DemonMdmManager.buildTermuxOneLineScript()))
        Toast.makeText(this, "📋 Script Termux copié !", Toast.LENGTH_LONG).show()
    }

    // -------------------------------------------------------------------------
    // 4-TAB FULL RPG GAME SHELL
    // -------------------------------------------------------------------------
    private fun buildTabbedShell(snap: DemonConfigSnapshot): View {
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_DARK)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        header.addView(TextView(this).apply {
            text = "🔮 DemonMom RPG"
            setTextColor(Color.WHITE); textSize = 19.5f; typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val rSnap = rpgState.snapshot()
        val pill = TextView(this).apply {
            text = "NV ${snap.playerLevel} • 💎 ${rSnap.gold}"
            setTextColor(Color.WHITE); textSize = 11.8f; typeface = Typeface.DEFAULT_BOLD
            background = roundedBg(Color.rgb(42, 20, 54), 14, strokeColor = Color.argb(165, 217, 70, 239), strokeDp = 1)
            setPadding(dp(11), dp(5), dp(11), dp(5))
            setOnClickListener {
                activeTab = 2; updateTabBarStyles(); renderActiveTab(settings.snapshot())
            }
        }
        headerPillTv = pill
        header.addView(pill)
        shell.addView(header)

        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(2), dp(12), dp(10))
        }
        tabButtons.clear()
        val titles = listOf("🗺️ Aventure", "📖 Grimoire", "⚒️ Forge", "😈 Démon", "⚙️ Syst.")
        titles.forEachIndexed { idx, label ->
            val b = Button(this).apply {
                text = label; isAllCaps = false; textSize = 10.0f; typeface = Typeface.DEFAULT_BOLD
                minHeight = 0; minimumHeight = 0; setPadding(dp(2), dp(8), dp(2), dp(8))
                setOnClickListener {
                    activeTab = idx; updateTabBarStyles(); renderActiveTab(settings.snapshot())
                }
            }
            tabButtons.add(b)
            tabBar.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (idx > 0) marginStart = dp(4)
            })
        }
        shell.addView(tabBar)

        val container = FrameLayout(this)
        tabContentContainer = container
        shell.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        updateTabBarStyles()
        return shell
    }

    private fun updateTabBarStyles() {
        tabButtons.forEachIndexed { idx, b ->
            val sel = idx == activeTab
            b.background = roundedBg(
                if (sel) Color.rgb(162, 28, 175) else Color.rgb(25, 21, 38), 12,
                strokeColor = if (sel) Color.rgb(240, 171, 252) else Color.argb(35, 255, 255, 255), strokeDp = 1
            )
            b.setTextColor(if (sel) Color.WHITE else Color.rgb(175, 175, 195))
        }
    }

    private fun renderActiveTab(snap: DemonConfigSnapshot) {
        val box = tabContentContainer ?: return
        updateHeaderPill(snap)
        mainHandler.removeCallbacks(animTick)
        previewPetView = null
        box.removeAllViews()

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(28))
        }
        scroll.addView(inner)

        when (activeTab) {
            0 -> populateAdventureTab(inner, snap)
            1 -> populateGrimoireTab(inner)
            2 -> populateForgeTab(inner, snap)
            3 -> populateDemonSanctumTab(inner, snap)
            4 -> populateFolderAndAiTab(inner, snap)
        }
        box.addView(scroll)
        if (previewPetView != null) mainHandler.post(animTick)
        refreshMediaSummaryAsync(snap)
    }

    private fun obedienceRankFor(level: Int): String = when {
        level >= 25 -> "Rebelle"
        level >= 15 -> "Apprenti"
        level >= 8 -> "Captif"
        level >= 3 -> "Serviteur"
        else -> "Soumis Absolu"
    }

    // ==================== TAB 0 : 🗺️ AVENTURE (WORLD MAP, TOWER, QUESTS, SHIZUKU) ====================
    private fun populateAdventureTab(root: LinearLayout, snap: DemonConfigSnapshot) {
        val rSnap = rpgState.snapshot()

        // 1. Player Hero RPG Card
        val hero = makeCard(accentStroke = Color.argb(145, 217, 70, 239))
        val heroRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

        val pv = DemonPetView(
            context = this, onDragMove = { _, _ -> }, onDragRelease = { _, _, _ -> },
            onGentlePet = { settings.adjustMood(-5); rpgState.addDemonCare(+3, 0, +4) }, onSecretTwentyTaps = {}
        ).apply { evolutionTier = rSnap.demonForm.index; updateConfig(snap); pose = DemonPetView.PetPose.WALKING }
        previewPetView = pv
        heroRow.addView(pv, LinearLayout.LayoutParams(dp(94), dp(114)))

        val statsCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11), 0, 0, 0) }
        statsCol.addView(TextView(this).apply {
            text = "NV ${snap.playerLevel} · ${obedienceRankFor(snap.playerLevel)}"
            setTextColor(Color.WHITE); textSize = 17f; typeface = Typeface.DEFAULT_BOLD
        })
        statsCol.addView(TextView(this).apply {
            text = "PV ${rSnap.maxHp} • PM ${rSnap.maxMp} • ATK ×${String.format("%.2f", rSnap.attackMultiplier)} • 💎 ${rSnap.gold}"
            setTextColor(Color.rgb(240, 171, 252)); textSize = 11.2f; typeface = Typeface.MONOSPACE; setPadding(0, dp(3), 0, dp(4))
        })

        val xpBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = rSnap.xp.coerceIn(0, 100)
            progressTintList = ColorStateList.valueOf(Color.rgb(217, 70, 239))
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(42, 34, 58))
        }
        statsCol.addView(xpBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7)).apply { bottomMargin = dp(5) })

        statsCol.addView(TextView(this).apply {
            text = "🌀 Esprits ${rSnap.unlockedSpirits.size}/4 • 🏅 Titres ${rSnap.unlockedTitleIds.size}/24 • ${rSnap.alignmentLabel}\n" +
                "📖 ${rSnap.defeatedIds.count { it in 1..50 }}/50 • 🌑 Superboss ${rSnap.defeatedSuperbossIds.size}/5 • 🏰 Donjons ${rSnap.dungeonsCompleted}"
            setTextColor(Color.rgb(180, 180, 202)); textSize = 10.9f
        })
        heroRow.addView(statsCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        hero.addView(heroRow)

        if (rSnap.canClaimDailyToday) {
            hero.addView(makeAccentButton("🎁 Réclamer Pacte Quotidien (Jour ${(rSnap.dailyStreak % 7) + 1}/7 · Or + Relique !)", Color.rgb(217, 119, 6)) {
                val res = rpgState.claimDailyGift()
                if (res != null) {
                    Toast.makeText(this, res, Toast.LENGTH_LONG).show()
                    renderActiveTab(settings.snapshot())
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            })
        }

        if (snap.mediaFolderPath.isBlank() && snap.mediaTreeUri.isBlank()) {
            hero.addView(makeAccentButton("📂 Choisir ton dossier photos pour les 55 Sorcières →", Color.rgb(126, 34, 206)) {
                activeTab = 4; updateTabBarStyles(); renderActiveTab(settings.snapshot())
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            })
        }
        root.addView(hero)

        // 2. 5-Chapter World Map + Infinite Witch Tower Card
        val chCard = makeCard(accentStroke = Color.argb(135, 244, 63, 94))
        chCard.addView(sectionTitle("🗺️ Campagne du Coven (5 Chapitres · 50 Sorcières)"))

        val curCh = rSnap.selectedChapter.coerceIn(1, 5)
        val chRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(10)) }
        WitchCatalog.CHAPTERS.forEachIndexed { idx, ch ->
            val unlocked = ch.id <= rSnap.unlockedChapter
            val selected = ch.id == curCh
            val btn = Button(this).apply {
                text = if (unlocked) "Ch.${ch.id}" else "🔒${ch.id}"
                isAllCaps = false; textSize = 11.5f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(when { selected -> Color.WHITE; unlocked -> Color.rgb(225, 210, 245); else -> Color.rgb(125, 125, 145) })
                background = roundedBg(
                    when { selected -> Color.rgb(225, 29, 72); unlocked -> Color.rgb(36, 26, 52); else -> Color.rgb(22, 19, 32) }, 10,
                    strokeColor = if (selected) Color.rgb(253, 164, 175) else Color.argb(40, 255, 255, 255), strokeDp = 1
                )
                minHeight = 0; minimumHeight = 0; setPadding(dp(2), dp(8), dp(2), dp(8))
                setOnClickListener {
                    if (!unlocked) {
                        val prevBoss = WitchCatalog.bossOfChapter(ch.id - 1)
                        Toast.makeText(this@MainActivity, "Bats ${prevBoss.name} (Boss Ch.${ch.id - 1}) pour débloquer le Chapitre ${ch.id} !", Toast.LENGTH_LONG).show()
                    } else {
                        rpgState.selectedChapter = ch.id; grimoireFilter = ch.id; renderActiveTab(settings.snapshot())
                    }
                }
            }
            chRow.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (idx > 0) marginStart = dp(5) })
        }
        chCard.addView(chRow)

        val chInfo = WitchCatalog.CHAPTERS[curCh - 1]
        val chDefeated = WitchCatalog.witchesInChapter(curCh).count { rSnap.defeatedIds.contains(it.id) }
        val chBoss = WitchCatalog.bossOfChapter(curCh)
        val bossBeat = rSnap.defeatedIds.contains(chBoss.id)

        val chDetail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(Color.rgb(28, 18, 42), 14, strokeColor = Color.argb(75, 217, 70, 239), strokeDp = 1)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        chDetail.addView(TextView(this).apply {
            text = "Chapitre $curCh : ${chInfo.name} (NV ${chInfo.minLevel}–${chInfo.maxLevel})"
            setTextColor(Color.WHITE); textSize = 14.3f; typeface = Typeface.DEFAULT_BOLD
        })
        chDetail.addView(TextView(this).apply {
            text = "${chInfo.subtitle}\nProgression : $chDefeated/10 vaincues • Boss : ${chBoss.name} ${if (bossBeat) "✅" else "👑"}"
            setTextColor(Color.rgb(208, 196, 228)); textSize = 11.6f; setPadding(0, dp(3), 0, 0)
        })
        chCard.addView(chDetail)

        val storyInCh = rSnap.storyStep in ((curCh - 1) * 3)..((curCh - 1) * 3 + 2)
        val exploreLabel = if (storyInCh) {
            "📜 EXPLORER — SUITE DE L'INTRIGUE (Ch.$curCh)"
        } else {
            "🎲 EXPLORER — HASARD, COFFRES & DUEL (Ch.$curCh)"
        }
        chCard.addView(makeAccentButton(exploreLabel, Color.rgb(225, 29, 72)) {
            launchChapterExploration(curCh)
        }.apply {
            textSize = 13.4f; setPadding(dp(12), dp(13), dp(12), dp(13))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        })

        chCard.addView(makeAccentButton("🏰 EXPÉDITION DONJON 5 SALLES (Ch.$curCh · Coffre Légendaire)", Color.rgb(14, 116, 144)) {
            startChapterDungeonExpedition(curCh)
        }.apply {
            textSize = 12.6f; setPadding(dp(12), dp(11), dp(12), dp(11))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        })

        chCard.addView(makeAccentButton("👑 DÉFIER LE BOSS : ${chBoss.name} (NV ${chBoss.level})", Color.rgb(162, 28, 175)) {
            startInAppDuel(chBoss)
        }.apply {
            textSize = 12.6f; setPadding(dp(12), dp(11), dp(12), dp(11))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        })

        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        modeRow.addView(makeAccentButton("🗼 Tour Infinie (#${rSnap.towerBestFloor})", Color.rgb(180, 83, 9)) {
            startInfiniteTower()
        }, gridLp(endMargin = dp(5)))
        modeRow.addView(makeGridTestBtn("🎲 Aléatoire (1–50)") {
            startInAppDuel(WitchCatalog.ALL_WITCHES.random())
        }, gridLp(startMargin = dp(5)))
        chCard.addView(modeRow)
        root.addView(chCard)

        // 2.5. Secret Endgame: 5 Awakened Eclipse Superbosses (#51-55)
        val superCard = makeCard(accentStroke = Color.argb(165, 250, 204, 21))
        superCard.addView(sectionTitle("🌑 Épreuves Secrètes — Les 5 Superboss (${rSnap.defeatedSuperbossIds.size}/5)"))
        superCard.addView(sectionSub("Combats légendaires très longs (1320–1980 Résolution) nécessitant les 4 Esprits & la Forge !"))
        for (sb in WitchCatalog.SUPERBOSSES) {
            val beat = rSnap.defeatedSuperbossIds.contains(sb.id)
            val sRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = roundedBg(Color.rgb(28, 18, 42), 11); setPadding(dp(9), dp(7), dp(9), dp(7))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) }
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = "${if (beat) "✅" else "🌑"} ${sb.name} (NV ${sb.level} · ${sb.element})"
                setTextColor(Color.rgb(254, 240, 138)); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            })
            col.addView(TextView(this).apply {
                text = "Résol. ${sb.maxResolve} • +${sb.rewardGold} 💎 • Ultime : ${sb.ultimateName}"
                setTextColor(Color.rgb(185, 175, 205)); textSize = 10.4f
            })
            sRow.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            sRow.addView(Button(this).apply {
                text = "⚔️ Défier"; isAllCaps = false; textSize = 10.8f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                background = roundedBg(Color.rgb(159, 18, 57), 9); minHeight = 0; minimumHeight = 0; setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { startInAppDuel(sb) }
            })
            superCard.addView(sRow)
        }
        root.addView(superCard)

        // 2.8. 12 Tactical Bounties Card
        val openBounties = WitchLoreAndCampaign.BOUNTIES.filter { !rSnap.claimedBounties.contains(it.id) }
        if (openBounties.isNotEmpty()) {
            val bCard = makeCard(accentStroke = Color.argb(135, 14, 165, 233))
            bCard.addView(sectionTitle("🎯 Contrats Tactiques du Coven (${rSnap.claimedBounties.size}/12)"))
            for (b in openBounties.take(4)) {
                val ready = rSnap.completedBounties.contains(b.id)
                val bRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = roundedBg(Color.rgb(24, 22, 42), 11); setPadding(dp(9), dp(7), dp(9), dp(7))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) }
                }
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(this).apply {
                    text = "${if (ready) "✅" else "🎯"} ${b.title}"
                    setTextColor(Color.WHITE); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
                })
                col.addView(TextView(this).apply {
                    text = "${b.objective}\n🎁 +${b.rewardGold} 💎 + ${b.rewardRelicName}"
                    setTextColor(Color.rgb(180, 210, 235)); textSize = 10.5f
                })
                bRow.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (ready) {
                    bRow.addView(Button(this).apply {
                        text = "🎁 Réclamer"; isAllCaps = false; textSize = 10.6f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                        background = roundedBg(Color.rgb(16, 128, 96), 9); minHeight = 0; minimumHeight = 0; setPadding(dp(9), dp(6), dp(9), dp(6))
                        setOnClickListener {
                            if (rpgState.claimBounty(b.id)) {
                                Toast.makeText(this@MainActivity, "🎁 Contrat validé : +${b.rewardGold} 💎 + ${b.rewardRelicName} !", Toast.LENGTH_LONG).show()
                                renderActiveTab(settings.snapshot())
                            }
                        }
                    })
                }
                bCard.addView(bRow)
            }
            root.addView(bCard)
        }

        // 3. Claimable RPG Quests Card
        val quests = rpgState.getQuests().filter { !it.claimed }
        if (quests.isNotEmpty()) {
            val qCard = makeCard(accentStroke = Color.argb(120, 245, 158, 11))
            qCard.addView(sectionTitle("🏆 Quêtes & Défis Tactiques"))
            for (q in quests.take(4)) {
                val qRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = roundedBg(Color.rgb(28, 22, 42), 11); setPadding(dp(10), dp(7), dp(10), dp(7))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
                }
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(this).apply {
                    text = "${q.title} (${q.currentProgress.coerceAtMost(q.targetProgress)}/${q.targetProgress})"
                    setTextColor(Color.WHITE); textSize = 12.2f; typeface = Typeface.DEFAULT_BOLD
                })
                col.addView(TextView(this).apply {
                    text = "Récompense : ${q.rewardText}"
                    setTextColor(Color.rgb(253, 224, 71)); textSize = 11f
                })
                qRow.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (q.completed) {
                    qRow.addView(Button(this).apply {
                        text = "🎁 Réclamer"
                        isAllCaps = false; textSize = 11f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                        background = roundedBg(Color.rgb(16, 128, 96), 9); minHeight = 0; minimumHeight = 0; setPadding(dp(10), dp(6), dp(10), dp(6))
                        setOnClickListener {
                            if (rpgState.claimQuest(q.id)) {
                                Toast.makeText(this@MainActivity, "🎁 Quête réclamée : ${q.rewardText} !", Toast.LENGTH_SHORT).show()
                                renderActiveTab(settings.snapshot())
                            }
                        }
                    })
                }
                qCard.addView(qRow)
            }
            root.addView(qCard)
        }

        // 4. Shizuku Live System Punishments & Instant Test Card
        val sysCard = makeCard(accentStroke = Color.argb(125, 16, 185, 129))
        val privOk = DemonMdmManager.hasPrivilegedControl(this)
        val susp = DemonMdmManager.getSuspendedPackages(this)
        val hid = DemonMdmManager.getHiddenPackages(this)
        val curses = DemonMdmManager.getActiveCurses(this)

        sysCard.addView(sectionTitle("⚡ Punitions Système (Shizuku Android 16)"))
        sysCard.addView(TextView(this).apply {
            text = "${DemonShizukuBridge.getDiagnostics(this@MainActivity)}\n" +
                "• ${susp.size + hid.size} appli(s) grisée(s) par ordre d'usage • ${curses.size} sceau(x)\n" +
                "• Victoire = +1 appli libérée • Restauration 100 % garantie !"
            setTextColor(if (privOk) Color.rgb(167, 243, 208) else Color.rgb(253, 186, 116))
            textSize = 11.4f; setPadding(0, dp(4), 0, dp(8))
        })

        val sysRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (!privOk) {
            sysRow.addView(makeAccentButton("⚡ Autoriser Shizuku", Color.rgb(126, 34, 206)) {
                handleShizukuAction()
            }, gridLp(endMargin = dp(5)))
        } else {
            sysRow.addView(makeAccentButton("🧪 Tester (Griser 1 app)", Color.rgb(2, 132, 199)) {
                runShizukuSuspendTest()
            }, gridLp(endMargin = dp(5)))
        }
        sysRow.addView(makeAccentButton("🕊️ Tout restaurer", Color.rgb(16, 128, 96)) {
            performEndGameRestoreAll()
        }, gridLp(startMargin = dp(5)))
        sysCard.addView(sysRow)
        root.addView(sysCard)
    }

    // ==================== TAB 1 : 📖 GRIMOIRE (55 WITCHES, BONDS, LORE & 24 TITLES!) ====================
    private fun populateGrimoireTab(root: LinearLayout) {
        val rSnap = rpgState.snapshot()

        val headCard = makeCard(accentStroke = Color.argb(120, 217, 70, 239))
        headCard.addView(sectionTitle("📖 Grimoire des 55 Sorcières & 24 Titres"))
        headCard.addView(sectionSub(
            "${rSnap.defeatedIds.count { it in 1..50 }}/50 Sorcières • ${rSnap.defeatedSuperbossIds.size}/5 Superboss • ♥ ${rSnap.totalBondLevels} Liens d'Âme • 🏅 ${rSnap.unlockedTitleIds.size}/24 Titres\n" +
                "10 attaques MGQ par Sorcière + Histoire complète & 5 chapitres de Lien d'Âme déblocables !"
        ))

        val filterRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(5)) }
        listOf(1 to "Ch.1", 2 to "Ch.2", 3 to "Ch.3", 4 to "Ch.4").forEachIndexed { idx, (code, label) ->
            filterRow1.addView(makeFilterChip(label, grimoireFilter == code) {
                grimoireFilter = code; renderActiveTab(settings.snapshot())
            }, gridLp(startMargin = if (idx > 0) dp(3) else 0, endMargin = if (idx < 3) dp(3) else 0))
        }
        headCard.addView(filterRow1)

        val filterRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(5 to "Ch.5", 0 to "👑 Boss", 6 to "🌑 Super", 7 to "🏅 Titres").forEachIndexed { idx, (code, label) ->
            filterRow2.addView(makeFilterChip(label, grimoireFilter == code) {
                grimoireFilter = code; renderActiveTab(settings.snapshot())
            }, gridLp(startMargin = if (idx > 0) dp(3) else 0, endMargin = if (idx < 3) dp(3) else 0))
        }
        headCard.addView(filterRow2)
        root.addView(headCard)

        if (grimoireFilter == 7) {
            val tSummary = makeCard(accentStroke = Color.argb(150, 245, 158, 11))
            tSummary.addView(sectionTitle("🏅 Bonus Cumulés de tes Titres (${rSnap.unlockedTitleIds.size}/24)"))
            tSummary.addView(sectionSub("+${rSnap.titleAtkPct} % ATK • +${rSnap.titleHpBonus} PV • +${rSnap.titleMpBonus} PM • +${rSnap.titlePostureBonus} Posture • +${rSnap.titleGoldPct} % 💎"))
            root.addView(tSummary)
            for (t in WitchLoreAndCampaign.TITLES) {
                val ok = rSnap.unlockedTitleIds.contains(t.id)
                val c = makeCard(accentStroke = if (ok) Color.argb(140, 16, 185, 129) else Color.argb(35, 255, 255, 255))
                c.addView(TextView(this).apply {
                    text = "${if (ok) "✅" else "🔒"} ${t.name}  —  ${t.bonusDesc}"
                    setTextColor(if (ok) Color.rgb(167, 243, 208) else Color.rgb(165, 165, 185))
                    textSize = 12.6f; typeface = Typeface.DEFAULT_BOLD
                })
                c.addView(TextView(this).apply {
                    text = "Condition : ${t.requirement}"
                    setTextColor(Color.rgb(145, 145, 168)); textSize = 11f
                })
                root.addView(c)
            }
            return
        }

        val list = when (grimoireFilter) {
            0 -> WitchCatalog.ALL_WITCHES.filter { it.isBoss || it.isMiniBoss }
            6 -> WitchCatalog.SUPERBOSSES
            else -> WitchCatalog.witchesInChapter(grimoireFilter)
        }

        for (w in list) {
            val defeated = rSnap.defeatedIds.contains(w.id)
            val lostTo = rSnap.lostToIds.contains(w.id)
            val seen = rSnap.encounteredIds.contains(w.id)
            val bondLv = rpgState.getBondLevel(w.id)
            val recSp = WitchCombatSystem.recommendedSpiritFor(w)

            val stroke = when {
                w.isSuperboss -> Color.argb(215, 250, 204, 21)
                w.isBoss -> Color.argb(190, 245, 158, 11)
                w.isMiniBoss -> Color.argb(165, 244, 63, 94)
                defeated -> Color.argb(120, 16, 185, 129)
                else -> Color.argb(65, 217, 70, 239)
            }
            val card = makeCard(accentStroke = stroke)

            val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val statusIcon = when { defeated -> "✅ ♥$bondLv/5"; lostTo -> "💋 ♥$bondLv/5"; seen -> "👁️ Vue"; else -> "❓" }
            topRow.addView(TextView(this).apply {
                text = "#${w.id} ${w.displayTitle}"
                setTextColor(if (w.isBoss || w.isSuperboss) Color.rgb(253, 224, 71) else Color.WHITE); textSize = 13.4f; typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            topRow.addView(TextView(this).apply {
                text = statusIcon
                setTextColor(when { defeated -> Color.rgb(167, 243, 208); lostTo -> Color.rgb(244, 114, 182); else -> Color.rgb(185, 185, 205) })
                textSize = 11.2f; typeface = Typeface.DEFAULT_BOLD
            })
            card.addView(topRow)

            card.addView(TextView(this).apply {
                text = "NV ${w.level} • ${w.archetype.label} • ${w.element} • Esprit : ${recSp.shortBadge} • Résol. ${w.maxResolve}"
                setTextColor(Color.rgb(208, 198, 232)); textSize = 11f; setPadding(0, dp(3), 0, dp(4))
            })
            card.addView(TextView(this).apply {
                text = "Ultime : « ${w.ultimateName} »\n💡 ${w.weaknessTip}"
                setTextColor(Color.rgb(156, 156, 178)); textSize = 10.9f; setPadding(0, 0, 0, dp(8))
            })

            val actRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actRow.addView(makeGridTestBtn("📖 Histoire & Liens") {
                showWitchLoreDialog(w)
            }, gridLp(endMargin = dp(4)))
            actRow.addView(makeAccentButton(
                if (w.isBoss || w.isSuperboss) "👑 Défier" else "⚔️ Combattre",
                if (w.isSuperboss) Color.rgb(159, 18, 57) else if (w.isBoss) Color.rgb(180, 83, 9) else Color.rgb(126, 34, 206)
            ) {
                startInAppDuel(w)
            }, gridLp(startMargin = dp(4)))
            card.addView(actRow)
            root.addView(card)
        }
    }

    private fun makeFilterChip(label: String, active: Boolean, onClick: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 11.5f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (active) Color.WHITE else Color.rgb(190, 190, 210))
        background = roundedBg(
            if (active) Color.rgb(225, 29, 72) else Color.rgb(30, 26, 44), 10,
            strokeColor = if (active) Color.rgb(253, 164, 175) else Color.argb(35, 255, 255, 255), strokeDp = 1
        )
        minHeight = 0; minimumHeight = 0; setPadding(dp(4), dp(7), dp(4), dp(7))
        setOnClickListener { onClick() }
    }

    // ==================== TAB 2 : ⚒️ 4 ESPRITS ÉLÉMENTAIRES, FORGE 6 SLOTS (Nv.1-15) & 10 RELIQUES ====================
    private fun populateForgeTab(root: LinearLayout, snap: DemonConfigSnapshot) {
        val rSnap = rpgState.snapshot()

        // 0. MGQ-Style 4 Elemental Spirits Sanctuary
        val spCard = makeCard(accentStroke = Color.argb(155, 14, 165, 233))
        spCard.addView(sectionTitle("🌀 Sanctuaire des 4 Esprits Élémentaires (${rSnap.unlockedSpirits.size}/4)"))
        spCard.addView(sectionSub("Invoquables en plein duel (menu 🌀 Esprits & Magie) : passifs continus, faiblesses ×1.55 et Surcharge 100 SP !"))
        val allSpirits = listOf(
            WitchCombatSystem.ElementalSpirit.ZEPHYR,
            WitchCombatSystem.ElementalSpirit.GAIA,
            WitchCombatSystem.ElementalSpirit.ONDINE,
            WitchCombatSystem.ElementalSpirit.IGNIS
        )
        for (sp in allSpirits) {
            val unlocked = rSnap.unlockedSpirits.contains(sp.id)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = roundedBg(Color.rgb(24, 22, 42), 12); setPadding(dp(10), dp(7), dp(10), dp(7))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) }
            }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(TextView(this).apply {
                text = "${if (unlocked) "✅" else "🔒"} ${sp.displayName}  (-${(sp.mpCost - rSnap.spiritMpDiscount).coerceAtLeast(6)} PM)"
                setTextColor(Color.WHITE); textSize = 12.6f; typeface = Typeface.DEFAULT_BOLD
            })
            info.addView(TextView(this).apply {
                text = if (unlocked) sp.passiveDesc else "${sp.passiveDesc}\n${sp.unlockHint}"
                setTextColor(Color.rgb(175, 195, 220)); textSize = 10.6f
            })
            row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (!unlocked) {
                row.addView(Button(this).apply {
                    text = "Éveiller 260 💎"; isAllCaps = false; textSize = 10.6f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                    background = roundedBg(if (rSnap.gold >= 260) sp.colorHex else Color.rgb(52, 48, 64), 10)
                    minHeight = 0; minimumHeight = 0; setPadding(dp(9), dp(7), dp(9), dp(7))
                    setOnClickListener {
                        if (rpgState.unlockSpirit(sp.id, goldCost = 260)) {
                            Toast.makeText(this@MainActivity, "🌀 ${sp.displayName} éveillé !", Toast.LENGTH_LONG).show()
                            renderActiveTab(settings.snapshot())
                        } else {
                            Toast.makeText(this@MainActivity, "Besoin de 260 💎 (ou bats le Boss correspondant) !", Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            }
            spCard.addView(row)
        }
        root.addView(spCard)

        // 1. 6 Permanent Soul Forge Upgrades (Rank 1..15!)
        val forgeCard = makeCard(accentStroke = Color.argb(145, 234, 88, 12))
        forgeCard.addView(sectionTitle("⚒️ Forge d'Âme — 6 Équipements Permanents (💎 ${rSnap.gold})"))
        forgeCard.addView(sectionSub("Renforce définitivement ton héros jusqu'au Nv.15 pour triompher des 5 Superboss & de la Tour !"))

        data class ForgeSlot(val key: String, val name: String, val desc: String, val rank: Int, val color: Int)
        val slots = listOf(
            ForgeSlot("blade", "🗡️ Lame de Volonté", "+12 % Dégâts (actuel ×${String.format("%.2f", rSnap.attackMultiplier)})", rSnap.bladeRank, Color.rgb(225, 29, 72)),
            ForgeSlot("cloak", "🛡️ Manteau Runique", "+16 PV Max & -3,5 % Emprise (PV Max ${rSnap.maxHp})", rSnap.cloakRank, Color.rgb(16, 128, 96)),
            ForgeSlot("grimoire", "🔮 Grimoire Intérieur", "+11 PM Max & +2 SP/tour (PM Max ${rSnap.maxMp})", rSnap.grimoireRank, Color.rgb(37, 99, 235)),
            ForgeSlot("ward", "🧿 Sceau Anti-Charme", "Résistance Envoûtements & fenêtre QTE", rSnap.wardRank, Color.rgb(126, 34, 206)),
            ForgeSlot("spirit_ring", "🌀 Anneau des Esprits", "+8 % puissance Esprits & -PM invocation (×${String.format("%.2f", rSnap.spiritPowerMult)})", rSnap.spiritRingRank, Color.rgb(2, 132, 199)),
            ForgeSlot("demon_heart", "😈 Cœur du Démon", "+Aide combat du Petit Démon (${(rSnap.demonAssistChance * 100).toInt()} %) & +20 💎 Focus", rSnap.demonHeartRank, Color.rgb(190, 24, 93))
        )

        for (slot in slots) {
            val cost = rpgState.forgeUpgradeCost(slot.rank)
            val isMax = slot.rank >= 15
            val canAfford = !isMax && rSnap.gold >= cost
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = roundedBg(Color.rgb(28, 22, 42), 12); setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(TextView(this).apply {
                text = "${slot.name}  [Nv.${slot.rank}/15]"
                setTextColor(Color.WHITE); textSize = 12.6f; typeface = Typeface.DEFAULT_BOLD
            })
            info.addView(TextView(this).apply {
                text = slot.desc; setTextColor(Color.rgb(175, 175, 198)); textSize = 10.8f
            })
            row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(Button(this).apply {
                text = if (isMax) "MAX ✅" else "Forger $cost 💎"
                isAllCaps = false; textSize = 10.8f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                background = roundedBg(if (canAfford) slot.color else Color.rgb(52, 48, 64), 10)
                minHeight = 0; minimumHeight = 0; setPadding(dp(10), dp(7), dp(10), dp(7))
                setOnClickListener {
                    if (isMax) return@setOnClickListener
                    if (rpgState.upgradeForge(slot.key)) {
                        Toast.makeText(this@MainActivity, "⚒️ ${slot.name} amélioré au Nv.${slot.rank + 1} !", Toast.LENGTH_SHORT).show()
                        renderActiveTab(settings.snapshot())
                    } else {
                        Toast.makeText(this@MainActivity, "Besoin de $cost 💎 !", Toast.LENGTH_SHORT).show()
                    }
                }
            })
            forgeCard.addView(row)
        }
        root.addView(forgeCard)

        // 2. 10 Consumable Relics Shop
        val shopCard = makeCard(accentStroke = Color.argb(135, 245, 158, 11))
        shopCard.addView(sectionTitle("🎒 Sac & Boutique des 10 Reliques"))
        data class ShopItem(val key: String, val title: String, val desc: String, val count: Int, val cost: Int, val color: Int)
        val items = listOf(
            ShopItem("elixir", "🧪 Élixir de Volonté", "+74 PV en combat", rSnap.elixirs, 35, Color.rgb(16, 128, 96)),
            ShopItem("crystal", "💎 Cristal de Mana", "+48 PM et +25 SP", rSnap.crystals, 30, Color.rgb(37, 99, 235)),
            ShopItem("talisman", "📜 Talisman de Rupture", "Brise instantanément Capture + -50 % Emprise", rSnap.talismans, 55, Color.rgb(180, 83, 9)),
            ShopItem("amulet", "🔮 Amulette de Clarté", "Purifie Charme, Hypnose, Siphon & Drain", rSnap.amulets, 45, Color.rgb(126, 34, 206)),
            ShopItem("incense", "🕯️ Encens de Furie", "Attaques ×1.6 pendant 3 tours", rSnap.incenses, 60, Color.rgb(190, 24, 93)),
            ShopItem("aegis", "🛡️ Voile d'Égide", "Absorbe 100 % du prochain assaut & sort système", rSnap.aegisVeils, 75, Color.rgb(14, 116, 144)),
            ShopItem("posture_bomb", "💣 Bombe Brise-Posture", "-75 Posture Boss + détruit Barrière & Épines", rSnap.postureBombs, 65, Color.rgb(234, 88, 12)),
            ShopItem("holy_water", "💧 Eau Bénite", "Remet l'Adaptation ennemie à 0 % & dissipe Météo", rSnap.holyWaters, 55, Color.rgb(2, 132, 199)),
            ShopItem("ether_elixir", "⚡ Élixir d'Éther", "Charge 100 SP instantanément (Surcharge !)", rSnap.etherElixirs, 110, Color.rgb(192, 38, 211)),
            ShopItem("hourglass", "⏳ Sablier de Répit", "Fige la Sorcière 1 tour + -35 % Emprise", rSnap.hourglasses, 95, Color.rgb(180, 83, 9))
        )

        for (shopItem in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = roundedBg(Color.rgb(28, 22, 42), 12); setPadding(dp(10), dp(7), dp(10), dp(7))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) }
            }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(TextView(this).apply {
                text = "${shopItem.title}  (x${shopItem.count})"
                setTextColor(Color.WHITE); textSize = 12.4f; typeface = Typeface.DEFAULT_BOLD
            })
            info.addView(TextView(this).apply {
                text = shopItem.desc; setTextColor(Color.rgb(175, 175, 196)); textSize = 10.8f
            })
            row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val canBuy = rSnap.gold >= shopItem.cost
            row.addView(Button(this).apply {
                text = "${shopItem.cost} 💎"
                isAllCaps = false; textSize = 11f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                background = roundedBg(if (canBuy) shopItem.color else Color.rgb(52, 48, 64), 10)
                minHeight = 0; minimumHeight = 0; setPadding(dp(12), dp(7), dp(12), dp(7))
                setOnClickListener {
                    if (rpgState.buyItem(shopItem.key, shopItem.cost)) {
                        Toast.makeText(this@MainActivity, "${shopItem.title} acheté !", Toast.LENGTH_SHORT).show()
                        renderActiveTab(settings.snapshot())
                    } else {
                        Toast.makeText(this@MainActivity, "Pas assez d'Éclats 💎 !", Toast.LENGTH_SHORT).show()
                    }
                }
            })
            shopCard.addView(row)
        }
        root.addView(shopCard)
    }

    // ==================== TAB 3 : 😈 SANCTUAIRE DU PETIT DÉMON (100% SHIZUKU + ACCESSIBILITÉ!) ====================
    private fun populateDemonSanctumTab(root: LinearLayout, snap: DemonConfigSnapshot) {
        val rSnap = rpgState.snapshot()

        // 1. Demon Evolution, Affection/Hunger Care & Mini-Games Card
        val evoCard = makeCard(accentStroke = Color.argb(175, 244, 63, 94))
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val pv = DemonPetView(
            context = this, onDragMove = { _, _ -> }, onDragRelease = { _, _, _ -> },
            onGentlePet = {
                settings.adjustMood(-6); rpgState.addDemonCare(+4, 0, +6)
                Toast.makeText(this, "💜 Affection +4 % !", Toast.LENGTH_SHORT).show()
            },
            onSecretTwentyTaps = {}
        ).apply { evolutionTier = rSnap.demonForm.index; updateConfig(snap); pose = DemonPetView.PetPose.WALKING }
        previewPetView = pv
        topRow.addView(pv, LinearLayout.LayoutParams(dp(92), dp(112)))

        val dCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        dCol.addView(TextView(this).apply {
            text = "${rSnap.demonForm.badge} ${snap.avatarName} — ${rSnap.demonForm.name}"
            setTextColor(Color.WHITE); textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD
        })
        dCol.addView(TextView(this).apply {
            text = "Forme ${rSnap.demonForm.index + 1}/4 • XP Démon : ${rSnap.demonXp}\n" +
                "💖 Affection : ${rSnap.demonAffection}% • 🍖 Faim : ${rSnap.demonHunger}% • 😈 Furie : ${snap.moodScore}%\n" +
                "⚡ Aide en duel : ${(rSnap.demonAssistChance * 100).toInt()} % — ${rSnap.demonForm.perkDesc}"
            setTextColor(Color.rgb(225, 200, 240)); textSize = 10.8f; setPadding(0, dp(3), 0, 0)
        })
        topRow.addView(dCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        evoCard.addView(topRow)

        val careRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        careRow.addView(makeAccentButton("💖 Caresser", Color.rgb(190, 24, 93)) {
            settings.adjustMood(-10); rpgState.addDemonCare(+6, 0, +10)
            Toast.makeText(this, "💜 ${snap.avatarName} ronronne ! Affection +6 %, Colère -10 % !", Toast.LENGTH_SHORT).show()
            renderActiveTab(settings.snapshot())
        }, gridLp(endMargin = dp(4)))
        careRow.addView(makeAccentButton("🍖 Nourrir (20💎)", Color.rgb(16, 128, 96)) {
            if (rSnap.gold >= 20) {
                rpgState.grantReward(-20); rpgState.addDemonCare(+8, +25, +24)
                Toast.makeText(this, "🍖 Miam ! +25 % Faim, +8 % Affection, +24 XP Démon !", Toast.LENGTH_SHORT).show()
                renderActiveTab(settings.snapshot())
            } else Toast.makeText(this, "Besoin de 20 💎 !", Toast.LENGTH_SHORT).show()
        }, gridLp(startMargin = dp(4)))
        evoCard.addView(careRow)

        val miniRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        miniRow.addView(makeGridTestBtn("🎲 Pierre-Feuille-Ciseaux") { showDemonRpsMiniGame() }, gridLp(endMargin = dp(4)))
        miniRow.addView(makeGridTestBtn("🧠 Quiz Grimoire (${rSnap.quizCorrectCount}✅)") { showGrimoireQuizDialog() }, gridLp(startMargin = dp(4)))
        evoCard.addView(miniRow)
        root.addView(evoCard)

        // 2. Shizuku Focus / Study Contract Card!
        val focusCard = makeCard(accentStroke = Color.argb(145, 2, 132, 199))
        focusCard.addView(sectionTitle("🎯 Mode Focus / Travail Shizuku (${rSnap.focusSessionsDone} réussi(s))"))
        val remFocusMs = rSnap.activeFocusUntilMs - System.currentTimeMillis()
        if (remFocusMs > 0L) {
            val remMin = ((remFocusMs + 59_999L) / 60_000L).toInt()
            focusCard.addView(sectionSub("⏳ Contrat Focus ACTIF : encore ~$remMin min ! Tes applis distrayantes sont grisées via Shizuku — +150 💎 à la fin !"))
            focusCard.addView(makeAccentButton("🕊️ Arrêter Focus maintenant (sans récompense)", Color.rgb(180, 83, 9)) {
                Thread {
                    val m = DemonMdmManager.finishShizukuFocusContract(applicationContext, awardRewards = false)
                    mainHandler.post { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); renderActiveTab(settings.snapshot()) }
                }.start()
            })
        } else {
            focusCard.addView(sectionSub("Met en pause tes 6 applis les plus chronophages via Shizuku et t'offre +150 💎 + XP Démon dès que le chrono termine !"))
            val fRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(15, 30, 60).forEachIndexed { idx, mins ->
                fRow.addView(makeAccentButton("⏱️ $mins min", Color.rgb(2, 132, 199)) {
                    Thread {
                        val m = DemonMdmManager.startShizukuFocusContract(applicationContext, mins)
                        mainHandler.post { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); renderActiveTab(settings.snapshot()) }
                    }.start()
                }, gridLp(startMargin = if (idx > 0) dp(4) else 0, endMargin = if (idx < 2) dp(4) else 0))
            }
            focusCard.addView(fRow)
        }
        root.addView(focusCard)

        // 3. Live Top-8 Most Used Apps Threat Radar (Shizuku UsageStats!)
        val radarCard = makeCard(accentStroke = Color.argb(135, 234, 88, 12))
        radarCard.addView(sectionTitle("📡 Radar des 8 Applis les Plus Utilisées (Shizuku)"))
        radarCard.addView(sectionSub("Ordre exact des cibles potentielles des Sorcières. Tu peux libérer un otage en 1 clic tant que le verrou Défi est OFF :"))
        val radarListBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        radarListBox.addView(TextView(this).apply {
            text = "⏳ Analyse UsageStats en cours…"; setTextColor(Color.rgb(180, 180, 200)); textSize = 11.2f
        })
        radarCard.addView(radarListBox)
        root.addView(radarCard)

        Thread {
            val threats = DemonMdmManager.getTopUsedAppsWithStatus(applicationContext, limit = 8)
            mainHandler.post {
                radarListBox.removeAllViews()
                if (threats.isEmpty()) {
                    radarListBox.addView(TextView(this).apply {
                        text = "Autorise Shizuku ou UsageStats dans ⚙️ Syst. pour voir ton top 8 !"; setTextColor(Color.rgb(253, 186, 116)); textSize = 11.2f
                    })
                } else {
                    threats.forEachIndexed { idx, app ->
                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                            background = roundedBg(if (app.isHostage) Color.rgb(55, 18, 34) else Color.rgb(26, 22, 40), 10)
                            setPadding(dp(9), dp(6), dp(9), dp(6))
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
                        }
                        row.addView(TextView(this).apply {
                            text = "#${idx + 1} ${app.label}  ${if (app.isHostage) "🔒 OTAGE GRISÉE" else if (idx == 0) "🎯 Cible #1" else "✅ Libre"}"
                            setTextColor(if (app.isHostage) Color.rgb(254, 202, 202) else Color.WHITE)
                            textSize = 11.5f; typeface = if (app.isHostage || idx == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        if (app.isHostage) {
                            row.addView(Button(this).apply {
                                text = "🕊️ Libérer"; isAllCaps = false; textSize = 10.2f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                                background = roundedBg(Color.rgb(16, 128, 96), 8); minHeight = 0; minimumHeight = 0; setPadding(dp(8), dp(4), dp(8), dp(4))
                                setOnClickListener {
                                    Thread {
                                        DemonMdmManager.releaseSpecificHostageApp(applicationContext, app.packageName)
                                        mainHandler.post { renderActiveTab(settings.snapshot()) }
                                    }.start()
                                }
                            })
                        }
                        radarListBox.addView(row)
                    }
                }
            }
        }.start()

        // 4. Witch Full Powers & Forced Fullscreen Controls Card
        val fullPowCard = makeCard(accentStroke = Color.argb(150, 217, 70, 239))
        fullPowCard.addView(sectionTitle("👑 Pleins Pouvoirs des Sorcières & Plein Écran (Shizuku)"))
        fullPowCard.addView(sectionSub("Contrôle les pouvoirs système Shizuku et tactiles des 55 Sorcières pendant les duels !"))
        fullPowCard.addView(makeSwitchRow("🔒 Plein Écran Immersif Forcé en combat (masque barres système)", rSnap.forcedFullscreen) {
            rpgState.forcedFullscreen = it
        })
        fullPowCard.addView(makeSwitchRow("📌 Épinglage Système Shizuku (am task lock — bloque Accueil/Récents)", rSnap.shizukuTaskPinning) {
            rpgState.shizukuTaskPinning = it
            if (it && !DemonMdmManager.isShizukuActive()) {
                Toast.makeText(this, "Autorise Shizuku dans ⚙️ Syst. pour l'épinglage système !", Toast.LENGTH_LONG).show()
            }
        })
        fullPowCard.addView(makeSwitchRow("🖥️ Forcer le combat en Overlay Système (bloque tout l'OS via Accessibilité)", rSnap.forcedOverlayBattle) {
            rpgState.forcedOverlayBattle = it
            if (it && !DemonAccessibilityService.isServiceActive()) {
                Toast.makeText(this, "Active l'Accessibilité dans ⚙️ Syst. pour le mode Overlay Système !", Toast.LENGTH_LONG).show()
            }
        })
        fullPowCard.addView(makeSwitchRow("⚡ Malédictions Shizuku EN PLEIN COMBAT (Otages, Ralenti ×3.5, Batterie 3%, Zoom, DND)", rSnap.midBattleAppPowers) {
            rpgState.midBattleAppPowers = it
        })
        fullPowCard.addView(makeSwitchRow("🖐️ Sabotage Tactile en combat (boutons fuyants, givre, leurres, brouillard)", rSnap.touchSabotageEnabled) {
            rpgState.touchSabotageEnabled = it
        })
        root.addView(fullPowCard)

        // 5. Living Demon Companion Controls (100% Shizuku + Accessibility!)
        val demonCard = makeCard(accentStroke = Color.argb(120, 244, 63, 94))
        demonCard.addView(sectionTitle("😈 Petit Démon Flottant (100 % Shizuku & Accessibilité)"))
        demonCard.addView(sectionSub("Double-tap sur le Petit Démon dans n'importe quelle appli pour son menu rapide ! Notifications réparées (zéro faux positifs)."))
        val sw = Switch(this).apply {
            isChecked = snap.enabled
            text = if (snap.enabled) "Compagnon Démon (Shizuku + Accessibilité) ACTIF" else "Activer le Petit Démon flottant"
            setTextColor(Color.WHITE); textSize = 13.4f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(4), 0, dp(8))
            setOnCheckedChangeListener { _, checked ->
                text = if (checked) "Compagnon Démon (Shizuku + Accessibilité) ACTIF" else "Activer le Petit Démon flottant"
                settings.enabled = checked
                if (checked) DemonCompanionService.start(applicationContext) else DemonCompanionService.stop(applicationContext)
            }
        }
        masterSwitch = sw
        demonCard.addView(sw)

        demonCard.addView(smallLabel("Sévérité des embuscades & farces (Shizuku + Accessibilité) :"))
        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        SeverityPreset.values().forEachIndexed { idx, preset ->
            val active = snap.severityPreset == preset
            val btn = Button(this).apply {
                text = "${preset.emoji}\n${preset.label}"
                isAllCaps = false; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (active) Color.WHITE else Color.rgb(195, 195, 210))
                background = roundedBg(
                    if (active) Color.rgb(225, 29, 72) else Color.rgb(32, 28, 46), 11,
                    strokeColor = if (active) Color.rgb(253, 164, 175) else Color.argb(35, 255, 255, 255), strokeDp = 1
                )
                minHeight = 0; minimumHeight = 0; setPadding(dp(4), dp(7), dp(4), dp(7))
                setOnClickListener {
                    settings.applySeverityPreset(preset)
                    Toast.makeText(this@MainActivity, "Profil ${preset.label} appliqué ${preset.emoji}", Toast.LENGTH_SHORT).show()
                    renderActiveTab(settings.snapshot())
                }
            }
            presetRow.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (idx > 0) marginStart = dp(5) })
        }
        demonCard.addView(presetRow)

        demonCard.addView(makeSwitchRow("🌙 Heures Calmes (23h–7h : zéro embuscade/farce la nuit)", rSnap.quietHoursEnabled) {
            rpgState.quietHoursEnabled = it
        })
        demonCard.addView(makeSwitchRow("⚔️ Embuscades Sorcières surprise hors de l'app (Accessibilité)", snap.rpgSeductionEnabled) { settings.rpgSeductionEnabled = it })
        demonCard.addView(makeSwitchRow("📜 Contre-Scroll & Anti-Doomscroll (Accessibilité)", snap.counterScrollEnabled) {
            settings.counterScrollEnabled = it; settings.antiDoomscrollEnabled = it
        })
        demonCard.addView(makeSwitchRow("⌨️ Sabotage Clavier & Zone Morte (Accessibilité)", snap.keyboardSabotageEnabled) {
            settings.keyboardSabotageEnabled = it; settings.deadZoneKeyboardEnabled = it
        })
        demonCard.addView(makeSwitchRow("🛡️ Anti-Like, Anti-Boutique & Vol de Clic (Accessibilité)", snap.antiLikeFollowEnabled) {
            settings.antiLikeFollowEnabled = it; settings.clickStealShieldEnabled = it; settings.blockStoreBuyEnabled = it
        })
        demonCard.addView(makeSwitchRow("🔕 Fermer le Panneau de Contrôle (uniquement ouvert en grand, réparé !)", snap.closeNotifShadeEnabled) {
            settings.closeNotifShadeEnabled = it; settings.blockQuickSettingsEnabled = it
        })
        demonCard.addView(makeSwitchRow("🌙 Drainage de luminosité & transe visuelle", snap.drainBrightnessEnabled) {
            settings.drainBrightnessEnabled = it; settings.drainQualityEnabled = it
        })
        demonCard.addView(makeSwitchRow("🕯️ Autel Photo Piégé (#87) & Fond d'écran trophée", snap.unremovableAltarEnabled) {
            settings.unremovableAltarEnabled = it; settings.wallpaperPrankEnabled = it
        })
        demonCard.addView(makeSwitchRow("🎬 Au Coin Vidéo (#38) & Lignes de Punition (#39)", snap.timeoutCornerEnabled) {
            settings.timeoutCornerEnabled = it; settings.punishmentLinesEnabled = it
        })

        demonCard.addView(makeAccentButton("🔒 Verrouiller (Paramètres + Admin • 20× + 100205)", Color.rgb(159, 18, 57)) {
            confirmAndEnableChallengeLock()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        })
        root.addView(demonCard)
    }

    // ==================== TAB 3 : ⚙️ DOSSIER PHOTOS, IA & SHIZUKU DIAGNOSTIC ====================
    private fun populateFolderAndAiTab(root: LinearLayout, snap: DemonConfigSnapshot) {
        val rSnap = rpgState.snapshot()

        // 1. Witch Media Folder
        val medCard = makeCard(accentStroke = Color.argb(145, 168, 85, 247))
        medCard.addView(sectionTitle("📂 Dossier Photos des 50 Sorcières (.* inclus)"))
        medCard.addView(sectionSub("Chaque photo est détourée automatiquement par IA (ML Kit) pour incarner les 50 Sorcières en plein écran !"))

        val sumTv = TextView(this).apply {
            setTextColor(Color.rgb(216, 180, 254)); textSize = 12f; typeface = Typeface.MONOSPACE
            background = roundedBg(Color.rgb(26, 20, 40), 10); setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        mediaSummaryTv = sumTv
        medCard.addView(sumTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4); bottomMargin = dp(8)
        })

        medCard.addView(makeAccentButton("🔍 Parcourir dossiers (masqués .* & .nomedia inclus)", Color.rgb(147, 51, 234)) {
            if (!MediaFolderHelper.hasAllFilesAccess()) {
                Toast.makeText(this, "Autorise « Accès à tous les fichiers » pour voir les dossiers masqués !", Toast.LENGTH_LONG).show()
                requestAllFilesAccess()
            } else {
                FolderPickerDialog.show(this, settings.mediaFolderPath) { chosenPath ->
                    settings.mediaFolderPath = chosenPath
                    Toast.makeText(this, "Dossier des Sorcières enregistré ✅", Toast.LENGTH_SHORT).show()
                    refreshMediaSummaryAsync(settings.snapshot())
                }
            }
        })
        medCard.addView(makeSecondaryBtn("📁 Ou sélecteur Android standard (SAF)") { pickSafTreeLauncher.launch(null) })

        val resBtn = makeSecondaryBtn("♻️ Restaurer mon fond d'écran d'origine") {
            Thread {
                val ok = MediaFolderHelper.restoreOriginalWallpaper(applicationContext)
                mainHandler.post { Toast.makeText(this, if (ok) "Fond d'écran restauré ✅" else "Aucune sauvegarde.", Toast.LENGTH_SHORT).show() }
            }.start()
        }
        resBtn.visibility = if (MediaFolderHelper.hasWallpaperBackup(this)) View.VISIBLE else View.GONE
        restoreWallBtn = resBtn
        medCard.addView(resBtn)
        root.addView(medCard)

        // 2. Instant AI Dialogue Engine (+ Optional Gemini API Key)
        val aiCard = makeCard(accentStroke = Color.argb(135, 14, 165, 233))
        aiCard.addView(sectionTitle("🧠 Dialogue IA Instantané (Non Répétitif)"))
        aiCard.addView(sectionSub(
            "• Moteur Local IA 0 ms : ACTIF ✅ (28 événements, 8 archétypes, mémoire anti-répétition 45 répliques).\n" +
                "• Optionnel : colle une clé API Google Gemini (aistudio.google.com) pour générer des répliques inédites par IA cloud !"
        ))

        val keyEt = EditText(this).apply {
            setText(rSnap.geminiApiKey); hint = "Clé API Gemini optionnelle (AIza…)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setTextColor(Color.WHITE); setHintTextColor(Color.rgb(130, 130, 152)); textSize = 12.5f
            background = roundedBg(Color.rgb(26, 24, 42), 10); setPadding(dp(10), dp(9), dp(10), dp(9))
        }
        aiCard.addView(keyEt)

        val aiRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        aiRow.addView(makeAccentButton("💾 Enregistrer Clé IA", Color.rgb(2, 132, 199)) {
            rpgState.geminiApiKey = keyEt.text?.toString().orEmpty()
            Toast.makeText(this, if (rpgState.geminiApiKey.isNotBlank()) "✨ IA Gemini Cloud activée !" else "⚡ Mode Local IA 0 ms.", Toast.LENGTH_SHORT).show()
            renderActiveTab(settings.snapshot())
        }, gridLp(endMargin = dp(5)))
        aiRow.addView(makeGridTestBtn("🗑️ Effacer") {
            rpgState.geminiApiKey = ""; keyEt.setText("")
            Toast.makeText(this, "Clé IA effacée.", Toast.LENGTH_SHORT).show()
        }, gridLp(startMargin = dp(5)))
        aiCard.addView(aiRow)
        root.addView(aiCard)

        // 3. Shizuku / MDM & Live Diagnostic Card
        val permCard = makeCard(accentStroke = Color.argb(135, 16, 185, 129))
        val shizukuOk = DemonMdmManager.isShizukuActive()
        val usageOk = DemonMdmManager.hasUsageStatsAccess(this)
        val accessRunning = DemonAccessibilityService.isServiceActive()
        val adminOk = DemonDeviceAdminReceiver.isAdminActive(this)
        val filesOk = MediaFolderHelper.hasAllFilesAccess()
        val ovOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val wsOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(this)

        permCard.addView(sectionTitle("🛡️ Shizuku & Autorisations Système"))
        permCard.addView(TextView(this).apply {
            text = DemonShizukuBridge.getDiagnostics(this@MainActivity)
            setTextColor(if (shizukuOk) Color.rgb(167, 243, 208) else Color.rgb(253, 224, 71))
            textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(4), 0, dp(8))
        })

        val shizRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        shizRow.addView(makeAccentButton(
            if (shizukuOk) "✅ Shizuku OK" else "⚡ Autoriser Shizuku",
            if (shizukuOk) Color.rgb(16, 128, 96) else Color.rgb(126, 34, 206)
        ) { handleShizukuAction() }, gridLp(endMargin = dp(5)))
        shizRow.addView(makeAccentButton("🧪 Tester (Griser 1 app)", Color.rgb(2, 132, 199)) {
            runShizukuSuspendTest()
        }, gridLp(startMargin = dp(5)))
        permCard.addView(shizRow)

        val pRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(6)) }
        pRow1.addView(makePermChip("Accessibilité", accessRunning) { openAccessibility() }, gridLp(endMargin = dp(4)))
        pRow1.addView(makePermChip("Fichiers .*", filesOk) { requestAllFilesAccess() }, gridLp(startMargin = dp(4), endMargin = dp(4)))
        pRow1.addView(makePermChip("UsageStats", usageOk) {
            try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } catch (_: Throwable) {}
        }, gridLp(startMargin = dp(4)))
        permCard.addView(pRow1)

        val pRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pRow2.addView(makePermChip("Superposition", ovOk) { openOverlayPerm() }, gridLp(endMargin = dp(4)))
        pRow2.addView(makePermChip("Luminosité", wsOk) { openWriteSettingsPerm() }, gridLp(startMargin = dp(4), endMargin = dp(4)))
        pRow2.addView(makePermChip("Admin", adminOk) { requestDeviceAdmin() }, gridLp(startMargin = dp(4)))
        permCard.addView(pRow2)

        permCard.addView(makeSecondaryBtn("🕊️ Tout réactiver & dégriser immédiatement") { performEndGameRestoreAll() })
        permCard.addView(makeSecondaryBtn("📋 Copier script Termux (optionnel)") { copyTermuxMdmScript() })
        permCard.addView(makeSecondaryBtn("👀 Applis surveillées (${snap.watchedPackages.size})") {
            AppPickerDialog.show(this, "Applis surveillées", settings.watchedPackages) {
                settings.watchedPackages = it; renderActiveTab(settings.snapshot())
            }
        })
        if (!accessRunning && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permCard.addView(makeSecondaryBtn("ℹ️ Android 13+ grisé ? Infos Appli (⋮ → Paramètres restreints)") { openAppDetails() })
        }
        root.addView(permCard)

        // 4. Demon Companion Portrait & Difficulty Sliders
        val avCard = makeCard()
        avCard.addView(sectionTitle("🎭 Compagnon Démon & Difficulté"))
        avCard.addView(makeAccentButton("📸 Choisir portrait du Compagnon Démon") {
            pickAvatarLauncher.launch(arrayOf("image/*"))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        })
        avCard.addView(makeSwitchRow("🔊 Effets Sonores Combat (Parade, Rupture, Esprits)", rSnap.combatSfxEnabled) {
            rpgState.combatSfxEnabled = it
        })
        avCard.addView(makeSwitchRow("Inclure cheveux & buste HD", snap.cutoutIncludeHair) { checked ->
            settings.cutoutIncludeHair = checked
            Thread {
                val rebuilt = AvatarCutoutEngine.rebuildFromSavedSource(applicationContext, checked)
                if (rebuilt != null) mainHandler.post {
                    settings.avatarPath = rebuilt; Toast.makeText(this, "Portrait mis à jour ✨", Toast.LENGTH_SHORT).show()
                }
            }.start()
        })
        avCard.addView(smallLabel("Style du compagnon :"))
        avCard.addView(makeChipGroup(AvatarStyle.values().toList(), snap.avatarStyle, { it.label }) { settings.avatarStyle = it })
        avCard.addView(makeSliderRow("Intensité / Chaos (≥ 8 = Duo Sorcières)", 1, 10, snap.chaosLevel, "/10") { settings.chaosLevel = it })
        avCard.addView(smallLabel("Cible du fond d'écran trophée :"))
        avCard.addView(makeChipGroup(WallpaperTarget.values().toList(), snap.wallpaperTarget, { it.label }) { settings.wallpaperTarget = it })
        avCard.addView(smallLabel("Censure IA (Overlay) :"))
        avCard.addView(makeChipGroup(FaceCensorMode.values().toList(), snap.faceCensorMode, { it.label }) { settings.faceCensorMode = it })
        avCard.addView(makeChipGroup(GenderFilter.values().toList(), snap.faceGenderFilter, { it.label }) { settings.faceGenderFilter = it })
        root.addView(avCard)
    }

    // -------------------------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------------------------
    private fun refreshMediaSummaryAsync(snap: DemonConfigSnapshot) {
        val tv = mediaSummaryTv ?: return
        Thread {
            val (imgs, vids) = MediaFolderHelper.countConfiguredMedia(applicationContext, snap)
            val txt = when {
                snap.mediaFolderPath.isNotBlank() -> "${snap.mediaFolderPath}\n→ $imgs photo(s) • $vids vidéo(s) prêtes pour les 50 Sorcières"
                snap.mediaTreeUri.isNotBlank() -> "Dossier SAF\n→ $imgs photo(s) • $vids vidéo(s) prêtes pour les 50 Sorcières"
                else -> "Aucun dossier choisi (silhouettes gothiques par défaut — sélectionne un dossier photos ci-dessous !)"
            }
            mainHandler.post {
                tv.text = txt
                restoreWallBtn?.visibility = if (MediaFolderHelper.hasWallpaperBackup(this)) View.VISIBLE else View.GONE
            }
        }.start()
    }

    private fun confirmAndEnableChallengeLock() {
        val adminActive = DemonDeviceAdminReceiver.isAdminActive(this)
        AlertDialog.Builder(this)
            .setTitle("🔒 Verrouiller Paramètres & Appli ?")
            .setMessage("Une fois activé :\n• Paramètres Android, désinstallation et réglages verrouillés.\n• Sortie : tapoter le démon 20× (ou barre NV en combat) + code 100205.")
            .setPositiveButton("Verrouiller") { _, _ ->
                settings.update {
                    putBoolean("enabled", true); putBoolean("strict_settings_lock", true); putBoolean("challenge_locked", true)
                }
                DemonCompanionService.start(applicationContext)
                if (!adminActive) requestDeviceAdmin()
                renderScreen(settings.snapshot())
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun openAccessibility() {
        try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        catch (_: Throwable) { Toast.makeText(this, "Paramètres → Accessibilité → DemonMom RPG", Toast.LENGTH_LONG).show() }
    }

    private fun openOverlayPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: Throwable) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
        }
    }

    private fun openWriteSettingsPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) }
            catch (_: Throwable) { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)) }
        }
    }

    private fun requestDeviceAdmin() {
        try {
            startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, DemonDeviceAdminReceiver.getComponentName(this@MainActivity))
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Verrouillage Défi DemonMom RPG (sortie : 20 taps + 100205).")
            })
        } catch (_: Throwable) {}
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: Throwable) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 202)
        }
    }

    private fun openAppDetails() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Throwable) {}
    }

    private fun requestBasicRuntimePermissionsIfNeeded() {
        val req = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) req.add(Manifest.permission.POST_NOTIFICATIONS)
        if (req.isNotEmpty()) ActivityCompat.requestPermissions(this, req.toTypedArray(), 201)
    }

    private fun makeCard(accentStroke: Int = Color.argb(38, 255, 255, 255)): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBg(CARD_DARK, 18, strokeColor = accentStroke, strokeDp = 1)
        setPadding(dp(14), dp(13), dp(14), dp(13))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(11) }
    }

    private fun makePermChip(label: String, ok: Boolean, onClick: () -> Unit): Button = Button(this).apply {
        text = if (ok) "✅ $label" else "⚠️ $label"
        isAllCaps = false; textSize = 10.6f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (ok) Color.rgb(167, 243, 208) else Color.rgb(254, 240, 138))
        background = roundedBg(
            if (ok) Color.argb(42, 16, 185, 129) else Color.argb(45, 245, 158, 11), 10,
            strokeColor = if (ok) Color.argb(120, 16, 185, 129) else Color.argb(120, 245, 158, 11), strokeDp = 1
        )
        minHeight = 0; minimumHeight = 0; setPadding(dp(4), dp(8), dp(4), dp(8)); setOnClickListener { onClick() }
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text; setTextColor(Color.WHITE); textSize = 14.6f; typeface = Typeface.DEFAULT_BOLD
    }

    private fun sectionSub(text: String): TextView = TextView(this).apply {
        this.text = text; setTextColor(Color.rgb(162, 162, 182)); textSize = 11.7f; setPadding(0, dp(2), 0, dp(6))
    }

    private fun smallLabel(text: String): TextView = TextView(this).apply {
        this.text = text; setTextColor(Color.rgb(208, 208, 224)); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(8), 0, dp(4))
    }

    private fun makeSwitchRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit): Switch = Switch(this).apply {
        text = label; isChecked = initial; setTextColor(Color.rgb(232, 232, 242)); textSize = 12.4f
        setPadding(0, dp(5), 0, dp(5)); setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    private fun makeSliderRow(title: String, min: Int, max: Int, initial: Int, unit: String, onChange: (Int) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(4)) }
        val tv = TextView(this).apply { text = "$title : $initial$unit"; setTextColor(Color.rgb(212, 212, 224)); textSize = 12f }
        val sb = SeekBar(this).apply {
            this.max = (max - min).coerceAtLeast(1); progress = (initial - min).coerceIn(0, this.max)
            thumbTintList = ColorStateList.valueOf(Color.rgb(217, 70, 239)); progressTintList = ColorStateList.valueOf(Color.rgb(217, 70, 239))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = min + progress; tv.text = "$title : $v$unit"; if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        box.addView(tv); box.addView(sb); return box
    }

    private fun <T> makeChipGroup(items: List<T>, selected: T, labelOf: (T) -> String, onSelect: (T) -> Unit): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var current = selected; val btns = ArrayList<Pair<T, Button>>()
        fun styleAll() {
            for ((item, b) in btns) {
                val active = item == current
                b.background = roundedBg(
                    if (active) Color.rgb(162, 28, 175) else Color.rgb(32, 28, 46), 11,
                    strokeColor = if (active) Color.rgb(240, 171, 252) else Color.argb(35, 255, 255, 255), strokeDp = 1
                )
                b.setTextColor(if (active) Color.WHITE else Color.rgb(208, 208, 220))
            }
        }
        for (rowItems in items.chunked(2)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowItems.forEachIndexed { idx, item ->
                val b = Button(this).apply {
                    text = labelOf(item); isAllCaps = false; textSize = 11.4f; minHeight = 0; minimumHeight = 0
                    setPadding(dp(8), dp(7), dp(8), dp(7))
                    setOnClickListener { current = item; styleAll(); onSelect(item) }
                }
                btns.add(Pair(item, b))
                row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx == 0 && rowItems.size > 1) marginEnd = dp(5); if (idx == 1) marginStart = dp(5)
                })
            }
            wrap.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(5) })
        }
        styleAll(); return wrap
    }

    private fun makeAccentButton(label: String, bgColor: Int = Color.rgb(225, 29, 72), onClick: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 12.4f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        background = roundedBg(bgColor, 12); setPadding(dp(10), dp(10), dp(10), dp(10)); minHeight = 0; minimumHeight = 0
        setOnClickListener { onClick() }
    }

    private fun makeSecondaryBtn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 11.7f; setTextColor(Color.rgb(224, 224, 236))
        background = roundedBg(Color.rgb(32, 28, 46), 11, strokeColor = Color.argb(45, 255, 255, 255), strokeDp = 1)
        setPadding(dp(10), dp(8), dp(10), dp(8)); minHeight = 0; minimumHeight = 0
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6); bottomMargin = dp(2) }
        setOnClickListener { onClick() }
    }

    private fun makeGridTestBtn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 11.4f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        background = roundedBg(Color.rgb(38, 24, 50), 11, strokeColor = Color.argb(90, 217, 70, 239), strokeDp = 1)
        setPadding(dp(6), dp(9), dp(6), dp(9)); minHeight = 0; minimumHeight = 0; setOnClickListener { onClick() }
    }

    private fun gridLp(startMargin: Int = 0, endMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = startMargin; marginEnd = endMargin }

    private fun roundedBg(fillColor: Int, radiusDp: Int, strokeColor: Int = Color.TRANSPARENT, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat(); setColor(fillColor)
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    companion object {
        private val BG_DARK = Color.rgb(11, 9, 18)
        private val CARD_DARK = Color.rgb(21, 17, 32)
    }
}
