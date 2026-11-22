package com.demonpet.app.ui

import android.Manifest
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
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
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
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
import com.demonpet.app.media.MediaFolderHelper
import com.demonpet.app.overlay.DemonPetView
import com.demonpet.app.receiver.DemonDeviceAdminReceiver
import com.demonpet.app.service.DemonAccessibilityService
import com.demonpet.app.service.DemonCompanionService

/**
 * Clean 4-Tab Native Material 3 Dashboard for DemonMom AI v2.0:
 * - Tab 0: Démon (Live Realistic Preview, ON/OFF, #65 Severity Presets, Compact Permissions, Quick Tests)
 * - Tab 1: Studio (Photorealistic HD Face/Bust Cutout, Styles, Media Folder with Hidden .* & Videos)
 * - Tab 2: Pouvoirs (Accessibility Sabotage, Punishments, AI Vision & Mischief)
 * - Tab 3: Réglages (Strict Settings Lock, Watched & Blacklist Apps, Fine Timers & Custom Phrases)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var settings: DemonSettings
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var rootFrame: FrameLayout
    private var lastRenderedLockState: Boolean? = null
    private var activeTab = 0

    // Live UI refs
    private var previewPetView: DemonPetView? = null
    private var lockedPetAnimView: DemonPetView? = null
    private var lockedTapHintTv: TextView? = null
    private var moodPillTv: TextView? = null
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
            if (lastRenderedLockState != snap.challengeLocked) {
                renderScreen(snap)
            } else {
                previewPetView?.updateConfig(snap)
                moodPillTv?.text = "${snap.mood.emoji} ${snap.mood.label} • ${snap.moodScore}%"
                if (masterSwitch?.isChecked != snap.enabled) masterSwitch?.isChecked = snap.enabled
                refreshMediaSummaryAsync(snap)
            }
        }
    }

    private val accessListener: () -> Unit = {
        mainHandler.post {
            if (!settings.challengeLocked && activeTab == 0) {
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
                    Toast.makeText(this, "Portrait réaliste prêt ! 😈", Toast.LENGTH_SHORT).show()
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
        Toast.makeText(this, "Dossier médias SAF sélectionné ✅", Toast.LENGTH_SHORT).show()
        refreshMediaSummaryAsync(settings.snapshot())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = DemonSettings(applicationContext)
        rootFrame = FrameLayout(this).apply { setBackgroundColor(BG_DARK) }
        setContentView(rootFrame)
        requestBasicRuntimePermissionsIfNeeded()
        renderScreen(settings.snapshot())
    }

    override fun onStart() {
        super.onStart()
        DemonSettings.addListener(cfgListener)
        DemonAccessibilityService.addStateListener(accessListener)
    }

    override fun onResume() {
        super.onResume()
        val snap = settings.snapshot()
        if (lastRenderedLockState != snap.challengeLocked) {
            renderScreen(snap)
        } else if (!snap.challengeLocked) {
            renderActiveTab(snap)
        }
        if (snap.enabled && !DemonCompanionService.isRunning) {
            DemonCompanionService.start(applicationContext)
        }
    }

    override fun onStop() {
        super.onStop()
        DemonSettings.removeListener(cfgListener)
        DemonAccessibilityService.removeStateListener(accessListener)
        mainHandler.removeCallbacks(animTick)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun renderScreen(snap: DemonConfigSnapshot) {
        lastRenderedLockState = snap.challengeLocked
        mainHandler.removeCallbacks(animTick)
        previewPetView = null
        lockedPetAnimView = null
        lockedTapHintTv = null
        rootFrame.removeAllViews()

        if (snap.challengeLocked) {
            rootFrame.addView(buildLockedChallengeGate(snap))
            mainHandler.post(animTick)
        } else {
            rootFrame.addView(buildTabbedShell(snap))
            renderActiveTab(snap)
        }
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
            text = "🔒 ${snap.avatarName} verrouille tout"
            setTextColor(Color.WHITE)
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        wrap.addView(TextView(this).apply {
            text = "Paramètres Android, Administrateur et réglages protégés.\nTapote le démon 20 fois de suite puis saisis le code pour sortir."
            setTextColor(Color.rgb(185, 185, 200))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(20))
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
            say("Tu ne peux pas me désactiver ! 😈", 4_000L)
        }
        lockedPetAnimView = petView

        val petCard = FrameLayout(this).apply {
            background = roundedBg(CARD_DARK, 28, strokeColor = Color.argb(135, 244, 63, 94), strokeDp = 2)
            addView(petView, FrameLayout.LayoutParams(dp(210), dp(240), Gravity.CENTER))
        }
        wrap.addView(petCard, LinearLayout.LayoutParams(dp(240), dp(264)))

        val hintTv = TextView(this).apply {
            text = "Tapote le démon 20× de suite pour débloquer"
            setTextColor(Color.rgb(253, 164, 175))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(8))
        }
        lockedTapHintTv = hintTv
        wrap.addView(hintTv)

        wrap.addView(TextView(this).apply {
            text = "${snap.mood.emoji} ${snap.mood.label} (${snap.moodScore}%) • Marche aussi sur le démon flottant"
            setTextColor(Color.rgb(145, 145, 162))
            textSize = 11.5f
            gravity = Gravity.CENTER
        })

        return wrap
    }

    private fun showExitPinDialog() {
        val input = EditText(this).apply {
            hint = "Code secret (100205)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(145, 145, 162))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBg(Color.rgb(30, 27, 42), 12)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(8))
            setBackgroundColor(Color.rgb(20, 18, 28))
            addView(TextView(this@MainActivity).apply {
                text = "Saisis le code pour déverrouiller les Paramètres, retirer l'Administrateur et mettre le démon en pause :"
                setTextColor(Color.rgb(225, 225, 234))
                textSize = 13f
                setPadding(0, 0, 0, dp(12))
            })
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("🔓 Déverrouillage DemonMom AI")
            .setView(box)
            .setPositiveButton("Déverrouiller") { _, _ ->
                if (settings.verifyExitPin(input.text?.toString().orEmpty())) {
                    performFullUnlock()
                } else {
                    Toast.makeText(this, "Mauvais code ! 😈", Toast.LENGTH_SHORT).show()
                    lockedPetAnimView?.say("Raté ! Mauvais code 😈", 3_000L)
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun performFullUnlock() {
        DemonDeviceAdminReceiver.removeAdminSafely(applicationContext)
        DemonAccessibilityService.activeInstance?.performSecretExitUnlock()
        settings.update {
            putBoolean("challenge_locked", false)
            putBoolean("strict_settings_lock", false)
            putBoolean("enabled", false)
        }
        DemonCompanionService.stop(applicationContext)
        Toast.makeText(this, "Déverrouillé, Paramètres libérés et Admin retiré ✅", Toast.LENGTH_LONG).show()
        renderScreen(settings.snapshot())
    }

    // -------------------------------------------------------------------------
    // CLEAN 4-TAB SHELL
    // -------------------------------------------------------------------------
    private fun buildTabbedShell(snap: DemonConfigSnapshot): View {
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_DARK)
        }

        // Compact sleek top header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(10))
        }
        header.addView(TextView(this).apply {
            text = "😈 DemonMom"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val pill = TextView(this).apply {
            text = "${snap.mood.emoji} ${snap.mood.label} • ${snap.moodScore}%"
            setTextColor(Color.WHITE)
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            background = roundedBg(Color.rgb(38, 22, 38), 14, strokeColor = Color.argb(140, 244, 63, 94), strokeDp = 1)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setOnClickListener {
                settings.update { putInt("mood_score", 20) }
                Toast.makeText(this@MainActivity, "Humeur calmée 😇", Toast.LENGTH_SHORT).show()
            }
        }
        moodPillTv = pill
        header.addView(pill)
        shell.addView(header)

        // 4 Clean Pill Tabs
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(2), dp(14), dp(10))
        }
        tabButtons.clear()
        val titles = listOf("😈 Démon", "🎭 Studio", "⚡ Pouvoirs", "⚙️ Réglages")
        titles.forEachIndexed { idx, label ->
            val b = Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                minHeight = 0; minimumHeight = 0
                setPadding(dp(4), dp(8), dp(4), dp(8))
                setOnClickListener {
                    activeTab = idx
                    updateTabBarStyles()
                    renderActiveTab(settings.snapshot())
                }
            }
            tabButtons.add(b)
            tabBar.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (idx > 0) marginStart = dp(6)
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
                if (sel) Color.rgb(225, 29, 72) else Color.rgb(25, 22, 36),
                12,
                strokeColor = if (sel) Color.rgb(253, 164, 175) else Color.argb(35, 255, 255, 255),
                strokeDp = 1
            )
            b.setTextColor(if (sel) Color.WHITE else Color.rgb(175, 175, 192))
        }
    }

    private fun renderActiveTab(snap: DemonConfigSnapshot) {
        val box = tabContentContainer ?: return
        mainHandler.removeCallbacks(animTick)
        previewPetView = null
        box.removeAllViews()

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(28))
        }
        scroll.addView(inner)

        when (activeTab) {
            0 -> populateHomeTab(inner, snap)
            1 -> populateStudioTab(inner, snap)
            2 -> populatePowersTab(inner, snap)
            3 -> populateSettingsTab(inner, snap)
        }
        box.addView(scroll)
        if (previewPetView != null) mainHandler.post(animTick)
        refreshMediaSummaryAsync(snap)
    }

    // ==================== TAB 0 : DÉMON (ULTRA-CLEAN HOME) ====================
    private fun populateHomeTab(root: LinearLayout, snap: DemonConfigSnapshot) {
        // 1. Live Realistic Avatar Hero + Master Toggle
        val hero = makeCard(accentStroke = Color.argb(120, 244, 63, 94))
        val heroRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val pv = DemonPetView(
            context = this,
            onDragMove = { _, _ -> },
            onDragRelease = { _, _, _ -> },
            onGentlePet = { settings.adjustMood(-5) },
            onSecretTwentyTaps = {}
        ).apply {
            updateConfig(snap)
            pose = DemonPetView.PetPose.WALKING
        }
        previewPetView = pv
        heroRow.addView(pv, LinearLayout.LayoutParams(dp(106), dp(126)))

        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        rightCol.addView(TextView(this).apply {
            text = snap.avatarName
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        })
        rightCol.addView(TextView(this).apply {
            text = "${snap.avatarStyle.label}\nProfil : ${snap.severityPreset.emoji} ${snap.severityPreset.label}"
            setTextColor(Color.rgb(175, 175, 192))
            textSize = 12f
            setPadding(0, dp(2), 0, dp(10))
        })

        val sw = Switch(this).apply {
            isChecked = snap.enabled
            text = if (snap.enabled) "Démon ACTIF" else "Activer le Démon"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setOnCheckedChangeListener { _, checked ->
                text = if (checked) "Démon ACTIF" else "Activer le Démon"
                settings.enabled = checked
                if (checked) DemonCompanionService.start(applicationContext)
                else DemonCompanionService.stop(applicationContext)
            }
        }
        masterSwitch = sw
        rightCol.addView(sw)
        heroRow.addView(rightCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        hero.addView(heroRow)

        hero.addView(divider())

        // #65 — 4 Severity Presets in 1 tap
        hero.addView(smallLabel("Niveau de sévérité (#65) :"))
        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        SeverityPreset.values().forEachIndexed { idx, preset ->
            val active = snap.severityPreset == preset
            val btn = Button(this).apply {
                text = "${preset.emoji}\n${preset.label}"
                isAllCaps = false
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (active) Color.WHITE else Color.rgb(195, 195, 210))
                background = roundedBg(
                    if (active) Color.rgb(225, 29, 72) else Color.rgb(32, 28, 46),
                    12,
                    strokeColor = if (active) Color.rgb(253, 164, 175) else Color.argb(35, 255, 255, 255),
                    strokeDp = 1
                )
                minHeight = 0; minimumHeight = 0
                setPadding(dp(4), dp(8), dp(4), dp(8))
                setOnClickListener {
                    settings.applySeverityPreset(preset)
                    Toast.makeText(this@MainActivity, "Profil ${preset.label} appliqué ${preset.emoji}", Toast.LENGTH_SHORT).show()
                    renderActiveTab(settings.snapshot())
                }
            }
            presetRow.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (idx > 0) marginStart = dp(6)
            })
        }
        hero.addView(presetRow)

        hero.addView(makeAccentButton("🔒 Verrouiller (Paramètres + Admin • Sortie 20× + 100205)", Color.rgb(159, 18, 57)) {
            confirmAndEnableChallengeLock()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            }
        })
        root.addView(hero)

        // 2. Compact Permissions Status Card (1-tap chips!)
        val permCard = makeCard()
        permCard.addView(sectionTitle("🛡️ Autorisations"))
        val accessRunning = DemonAccessibilityService.isServiceActive()
        val accessOk = accessRunning || DemonAccessibilityService.isEnabledInSettings(this)
        val adminOk = DemonDeviceAdminReceiver.isAdminActive(this)
        val filesOk = MediaFolderHelper.hasAllFilesAccess()
        val ovOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val wsOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(this)

        if (!accessRunning) {
            permCard.addView(TextView(this).apply {
                text = "⚠️ Service d'Accessibilité non connecté : les pouvoirs #26–#44 et le verrouillage Paramètres nécessitent de l'activer (ou le relancer après mise à jour)."
                setTextColor(Color.rgb(253, 186, 116))
                textSize = 11.5f
                setPadding(0, dp(4), 0, dp(4))
            })
        }

        val pRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(6)) }
        pRow1.addView(makePermChip(if (accessRunning) "Accessibilité" else "Activer Access.", accessRunning) { openAccessibility() }, gridLp(endMargin = dp(5)))
        pRow1.addView(makePermChip("Admin", adminOk) { requestDeviceAdmin() }, gridLp(startMargin = dp(5), endMargin = dp(5)))
        pRow1.addView(makePermChip("Fichiers .*", filesOk) { requestAllFilesAccess() }, gridLp(startMargin = dp(5)))
        permCard.addView(pRow1)

        val pRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pRow2.addView(makePermChip("Superposition", ovOk) { openOverlayPerm() }, gridLp(endMargin = dp(5)))
        pRow2.addView(makePermChip("Luminosité", wsOk) { openWriteSettingsPerm() }, gridLp(startMargin = dp(5)))
        permCard.addView(pRow2)

        if (!accessOk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permCard.addView(makeSecondaryBtn("ℹ️ Android 13+ grisé ? Ouvrir Infos Appli (⋮ → Paramètres restreints)") {
                openAppDetails()
            })
        }
        root.addView(permCard)

        // 3. Quick Live Tests
        val testCard = makeCard()
        testCard.addView(sectionTitle("🧪 Tester en direct"))
        val t1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(6)) }
        t1.addView(makeGridTestBtn("🎬 Au coin Vidéo") { runLiveTest("corner") }, gridLp(endMargin = dp(5)))
        t1.addView(makeGridTestBtn("✍️ Lignes (#39)") { runLiveTest("lines") }, gridLp(startMargin = dp(5)))
        testCard.addView(t1)

        val t2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(6)) }
        t2.addView(makeGridTestBtn("📝 Quiz (#53)") { runLiveTest("quiz") }, gridLp(endMargin = dp(5)))
        t2.addView(makeGridTestBtn("📸 Diaporama") { runLiveTest("slideshow") }, gridLp(startMargin = dp(5)))
        testCard.addView(t2)

        val t3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(6)) }
        t3.addView(makeGridTestBtn("🚫 Zone Morte") { runLiveTest("deadzone") }, gridLp(endMargin = dp(5)))
        t3.addView(makeGridTestBtn("🔥 Crise Furieuse") { runLiveTest("fury") }, gridLp(startMargin = dp(5)))
        testCard.addView(t3)
        root.addView(testCard)
    }

    // ==================== TAB 1 : STUDIO (REALISTIC AVATAR + HIDDEN MEDIA FOLDER) ====================
    private fun populateStudioTab(root: LinearLayout, snap: DemonConfigSnapshot) {
        val avCard = makeCard(accentStroke = Color.argb(100, 244, 63, 94))
        avCard.addView(sectionTitle("🎭 Avatar Photo-Réaliste HD"))
        avCard.addView(sectionSub("Détourage IA haute définition (visage + cheveux + buste + cornes 3D posées sur le vrai front)."))

        val pvWrap = FrameLayout(this).apply {
            background = roundedBg(Color.rgb(18, 15, 28), 18)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val pv = DemonPetView(
            context = this,
            onDragMove = { _, _ -> },
            onDragRelease = { _, _, _ -> },
            onGentlePet = {},
            onSecretTwentyTaps = {}
        ).apply { updateConfig(snap) }
        previewPetView = pv
        pvWrap.addView(pv, FrameLayout.LayoutParams(dp(150), dp(180), Gravity.CENTER))
        avCard.addView(pvWrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(196)).apply {
            topMargin = dp(8)
            bottomMargin = dp(10)
        })

        avCard.addView(makeAccentButton("📸 Choisir une photo (Détourage réaliste HD)") {
            pickAvatarLauncher.launch(arrayOf("image/*"))
        })

        avCard.addView(makeSwitchRow("Inclure cheveux & buste réel (Silhouette HD)", snap.cutoutIncludeHair) { checked ->
            settings.cutoutIncludeHair = checked
            Thread {
                val rebuilt = AvatarCutoutEngine.rebuildFromSavedSource(applicationContext, checked)
                if (rebuilt != null) {
                    mainHandler.post {
                        settings.avatarPath = rebuilt
                        previewPetView?.updateConfig(settings.snapshot())
                        Toast.makeText(this, "Détourage mis à jour ✨", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        })

        avCard.addView(smallLabel("Style réaliste :"))
        avCard.addView(makeChipGroup(AvatarStyle.values().toList(), snap.avatarStyle, { it.label }) {
            settings.avatarStyle = it
            previewPetView?.updateConfig(settings.snapshot())
        })

        avCard.addView(makeSliderRow("Taille de l'avatar", 72, 156, snap.avatarSizeDp, " dp") {
            settings.avatarSizeDp = it
        })

        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        val nameEt = EditText(this).apply {
            setText(snap.avatarName)
            hint = "Prénom du démon"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(130, 130, 148))
            textSize = 13.5f
            background = roundedBg(Color.rgb(28, 25, 40), 10)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        nameRow.addView(nameEt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nameRow.addView(makeSmallActionButton("OK") {
            settings.avatarName = nameEt.text?.toString().orEmpty()
            Toast.makeText(this, "Nom enregistré ✅", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8)
        })
        avCard.addView(nameRow)
        root.addView(avCard)

        // Media Folder Card (Photos + Videos + Hidden .* folders)
        val medCard = makeCard(accentStroke = Color.argb(95, 168, 85, 247))
        medCard.addView(sectionTitle("📂 Dossier Photos & Vidéos (Masqués .* inclus)"))
        medCard.addView(sectionSub("Utilisé pour fonds d'écran (#14), pop-ups (#15), diaporamas (#61) et vidéos de punition Au Coin (#38)."))

        val sumTv = TextView(this).apply {
            setTextColor(Color.rgb(216, 180, 254))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            background = roundedBg(Color.rgb(26, 20, 40), 10)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        mediaSummaryTv = sumTv
        medCard.addView(sumTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
            bottomMargin = dp(8)
        })

        medCard.addView(makeAccentButton("🔍 Parcourir dossiers (masqués .* & .nomedia inclus)", Color.rgb(147, 51, 234)) {
            if (!MediaFolderHelper.hasAllFilesAccess()) {
                Toast.makeText(this, "Autorise « Accès à tous les fichiers » pour voir les dossiers masqués !", Toast.LENGTH_LONG).show()
                requestAllFilesAccess()
            } else {
                FolderPickerDialog.show(this, settings.mediaFolderPath) { chosenPath ->
                    settings.mediaFolderPath = chosenPath
                    Toast.makeText(this, "Dossier sélectionné ✅", Toast.LENGTH_SHORT).show()
                    refreshMediaSummaryAsync(settings.snapshot())
                }
            }
        })

        medCard.addView(makeSecondaryBtn("📁 Ou sélecteur Android standard (SAF)") {
            pickSafTreeLauncher.launch(null)
        })

        val resBtn = makeSecondaryBtn("♻️ Restaurer mon fond d'écran d'origine") {
            Thread {
                val ok = MediaFolderHelper.restoreOriginalWallpaper(applicationContext)
                mainHandler.post {
                    Toast.makeText(this, if (ok) "Fond d'écran restauré ✅" else "Aucune sauvegarde.", Toast.LENGTH_SHORT).show()
                }
            }.start()
        }
        resBtn.visibility = if (MediaFolderHelper.hasWallpaperBackup(this)) View.VISIBLE else View.GONE
        restoreWallBtn = resBtn
        medCard.addView(resBtn)
        root.addView(medCard)
    }

    // ==================== TAB 2 : POUVOIRS (CLEAN 3-SECTION SEVERITY CONTROLS) ====================
    private fun populatePowersTab(root: LinearLayout, snap: DemonConfigSnapshot) {
        // Section 1: Severe Accessibility Sabotage
        val c1 = makeCard(accentStroke = Color.argb(95, 244, 63, 94))
        c1.addView(sectionTitle("⚡ Sabotage Accessibilité (Actif partout)"))
        c1.addView(makeSwitchRow("#26 Contre-scroll (remonte tes fils)", snap.counterScrollEnabled) { settings.counterScrollEnabled = it })
        c1.addView(makeSwitchRow("#27 Touche Retour compulsive", snap.compulsiveBackEnabled) { settings.compulsiveBackEnabled = it })
        c1.addView(makeSwitchRow("#29 Mise en pause forcée des vidéos", snap.forcePauseVideoEnabled) { settings.forcePauseVideoEnabled = it })
        c1.addView(makeSwitchRow("#31 Vol de clics (bouclier sur boutons)", snap.clickStealShieldEnabled) { settings.clickStealShieldEnabled = it })
        c1.addView(makeSwitchRow("#37 Anti-Like / Anti-Follow ciblé", snap.antiLikeFollowEnabled) { settings.antiLikeFollowEnabled = it })
        c1.addView(makeSwitchRow("#32 Sabotage de saisie clavier", snap.keyboardSabotageEnabled) { settings.keyboardSabotageEnabled = it })
        c1.addView(makeSwitchRow("#34 Fermer le panneau de notifications", snap.closeNotifShadeEnabled) { settings.closeNotifShadeEnabled = it })
        c1.addView(makeSwitchRow("#35 Bloquer les Réglages Rapides (Wi-Fi/Avion)", snap.blockQuickSettingsEnabled) { settings.blockQuickSettingsEnabled = it })
        root.addView(c1)

        // Section 2: Severe Punishments & Parental Discipline
        val c2 = makeCard()
        c2.addView(sectionTitle("🔒 Punitions & Discipline"))
        c2.addView(makeSwitchRow("#38 « Au coin ! » avec vidéo du dossier", snap.timeoutCornerEnabled) { settings.timeoutCornerEnabled = it })
        c2.addView(makeSwitchRow("#39 Lignes de punition à recopier (anti-collage)", snap.punishmentLinesEnabled) { settings.punishmentLinesEnabled = it })
        c2.addView(makeSwitchRow("#42 Zone Morte sur le clavier", snap.deadZoneKeyboardEnabled) { settings.deadZoneKeyboardEnabled = it })
        c2.addView(makeSwitchRow("#44 Liste Noire Totale (confiscation)", snap.blacklistEnabled) { settings.blacklistEnabled = it })
        c2.addView(makeSwitchRow("#7 Demander la permission avant d'ouvrir", snap.askPermissionGate) { settings.askPermissionGate = it })
        c2.addView(makeSwitchRow("#8 Quota temps limite + #9 Négociation", snap.appQuotaLockEnabled) { settings.appQuotaLockEnabled = it })
        c2.addView(makeSwitchRow("#10 Couvre-feu « Au lit ! »", snap.curfewEnabled) { settings.curfewEnabled = it })
        c2.addView(makeSwitchRow("#11 Mode « Fais tes devoirs ! »", snap.homeworkModeEnabled) { settings.homeworkModeEnabled = it })
        c2.addView(makeSwitchRow("#12 Anti-doomscroll (s'assoit au centre)", snap.antiDoomscrollEnabled) { settings.antiDoomscrollEnabled = it })
        root.addView(c2)

        // Section 3: AI Vision Censor & Interactive Mischief
        val c3 = makeCard()
        c3.addView(sectionTitle("👀 Censure IA & Bêtises"))
        c3.addView(smallLabel("Censure des visages (#1, #2, #5, #6) :"))
        c3.addView(makeChipGroup(FaceCensorMode.values().toList(), snap.faceCensorMode, { it.label }) { settings.faceCensorMode = it })
        c3.addView(smallLabel("Filtre jalousie (#3) :"))
        c3.addView(makeChipGroup(GenderFilter.values().toList(), snap.faceGenderFilter, { it.label }) { settings.faceGenderFilter = it })
        c3.addView(makeSwitchRow("#4 Bouclier NSFW dramatique", snap.nsfwShieldEnabled) { settings.nsfwShieldEnabled = it })
        c3.addView(divider())
        c3.addView(makeSwitchRow("#50 Le démon esquive ton doigt", snap.dodgeFingerEnabled) { settings.dodgeFingerEnabled = it })
        c3.addView(makeSwitchRow("#53 Quiz surprise « Maman te teste »", snap.surpriseQuizEnabled) { settings.surpriseQuizEnabled = it })
        c3.addView(makeSwitchRow("#61 Diaporama forcé (4 photos)", snap.forcedSlideshowEnabled) { settings.forcedSlideshowEnabled = it })
        c3.addView(makeSwitchRow("#14 Fond d'écran aléatoire", snap.wallpaperPrankEnabled) { settings.wallpaperPrankEnabled = it })
        c3.addView(makeSwitchRow("#15 Pop-ups photo tirées", snap.draggedPopupEnabled) { settings.draggedPopupEnabled = it })
        c3.addView(makeSwitchRow("#16 Flash photo plein écran", snap.fullscreenFlashEnabled) { settings.fullscreenFlashEnabled = it })
        c3.addView(makeSwitchRow("#19 Luminosité nuit / #20 Batterie / #22 Commentaires / #23 Vitesse", snap.appCommentsEnabled) {
            settings.update {
                putBoolean("app_comments", it)
                putBoolean("night_brightness", it)
                putBoolean("low_battery_panic", it)
                putBoolean("speed_warning", it)
            }
        })
        root.addView(c3)
    }

    // ==================== TAB 3 : RÉGLAGES (SETTINGS LOCK, APPS, TIMERS) ====================
    private fun populateSettingsTab(root: LinearLayout, snap: DemonConfigSnapshot) {
        // Security & Settings Lock Card
        val secCard = makeCard(accentStroke = Color.argb(110, 244, 63, 94))
        secCard.addView(sectionTitle("🔐 Verrouillage des Paramètres"))
        secCard.addView(sectionSub("Bloque l'accès aux Paramètres Android et à la désinstallation. Sortie : tapoter le démon 20× + code 100205."))
        secCard.addView(makeSwitchRow("Verrouiller strictement les Paramètres Android", snap.strictSettingsLock) {
            settings.strictSettingsLock = it
        })
        secCard.addView(makeAccentButton("🔒 Activer le Verrouillage Défi complet maintenant") {
            confirmAndEnableChallengeLock()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        })
        root.addView(secCard)

        // Apps Picker Card
        val appCard = makeCard()
        appCard.addView(sectionTitle("📱 Applications ciblées"))
        appCard.addView(makeSecondaryBtn("👀 Applis surveillées (${snap.watchedPackages.size})") {
            AppPickerDialog.show(this, "Applis surveillées", settings.watchedPackages) {
                settings.watchedPackages = it
                renderActiveTab(settings.snapshot())
            }
        })
        appCard.addView(makeSecondaryBtn("🚫 Liste Noire Totale #44 (${snap.blacklistPackages.size})") {
            AppPickerDialog.show(this, "Liste Noire Totale (Confisquées)", settings.blacklistPackages) {
                settings.blacklistPackages = it
                renderActiveTab(settings.snapshot())
            }
        })
        root.addView(appCard)

        // Timers & Texts Card
        val tuneCard = makeCard()
        tuneCard.addView(sectionTitle("🎛️ Intensité & Punitions"))
        tuneCard.addView(makeSliderRow("Niveau de chaos", 1, 10, snap.chaosLevel, "/10") { settings.chaosLevel = it })
        tuneCard.addView(makeSliderRow("Intervalle min entre bêtises", 8, 300, snap.minIntervalSec, " s") { settings.minIntervalSec = it })
        tuneCard.addView(makeSliderRow("Durée Au Coin Vidéo (#38)", 15, 300, snap.timeoutCornerSec, " s") { settings.timeoutCornerSec = it })
        tuneCard.addView(makeSliderRow("Nombre de lignes à recopier (#39)", 2, 10, snap.punishmentLinesCount, " lignes") { settings.punishmentLinesCount = it })
        tuneCard.addView(makeSliderRow("Quota par appli (#8)", 2, 60, snap.appQuotaMinutes, " min") { settings.appQuotaMinutes = it })
        tuneCard.addView(makeSliderRow("Couvre-feu début (#10)", 0, 23, snap.curfewStartHour, "h") { settings.curfewStartHour = it })
        tuneCard.addView(makeSliderRow("Couvre-feu fin (#10)", 0, 23, snap.curfewEndHour, "h") { settings.curfewEndHour = it })
        tuneCard.addView(makeSliderRow("Seuil vitesse route (#23)", 10, 90, snap.speedThresholdKmh, " km/h") { settings.speedThresholdKmh = it })

        tuneCard.addView(smallLabel("Cible du fond d'écran (#14) :"))
        tuneCard.addView(makeChipGroup(WallpaperTarget.values().toList(), snap.wallpaperTarget, { it.label }) { settings.wallpaperTarget = it })

        tuneCard.addView(smallLabel("Phrase de punition à recopier (#39) :"))
        val punEt = EditText(this).apply {
            setText(snap.punishmentPhrase)
            setTextColor(Color.WHITE)
            textSize = 12.5f
            background = roundedBg(Color.rgb(26, 24, 38), 10)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        tuneCard.addView(punEt)

        tuneCard.addView(smallLabel("Phrases personnalisées (1 par ligne) :"))
        val phrEt = EditText(this).apply {
            setText(snap.customPhrases)
            hint = "Range ta chambre !\nJe te surveille 👀"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(120, 120, 140))
            textSize = 12.5f
            minLines = 2
            background = roundedBg(Color.rgb(26, 24, 38), 10)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        tuneCard.addView(phrEt)

        tuneCard.addView(makeSecondaryBtn("💾 Enregistrer les phrases") {
            settings.punishmentPhrase = punEt.text?.toString().orEmpty()
            settings.customPhrases = phrEt.text?.toString().orEmpty()
            Toast.makeText(this, "Phrases enregistrées ✅", Toast.LENGTH_SHORT).show()
        })
        root.addView(tuneCard)
    }

    // -------------------------------------------------------------------------
    // ACTIONS & HELPERS
    // -------------------------------------------------------------------------
    private fun runLiveTest(kind: String) {
        if (!settings.enabled) {
            settings.enabled = true
            DemonCompanionService.start(applicationContext)
        }
        DemonAccessibilityService.activeInstance?.triggerManualTest(kind)?.also { return }
        DemonCompanionService.instance?.triggerFallbackTest(kind)?.also { return }
        DemonCompanionService.start(applicationContext)
        mainHandler.postDelayed({
            DemonAccessibilityService.activeInstance?.triggerManualTest(kind)
                ?: DemonCompanionService.instance?.triggerFallbackTest(kind)
                ?: Toast.makeText(this, "Active d'abord l'Accessibilité ou la Superposition !", Toast.LENGTH_LONG).show()
        }, 550L)
    }

    private fun refreshMediaSummaryAsync(snap: DemonConfigSnapshot) {
        val tv = mediaSummaryTv ?: return
        Thread {
            val (imgs, vids) = MediaFolderHelper.countConfiguredMedia(applicationContext, snap)
            val txt = when {
                snap.mediaFolderPath.isNotBlank() -> "${snap.mediaFolderPath}\n→ $imgs photo(s) • $vids vidéo(s)"
                snap.mediaTreeUri.isNotBlank() -> "Dossier SAF\n→ $imgs photo(s) • $vids vidéo(s)"
                else -> "Aucun dossier choisi (utilise ton portrait par défaut)"
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
            .setMessage(
                "Une fois activé :\n" +
                    "• Les Paramètres Android, la désinstallation et cette appli sont verrouillés.\n" +
                    "• Pour sortir : tapote le démon 20 fois de suite puis saisis le code 100205."
            )
            .setPositiveButton("Verrouiller") { _, _ ->
                settings.update {
                    putBoolean("enabled", true)
                    putBoolean("strict_settings_lock", true)
                    putBoolean("challenge_locked", true)
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
        catch (_: Throwable) { Toast.makeText(this, "Paramètres → Accessibilité → DemonMom AI", Toast.LENGTH_LONG).show() }
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
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, DemonDeviceAdminReceiver.getComponentName(this@MainActivity))
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Verrouillage Défi DemonMom AI (sortie : 20 taps + 100205).")
            }
            startActivity(intent)
        } catch (_: Throwable) {
        }
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
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        catch (_: Throwable) {}
    }

    private fun requestBasicRuntimePermissionsIfNeeded() {
        val req = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) req.add(Manifest.permission.POST_NOTIFICATIONS)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            req.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (req.isNotEmpty()) ActivityCompat.requestPermissions(this, req.toTypedArray(), 201)
    }

    // -------------------------------------------------------------------------
    // SLEEK UI WIDGET HELPERS
    // -------------------------------------------------------------------------
    private fun makeCard(accentStroke: Int = Color.argb(38, 255, 255, 255)): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBg(CARD_DARK, 18, strokeColor = accentStroke, strokeDp = 1)
        setPadding(dp(15), dp(14), dp(15), dp(14))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        }
    }

    private fun makePermChip(label: String, ok: Boolean, onClick: () -> Unit): Button = Button(this).apply {
        text = if (ok) "✅ $label" else "⚠️ $label"
        isAllCaps = false
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (ok) Color.rgb(167, 243, 208) else Color.rgb(254, 240, 138))
        background = roundedBg(
            if (ok) Color.argb(42, 16, 185, 129) else Color.argb(45, 245, 158, 11),
            10,
            strokeColor = if (ok) Color.argb(120, 16, 185, 129) else Color.argb(120, 245, 158, 11),
            strokeDp = 1
        )
        minHeight = 0; minimumHeight = 0
        setPadding(dp(6), dp(8), dp(6), dp(8))
        setOnClickListener { onClick() }
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text; setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
    }

    private fun sectionSub(text: String): TextView = TextView(this).apply {
        this.text = text; setTextColor(Color.rgb(156, 156, 174)); textSize = 12f; setPadding(0, dp(2), 0, dp(6))
    }

    private fun smallLabel(text: String): TextView = TextView(this).apply {
        this.text = text; setTextColor(Color.rgb(208, 208, 222)); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(8), 0, dp(4))
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(Color.argb(26, 255, 255, 255))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(10); bottomMargin = dp(10)
        }
    }

    private fun makeSwitchRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit): Switch = Switch(this).apply {
        text = label; isChecked = initial; setTextColor(Color.rgb(232, 232, 242)); textSize = 12.8f
        setPadding(0, dp(6), 0, dp(6))
        setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    private fun makeSliderRow(title: String, min: Int, max: Int, initial: Int, unit: String, onChange: (Int) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(4)) }
        val tv = TextView(this).apply { text = "$title : $initial$unit"; setTextColor(Color.rgb(212, 212, 224)); textSize = 12.2f }
        val sb = SeekBar(this).apply {
            this.max = (max - min).coerceAtLeast(1)
            progress = (initial - min).coerceIn(0, this.max)
            thumbTintList = ColorStateList.valueOf(Color.rgb(244, 63, 94))
            progressTintList = ColorStateList.valueOf(Color.rgb(244, 63, 94))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = min + progress
                    tv.text = "$title : $v$unit"
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        box.addView(tv); box.addView(sb)
        return box
    }

    private fun <T> makeChipGroup(items: List<T>, selected: T, labelOf: (T) -> String, onSelect: (T) -> Unit): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var current = selected
        val btns = ArrayList<Pair<T, Button>>()
        fun styleAll() {
            for ((item, b) in btns) {
                val active = item == current
                b.background = roundedBg(
                    if (active) Color.rgb(225, 29, 72) else Color.rgb(32, 28, 46), 11,
                    strokeColor = if (active) Color.rgb(253, 164, 175) else Color.argb(35, 255, 255, 255), strokeDp = 1
                )
                b.setTextColor(if (active) Color.WHITE else Color.rgb(208, 208, 220))
            }
        }
        for (rowItems in items.chunked(2)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowItems.forEachIndexed { idx, item ->
                val b = Button(this).apply {
                    text = labelOf(item); isAllCaps = false; textSize = 11.8f; minHeight = 0; minimumHeight = 0
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    setOnClickListener { current = item; styleAll(); onSelect(item) }
                }
                btns.add(Pair(item, b))
                row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx == 0 && rowItems.size > 1) marginEnd = dp(5)
                    if (idx == 1) marginStart = dp(5)
                })
            }
            wrap.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            })
        }
        styleAll()
        return wrap
    }

    private fun makeAccentButton(label: String, bgColor: Int = Color.rgb(225, 29, 72), onClick: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 12.8f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        background = roundedBg(bgColor, 12); setPadding(dp(12), dp(10), dp(12), dp(10)); minHeight = 0; minimumHeight = 0
        setOnClickListener { onClick() }
    }

    private fun makeSmallActionButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        background = roundedBg(Color.rgb(225, 29, 72), 10); setPadding(dp(12), dp(7), dp(12), dp(7)); minHeight = 0; minimumHeight = 0
        setOnClickListener { onClick() }
    }

    private fun makeSecondaryBtn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 12f; setTextColor(Color.rgb(224, 224, 236))
        background = roundedBg(Color.rgb(32, 28, 46), 11, strokeColor = Color.argb(45, 255, 255, 255), strokeDp = 1)
        setPadding(dp(10), dp(8), dp(10), dp(8)); minHeight = 0; minimumHeight = 0
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6); bottomMargin = dp(2)
        }
        setOnClickListener { onClick() }
    }

    private fun makeGridTestBtn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 11.8f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        background = roundedBg(Color.rgb(38, 24, 44), 11, strokeColor = Color.argb(85, 244, 63, 94), strokeDp = 1)
        setPadding(dp(6), dp(9), dp(6), dp(9)); minHeight = 0; minimumHeight = 0
        setOnClickListener { onClick() }
    }

    private fun gridLp(startMargin: Int = 0, endMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = startMargin; marginEnd = endMargin
        }

    private fun roundedBg(fillColor: Int, radiusDp: Int, strokeColor: Int = Color.TRANSPARENT, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fillColor)
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    companion object {
        private val BG_DARK = Color.rgb(11, 10, 17)
        private val CARD_DARK = Color.rgb(21, 18, 30)
    }
}
