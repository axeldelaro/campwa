package com.demonpet.app.ui

import android.Manifest
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
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
import android.widget.ImageView
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
import com.demonpet.app.config.WallpaperTarget
import com.demonpet.app.media.MediaFolderHelper
import com.demonpet.app.overlay.DemonPetView
import com.demonpet.app.receiver.DemonDeviceAdminReceiver
import com.demonpet.app.service.DemonAccessibilityService
import com.demonpet.app.service.DemonCompanionService
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var settings: DemonSettings
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var rootFrame: FrameLayout
    private var lastRenderedLockState: Boolean? = null

    // Dynamic UI refs refreshed on resume / config updates
    private var moodBadgeTv: TextView? = null
    private var masterSwitch: Switch? = null
    private var lockSwitch: Switch? = null
    private var avatarPreviewIv: ImageView? = null
    private var avatarStatusTv: TextView? = null
    private var mediaFolderTv: TextView? = null
    private var restoreWallBtn: Button? = null
    private var watchedAppsBtn: Button? = null
    private var permAccessStatusTv: TextView? = null
    private var permAdminStatusTv: TextView? = null
    private var permFilesStatusTv: TextView? = null
    private var permOverlayStatusTv: TextView? = null
    private var permWriteStatusTv: TextView? = null
    private var lockedPetAnimView: DemonPetView? = null
    private var lockedTapHintTv: TextView? = null

    private val lockedAnimTick = object : Runnable {
        override fun run() {
            val pv = lockedPetAnimView
            if (pv != null && settings.challengeLocked) {
                pv.animPhase += 0.14f
                pv.invalidate()
                mainHandler.postDelayed(this, 32L)
            }
        }
    }

    private val cfgListener: (DemonConfigSnapshot) -> Unit = { snap ->
        mainHandler.post {
            if (lastRenderedLockState != snap.challengeLocked) {
                renderScreen(snap)
            } else {
                refreshLiveBadges(snap)
            }
        }
    }

    private val accessListener: () -> Unit = {
        mainHandler.post { refreshLiveBadges(settings.snapshot()) }
    }

    private val pickAvatarLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        Toast.makeText(this, "Découpage IA du visage en cours… ✨", Toast.LENGTH_SHORT).show()
        val includeHair = settings.cutoutIncludeHair
        Thread {
            val outPath = AvatarCutoutEngine.createAvatarFromUri(applicationContext, uri, includeHair)
            mainHandler.post {
                if (outPath != null) {
                    settings.avatarPath = outPath
                    Toast.makeText(this, "Visage détouré avec succès ! 😈", Toast.LENGTH_SHORT).show()
                    refreshAvatarPreview(outPath)
                } else {
                    Toast.makeText(this, "Impossible de lire cette photo.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private val pickSafTreeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Throwable) {
        }
        settings.update {
            putString("media_folder", "")
            putString("media_tree_uri", uri.toString())
        }
        Toast.makeText(this, "Dossier médias SAF sélectionné ✅", Toast.LENGTH_SHORT).show()
        refreshLiveBadges(settings.snapshot())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = DemonSettings(applicationContext)
        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(BG_DARK)
        }
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
        } else {
            refreshLiveBadges(snap)
        }
        if (snap.enabled && !DemonCompanionService.isRunning) {
            DemonCompanionService.start(applicationContext)
        }
    }

    override fun onStop() {
        super.onStop()
        DemonSettings.removeListener(cfgListener)
        DemonAccessibilityService.removeStateListener(accessListener)
        mainHandler.removeCallbacks(lockedAnimTick)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun renderScreen(snap: DemonConfigSnapshot) {
        lastRenderedLockState = snap.challengeLocked
        mainHandler.removeCallbacks(lockedAnimTick)
        lockedPetAnimView = null
        lockedTapHintTv = null
        rootFrame.removeAllViews()

        if (snap.challengeLocked) {
            rootFrame.addView(buildLockedChallengeGate(snap))
            mainHandler.post(lockedAnimTick)
        } else {
            rootFrame.addView(buildDashboard(snap))
            refreshLiveBadges(snap)
            refreshAvatarPreview(snap.avatarPath)
        }
    }

    // -------------------------------------------------------------------------
    // LOCKED CHALLENGE GATE (20 taps on the Demon + PIN 100205)
    // -------------------------------------------------------------------------
    private fun buildLockedChallengeGate(snap: DemonConfigSnapshot): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(BG_DARK)
            setPadding(dp(24), dp(36), dp(24), dp(36))
        }

        val title = TextView(this).apply {
            text = "🔒 ${snap.avatarName} garde le téléphone !"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        wrap.addView(title)

        val subtitle = TextView(this).apply {
            text = "Mode Verrouillage Défi actif (Admin + Accessibilité).\nPour déverrouiller les réglages et sortir :\ntapote le démon 20 fois de suite puis saisis le code."
            setTextColor(Color.rgb(212, 212, 216))
            textSize = 13.5f
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(22))
        }
        wrap.addView(subtitle)

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
                        tapCounter >= DemonSettings.REQUIRED_EXIT_TAPS -> "🔓 Code demandé !"
                        tapCounter >= 5 -> "Tapotes : $tapCounter / ${DemonSettings.REQUIRED_EXIT_TAPS}…"
                        else -> "Tapote le démon 20× de suite pour débloquer"
                    }
                }
            },
            onGentlePet = {},
            onSecretTwentyTaps = {
                showExitPinDialog()
            }
        ).apply {
            updateConfig(snap)
            pose = DemonPetView.PetPose.WALKING
            say("Tu ne peux pas m'enlever ! 😈", 4_000L)
        }
        lockedPetAnimView = petView

        val petCard = FrameLayout(this).apply {
            background = roundedBg(Color.rgb(24, 20, 34), 28, strokeColor = Color.argb(120, 244, 63, 94), strokeDp = 2)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            addView(petView, FrameLayout.LayoutParams(dp(180), dp(200), Gravity.CENTER))
        }
        wrap.addView(petCard, LinearLayout.LayoutParams(dp(230), dp(240)))

        val hintTv = TextView(this).apply {
            text = "Tapote le démon 20× de suite pour débloquer"
            setTextColor(Color.rgb(253, 164, 175))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(10))
        }
        lockedTapHintTv = hintTv
        wrap.addView(hintTv)

        val statusTv = TextView(this).apply {
            text = "Humeur : ${snap.mood.emoji} ${snap.mood.label} (${snap.moodScore}/100)\nTu peux aussi tapoter le démon flottant 20× depuis n'importe quel écran."
            setTextColor(Color.rgb(161, 161, 170))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        wrap.addView(statusTv)

        return wrap
    }

    private fun showExitPinDialog() {
        val input = EditText(this).apply {
            hint = "Code secret (ex: 100205)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(150, 150, 165))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBg(Color.rgb(32, 30, 44), 12)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(8))
            setBackgroundColor(Color.rgb(20, 18, 28))
            addView(TextView(this@MainActivity).apply {
                text = "Saisis le code de sortie pour libérer le téléphone, retirer l'Administrateur et mettre le démon en pause :"
                setTextColor(Color.rgb(228, 228, 231))
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
            putBoolean("enabled", false)
        }
        DemonCompanionService.stop(applicationContext)
        Toast.makeText(this, "DemonMom déverrouillé et Admin retiré ✅", Toast.LENGTH_LONG).show()
        renderScreen(settings.snapshot())
    }

    // -------------------------------------------------------------------------
    // FULL NATIVE CONTROL DASHBOARD
    // -------------------------------------------------------------------------
    private fun buildDashboard(snap: DemonConfigSnapshot): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG_DARK)
            isVerticalScrollBarEnabled = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(36))
        }
        scroll.addView(content)

        // 1. HERO HEADER + MASTER SWITCH + MOOD GAUGE (#21)
        content.addView(buildHeroCard(snap))

        // 2. AUTORISATIONS & VERROUILLAGE (Accessibility, Admin, All Files, Overlay, WriteSettings)
        content.addView(buildPermissionsCard())

        // 3. STUDIO AVATAR IA (#24)
        content.addView(buildAvatarStudioCard(snap))

        // 4. DOSSIER MÉDIAS (DOSSIERS MASQUÉS .* INCLUS) + RESTAURATION FOND D'ÉCRAN
        content.addView(buildMediaFolderCard(snap))

        // 5. TESTS EN DIRECT
        content.addView(buildLiveTestsCard())

        // 6. CENSURE IA JALOUSE (#1, #2, #3, #4, #5, #6)
        content.addView(buildAiCensorCard(snap))

        // 7. CONTRÔLE PARENTAL & COMPORTEMENTS (#7–#13, #19–#23)
        content.addView(buildParentalBehaviorsCard(snap))

        // 8. BÊTISES & CHAOS (#14, #15, #16, #25 — SANS #17 ni #18)
        content.addView(buildMischiefCard(snap))

        return scroll
    }

    private fun buildHeroCard(snap: DemonConfigSnapshot): View {
        val card = makeCard(accentStroke = Color.argb(120, 244, 63, 94))

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(this).apply {
            text = "😈 DemonMom AI"
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        })
        titleCol.addView(TextView(this).apply {
            text = "Avatar démon possessif 100 % local • 23 pouvoirs"
            setTextColor(Color.rgb(180, 175, 195))
            textSize = 12f
        })
        topRow.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val moodTv = TextView(this).apply {
            text = "${snap.mood.emoji} ${snap.mood.label} (${snap.moodScore}%)"
            setTextColor(Color.WHITE)
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            background = roundedBg(Color.rgb(45, 22, 38), 14, strokeColor = Color.rgb(244, 63, 94), strokeDp = 1)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                settings.update { putInt("mood_score", 20) }
                Toast.makeText(this@MainActivity, "Humeur calmée 😇", Toast.LENGTH_SHORT).show()
            }
        }
        moodBadgeTv = moodTv
        topRow.addView(moodTv)
        card.addView(topRow)

        card.addView(divider())

        // Master Switch
        val sw = Switch(this).apply {
            isChecked = snap.enabled
            text = "Activer le Démon sur tout l'écran"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setOnCheckedChangeListener { _, checked ->
                settings.enabled = checked
                if (checked) {
                    if (!DemonAccessibilityService.isEnabledInSettings(this@MainActivity) &&
                        !(Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this@MainActivity))
                    ) {
                        Toast.makeText(
                            this@MainActivity,
                            "Active d'abord l'Accessibilité ou la Superposition ci-dessous !",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    DemonCompanionService.start(applicationContext)
                } else {
                    DemonCompanionService.stop(applicationContext)
                }
            }
        }
        masterSwitch = sw
        card.addView(sw)

        // Challenge Lock Toggle (Device Admin + Accessibility + 20-tap 100205)
        val lockSw = Switch(this).apply {
            isChecked = snap.challengeLocked
            text = "🔒 Verrouillage Défi (Admin + Accessibilité)"
            setTextColor(Color.rgb(253, 164, 175))
            textSize = 13.5f
            setPadding(0, dp(10), 0, 0)
            setOnCheckedChangeListener { button, checked ->
                if (!button.isPressed) return@setOnCheckedChangeListener
                if (checked) {
                    button.isChecked = false
                    confirmAndEnableChallengeLock()
                } else {
                    settings.challengeLocked = false
                }
            }
        }
        lockSwitch = lockSw
        card.addView(lockSw)

        card.addView(TextView(this).apply {
            text = "Astuce sortie d'urgence : tapote le démon 20 fois de suite puis saisis le code 100205 (retire aussi l'Administrateur)."
            setTextColor(Color.rgb(161, 161, 170))
            textSize = 11.5f
            setPadding(0, dp(6), 0, 0)
        })

        return card
    }

    private fun confirmAndEnableChallengeLock() {
        val adminActive = DemonDeviceAdminReceiver.isAdminActive(this)
        val accessActive = DemonAccessibilityService.isEnabledInSettings(this)
        val msg = buildString {
            append("Une fois activé :\n")
            append("• L'application et les Paramètres de désinstallation sont protégés.\n")
            append("• Pour en sortir : tapote le démon 20 fois de suite + code 100205.\n\n")
            if (!accessActive) append("⚠️ Service d'Accessibilité non actif (recommandé).\n")
            if (!adminActive) append("⚠️ Administrateur de l'appareil non actif (tu peux l'activer maintenant).")
        }
        AlertDialog.Builder(this)
            .setTitle("🔒 Activer le Verrouillage Défi ?")
            .setMessage(msg)
            .setPositiveButton("Activer maintenant") { _, _ ->
                settings.update {
                    putBoolean("enabled", true)
                    putBoolean("challenge_locked", true)
                }
                DemonCompanionService.start(applicationContext)
                if (!adminActive) {
                    requestDeviceAdmin()
                }
                renderScreen(settings.snapshot())
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun buildPermissionsCard(): View {
        val card = makeCard()
        card.addView(sectionTitle("🛡️ Autorisations système"))
        card.addView(sectionSub("Indispensables pour l'avatar sur toutes les applis, les dossiers masqués (.*) et le verrouillage."))

        // 1. Accessibility
        val (accRow, accStatus) = makePermRow(
            title = "Service d'Accessibilité (Cœur IA + Overlay)",
            btnText = "Activer",
            onClick = {
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (_: Throwable) {
                    Toast.makeText(this, "Ouvre Paramètres → Accessibilité → DemonMom AI", Toast.LENGTH_LONG).show()
                }
            }
        )
        permAccessStatusTv = accStatus
        card.addView(accRow)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            card.addView(makeSmallSecondaryButton("ℹ️ Android 13+ grisé ? Ouvrir Infos Appli (⋮ → Paramètres restreints)") {
                openAppDetails()
            })
        }

        // 2. Device Admin
        val (admRow, admStatus) = makePermRow(
            title = "Administrateur de l'appareil (Anti-désinstallation)",
            btnText = "Activer",
            onClick = { requestDeviceAdmin() }
        )
        permAdminStatusTv = admStatus
        card.addView(admRow)

        // 3. All Files Access (for hidden .dotfolders)
        val (filesRow, filesStatus) = makePermRow(
            title = "Accès Tous les Fichiers (Dossiers masqués .*)",
            btnText = "Autoriser",
            onClick = { requestAllFilesAccess() }
        )
        permFilesStatusTv = filesStatus
        card.addView(filesRow)

        // 4. Overlay fallback
        val (ovRow, ovStatus) = makePermRow(
            title = "Superposition sur les autres applis",
            btnText = "Autoriser",
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    } catch (_: Throwable) {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                    }
                }
            }
        )
        permOverlayStatusTv = ovStatus
        card.addView(ovRow)

        // 5. Write Settings (night brightness #19)
        val (wsRow, wsStatus) = makePermRow(
            title = "Modifier paramètres système (Luminosité nuit #19)",
            btnText = "Autoriser",
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
                    } catch (_: Throwable) {
                        startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS))
                    }
                }
            }
        )
        permWriteStatusTv = wsStatus
        card.addView(wsRow)

        return card
    }

    private fun buildAvatarStudioCard(snap: DemonConfigSnapshot): View {
        val card = makeCard()
        card.addView(sectionTitle("🎭 Studio Avatar IA (#24)"))
        card.addView(sectionSub("Choisis la photo d'une personne : l'IA locale détoure son visage pour en faire ton petit démon vivant (et coller sa tête en mode #2)."))

        val previewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(10))
        }
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBg(Color.rgb(34, 28, 48), 44, strokeColor = Color.rgb(244, 63, 94), strokeDp = 2)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        avatarPreviewIv = iv
        previewRow.addView(iv, LinearLayout.LayoutParams(dp(84), dp(84)))

        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        val statusTv = TextView(this).apply {
            text = if (snap.avatarPath.isNotBlank()) "Visage IA prêt ✅" else "Aucune photo choisie"
            setTextColor(Color.rgb(228, 228, 231))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
        avatarStatusTv = statusTv
        rightCol.addView(statusTv)

        val pickBtn = makeAccentButton("📸 Choisir photo (Visage)") {
            pickAvatarLauncher.launch(arrayOf("image/*"))
        }
        rightCol.addView(pickBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        previewRow.addView(rightCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(previewRow)

        // Include Hair toggle
        card.addView(makeSwitchRow("Inclure les cheveux (détourage Silhouette IA)", snap.cutoutIncludeHair) { checked ->
            settings.cutoutIncludeHair = checked
            Thread {
                val rebuilt = AvatarCutoutEngine.rebuildFromSavedSource(applicationContext, checked)
                if (rebuilt != null) {
                    mainHandler.post {
                        settings.avatarPath = rebuilt
                        refreshAvatarPreview(rebuilt)
                        Toast.makeText(this, "Détourage mis à jour ✨", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        })

        // Avatar Style Chips
        card.addView(smallLabel("Style du corps :"))
        card.addView(makeChipGroup(
            items = AvatarStyle.values().toList(),
            selected = snap.avatarStyle,
            labelOf = { it.label }
        ) { chosen -> settings.avatarStyle = chosen })

        // Avatar Size Slider
        card.addView(makeSliderRow(
            title = "Taille de l'avatar",
            min = 64, max = 136, initial = snap.avatarSizeDp, unit = " dp"
        ) { settings.avatarSizeDp = it })

        // Custom Name
        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val nameEt = EditText(this).apply {
            setText(snap.avatarName)
            hint = "Prénom du démon (ex: Maman Démon)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(130, 130, 148))
            textSize = 13.5f
            background = roundedBg(Color.rgb(28, 25, 40), 10)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val saveNameBtn = makeSmallActionButton("OK") {
            settings.avatarName = nameEt.text?.toString().orEmpty()
            Toast.makeText(this, "Nom enregistré ✅", Toast.LENGTH_SHORT).show()
        }
        nameRow.addView(nameEt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nameRow.addView(saveNameBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8)
        })
        card.addView(nameRow)

        return card
    }

    private fun buildMediaFolderCard(snap: DemonConfigSnapshot): View {
        val card = makeCard(accentStroke = Color.argb(95, 168, 85, 247))
        card.addView(sectionTitle("📂 Dossier Médias (Dossiers masqués .* inclus)"))
        card.addView(sectionSub("Utilisé pour les fonds d'écran (#14), les pop-ups tirées (#15) et les flashs plein écran (#16). Supporte les dossiers cachés (.secret) et .nomedia."))

        val folderTv = TextView(this).apply {
            setTextColor(Color.rgb(216, 180, 254))
            textSize = 12.5f
            typeface = Typeface.MONOSPACE
            background = roundedBg(Color.rgb(28, 22, 42), 10)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        mediaFolderTv = folderTv
        card.addView(folderTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })

        val pickHiddenBtn = makeAccentButton("🔍 Parcourir dossiers (masqués .* & .nomedia inclus)", Color.rgb(147, 51, 234)) {
            if (!MediaFolderHelper.hasAllFilesAccess()) {
                Toast.makeText(this, "Autorise d'abord « Accès à tous les fichiers » pour voir les dossiers masqués (.*) !", Toast.LENGTH_LONG).show()
                requestAllFilesAccess()
            } else {
                FolderPickerDialog.show(this, settings.mediaFolderPath) { chosenPath ->
                    settings.mediaFolderPath = chosenPath
                    Toast.makeText(this, "Dossier sélectionné ✅", Toast.LENGTH_SHORT).show()
                    refreshLiveBadges(settings.snapshot())
                }
            }
        }
        card.addView(pickHiddenBtn)

        card.addView(makeSmallSecondaryButton("📁 Ou utiliser le sélecteur Android classique (SAF)") {
            pickSafTreeLauncher.launch(null)
        })

        card.addView(makeSwitchRow("Inclure fichiers cachés (.* / .nomedia)", snap.includeHiddenFiles) {
            settings.includeHiddenFiles = it
            refreshLiveBadges(settings.snapshot())
        })

        val restoreBtn = makeSmallSecondaryButton("♻️ Restaurer mon fond d'écran d'origine") {
            Thread {
                val ok = MediaFolderHelper.restoreOriginalWallpaper(applicationContext)
                mainHandler.post {
                    Toast.makeText(
                        this,
                        if (ok) "Fond d'écran d'origine restauré ✅" else "Aucune sauvegarde de fond d'écran trouvée.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }.start()
        }
        restoreWallBtn = restoreBtn
        card.addView(restoreBtn)

        return card
    }

    private fun buildLiveTestsCard(): View {
        val card = makeCard()
        card.addView(sectionTitle("🧪 Tester les bêtises en direct"))
        card.addView(sectionSub("Déclenche immédiatement une action pour vérifier ton avatar et ton dossier de photos."))

        fun runTest(kind: String) {
            if (!settings.enabled) {
                settings.enabled = true
                DemonCompanionService.start(applicationContext)
            }
            val acc = DemonAccessibilityService.activeInstance
            if (acc != null) {
                acc.triggerManualTest(kind)
                return
            }
            val comp = DemonCompanionService.instance
            if (comp != null) {
                comp.triggerFallbackTest(kind)
                return
            }
            DemonCompanionService.start(applicationContext)
            mainHandler.postDelayed({
                DemonAccessibilityService.activeInstance?.triggerManualTest(kind)
                    ?: DemonCompanionService.instance?.triggerFallbackTest(kind)
                    ?: Toast.makeText(this, "Active l'Accessibilité ou la Superposition d'abord !", Toast.LENGTH_LONG).show()
            }, 600L)
        }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(makeGridTestBtn("🖼️ Pop-up tirée (#15)") { runTest("popup") }, gridLp(endMargin = dp(6)))
        row1.addView(makeGridTestBtn("⚡ Flash écran (#16)") { runTest("flash") }, gridLp(startMargin = dp(6)))
        card.addView(row1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(makeGridTestBtn("🎨 Fond d'écran (#14)") { runTest("wallpaper") }, gridLp(endMargin = dp(6)))
        row2.addView(makeGridTestBtn("🔒 Blocage + Négo (#8)") { runTest("lock") }, gridLp(startMargin = dp(6)))
        card.addView(row2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        return card
    }

    private fun buildAiCensorCard(snap: DemonConfigSnapshot): View {
        val card = makeCard()
        card.addView(sectionTitle("👀 Censure IA Jalouse & NSFW (#1–#6)"))
        card.addView(sectionSub("Analyse locale temps réel (ML Kit + NudeNet ONNX) via le Service d'Accessibilité."))

        card.addView(smallLabel("Mode sur les visages détectés :"))
        card.addView(makeChipGroup(
            items = FaceCensorMode.values().toList(),
            selected = snap.faceCensorMode,
            labelOf = { it.label }
        ) { settings.faceCensorMode = it })

        card.addView(smallLabel("Filtre jalousie (#3) :"))
        card.addView(makeChipGroup(
            items = GenderFilter.values().toList(),
            selected = snap.faceGenderFilter,
            labelOf = { it.label }
        ) { settings.faceGenderFilter = it })

        card.addView(makeSwitchRow("Bouclier NSFW dramatique « PAS DE ÇA ICI ! » (#4)", snap.nsfwShieldEnabled) {
            settings.nsfwShieldEnabled = it
        })

        return card
    }

    private fun buildParentalBehaviorsCard(snap: DemonConfigSnapshot): View {
        val card = makeCard()
        card.addView(sectionTitle("🤱 Contrôle Parental « Maman Démon » (#7–#13, #19–#23)"))
        card.addView(sectionSub("Restrictions possessives, négociation, couvre-feu, devoirs et réactions contextuelles."))

        val appsBtn = makeSmallSecondaryButton("📱 Choisir les applis surveillées (${snap.watchedPackages.size})") {
            AppPickerDialog.show(
                context = this,
                title = "Applis surveillées par le démon",
                initialSelected = settings.watchedPackages
            ) { updated ->
                settings.watchedPackages = updated
                refreshLiveBadges(settings.snapshot())
            }
        }
        watchedAppsBtn = appsBtn
        card.addView(appsBtn)

        card.addView(makeSwitchRow("#7 « Demande la permission ! » à l'ouverture", snap.askPermissionGate) {
            settings.askPermissionGate = it
        })
        card.addView(makeSwitchRow("#8 Quota temps limite par appli", snap.appQuotaLockEnabled) {
            settings.appQuotaLockEnabled = it
        })
        card.addView(makeSliderRow("Durée du quota", 2, 60, snap.appQuotaMinutes, " min") {
            settings.appQuotaMinutes = it
        })
        card.addView(makeSwitchRow("#9 Mini-jeux de négociation (+10 min : caresse / fond / maths)", snap.negotiationMiniGames) {
            settings.negotiationMiniGames = it
        })

        card.addView(divider())
        card.addView(makeSwitchRow("#10 Couvre-feu « Au lit ! » (bonnet de nuit)", snap.curfewEnabled) {
            settings.curfewEnabled = it
        })
        card.addView(makeSliderRow("Début couvre-feu", 0, 23, snap.curfewStartHour, "h") {
            settings.curfewStartHour = it
        })
        card.addView(makeSliderRow("Fin couvre-feu", 0, 23, snap.curfewEndHour, "h") {
            settings.curfewEndHour = it
        })

        card.addView(divider())
        card.addView(makeSwitchRow("#11 Mode « Fais tes devoirs ! » (bloque jeux & réseaux)", snap.homeworkModeEnabled) {
            settings.homeworkModeEnabled = it
        })
        card.addView(makeSwitchRow("#12 Anti-doomscroll (s'assoit au milieu de l'écran)", snap.antiDoomscrollEnabled) {
            settings.antiDoomscrollEnabled = it
        })
        card.addView(makeSwitchRow("#13 Panique Paramètres & Désinstallation", snap.settingsPanicEnabled) {
            settings.settingsPanicEnabled = it
        })
        card.addView(makeSwitchRow("#19 Baisse luminosité la nuit (22h–6h)", snap.nightBrightnessEnabled) {
            settings.nightBrightnessEnabled = it
        })
        card.addView(makeSwitchRow("#20 Alerte batterie faible (< 20 %)", snap.lowBatteryPanicEnabled) {
            settings.lowBatteryPanicEnabled = it
        })
        card.addView(makeSwitchRow("#22 Commentaires en direct selon l'appli", snap.appCommentsEnabled) {
            settings.appCommentsEnabled = it
        })
        card.addView(makeSwitchRow("#23 Alerte vitesse route (GPS passif)", snap.speedWarningEnabled) {
            settings.speedWarningEnabled = it
        })
        card.addView(makeSliderRow("Seuil alerte vitesse", 10, 90, snap.speedThresholdKmh, " km/h") {
            settings.speedThresholdKmh = it
        })

        return card
    }

    private fun buildMischiefCard(snap: DemonConfigSnapshot): View {
        val card = makeCard()
        card.addView(sectionTitle("🔥 Bêtises, Pop-ups & Chaos (#14–#16, #25)"))
        card.addView(sectionSub("Règle la fréquence des apparitions, fonds d'écran surprises et phrases personnalisées."))

        card.addView(makeSliderRow("Niveau de chaos", 1, 10, snap.chaosLevel, "/10") {
            settings.chaosLevel = it
        })
        card.addView(makeSliderRow("Intervalle min entre bêtises", 15, 300, snap.minIntervalSec, " s") {
            settings.minIntervalSec = it
        })

        card.addView(divider())
        card.addView(makeSwitchRow("#14 Changement aléatoire de fond d'écran", snap.wallpaperPrankEnabled) {
            settings.wallpaperPrankEnabled = it
        })
        card.addView(smallLabel("Cible du fond d'écran :"))
        card.addView(makeChipGroup(
            items = WallpaperTarget.values().toList(),
            selected = snap.wallpaperTarget,
            labelOf = { it.label }
        ) { settings.wallpaperTarget = it })

        card.addView(divider())
        card.addView(makeSwitchRow("#15 Pop-ups photo tirées par le démon", snap.draggedPopupEnabled) {
            settings.draggedPopupEnabled = it
        })
        card.addView(makeSliderRow("Nombre max de pop-ups simultanées", 1, 4, snap.maxPopups, "") {
            settings.maxPopups = it
        })

        card.addView(divider())
        card.addView(makeSwitchRow("#16 Flash photo plein écran", snap.fullscreenFlashEnabled) {
            settings.fullscreenFlashEnabled = it
        })
        card.addView(makeSliderRow("Durée du flash", 500, 5000, snap.flashDurationMs, " ms") {
            settings.flashDurationMs = it
        })

        card.addView(makeSwitchRow("Vibrer lors des bêtises", snap.vibrateOnPrank) {
            settings.vibrateOnPrank = it
        })

        card.addView(smallLabel("Phrases personnalisées (1 par ligne) :"))
        val phrasesEt = EditText(this).apply {
            setText(snap.customPhrases)
            hint = "Ex:\nRange ta chambre !\nJe te surveille 👀\nC'est qui sur cette photo ?"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(120, 120, 140))
            textSize = 12.5f
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            background = roundedBg(Color.rgb(26, 24, 38), 10)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        card.addView(phrasesEt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
            bottomMargin = dp(8)
        })
        card.addView(makeSmallSecondaryButton("💾 Enregistrer mes phrases") {
            settings.customPhrases = phrasesEt.text?.toString().orEmpty()
            Toast.makeText(this, "Phrases enregistrées ✅", Toast.LENGTH_SHORT).show()
        })

        return card
    }

    // -------------------------------------------------------------------------
    // LIVE REFRESH & SYSTEM HELPERS
    // -------------------------------------------------------------------------
    private fun refreshLiveBadges(snap: DemonConfigSnapshot) {
        moodBadgeTv?.text = "${snap.mood.emoji} ${snap.mood.label} (${snap.moodScore}%)"
        if (masterSwitch?.isChecked != snap.enabled) masterSwitch?.isChecked = snap.enabled
        if (lockSwitch?.isChecked != snap.challengeLocked) lockSwitch?.isChecked = snap.challengeLocked
        watchedAppsBtn?.text = "📱 Choisir les applis surveillées (${snap.watchedPackages.size})"

        val accessOk = DemonAccessibilityService.isEnabledInSettings(this)
        setPermStatus(permAccessStatusTv, accessOk, if (DemonAccessibilityService.isServiceActive()) "Actif ✅" else if (accessOk) "Activé ✅" else "Inactif")

        val adminOk = DemonDeviceAdminReceiver.isAdminActive(this)
        setPermStatus(permAdminStatusTv, adminOk, if (adminOk) "Actif ✅" else "Inactif")

        val filesOk = MediaFolderHelper.hasAllFilesAccess()
        setPermStatus(permFilesStatusTv, filesOk, if (filesOk) "Autorisé ✅" else "Requis pour .*")

        val ovOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        setPermStatus(permOverlayStatusTv, ovOk, if (ovOk) "Autorisé ✅" else "Inactif")

        val wsOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(this)
        setPermStatus(permWriteStatusTv, wsOk, if (wsOk) "Autorisé ✅" else "Optionnel")

        restoreWallBtn?.visibility = if (MediaFolderHelper.hasWallpaperBackup(this)) View.VISIBLE else View.GONE

        Thread {
            val count = MediaFolderHelper.countConfiguredImages(applicationContext, snap)
            val label = when {
                snap.mediaFolderPath.isNotBlank() -> "${snap.mediaFolderPath}\n→ $count photo${if (count > 1) "s" else ""} détectée${if (count > 1) "s" else ""}"
                snap.mediaTreeUri.isNotBlank() -> "Dossier SAF sélectionné\n→ $count photo${if (count > 1) "s" else ""} détectée${if (count > 1) "s" else ""}"
                else -> "Aucun dossier choisi (utilise l'avatar par défaut)"
            }
            mainHandler.post { mediaFolderTv?.text = label }
        }.start()
    }

    private fun refreshAvatarPreview(path: String) {
        val iv = avatarPreviewIv ?: return
        if (path.isNotBlank() && File(path).exists()) {
            val bmp = try { BitmapFactory.decodeFile(path) } catch (_: Throwable) { null }
            if (bmp != null) {
                iv.setImageBitmap(bmp)
                avatarStatusTv?.text = "Visage IA détouré ✅"
                return
            }
        }
        iv.setImageResource(android.R.drawable.ic_menu_camera)
        avatarStatusTv?.text = "Aucune photo choisie"
    }

    private fun setPermStatus(tv: TextView?, ok: Boolean, text: String) {
        tv ?: return
        tv.text = text
        tv.setTextColor(if (ok) Color.rgb(52, 211, 153) else Color.rgb(251, 191, 36))
    }

    private fun requestDeviceAdmin() {
        try {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, DemonDeviceAdminReceiver.getComponentName(this@MainActivity))
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Empêche la désinstallation tant que le mode Défi DemonMom AI est actif (sortie : 20 taps + 100205)."
                )
            }
            startActivity(intent)
        } catch (_: Throwable) {
            Toast.makeText(this, "Impossible d'ouvrir l'Administrateur de l'appareil.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Throwable) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 202)
        }
    }

    private fun openAppDetails() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: Throwable) {
        }
    }

    private fun requestBasicRuntimePermissionsIfNeeded() {
        val req = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            req.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            req.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (req.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, req.toTypedArray(), 201)
        }
    }

    // -------------------------------------------------------------------------
    // UI BUILDER HELPERS
    // -------------------------------------------------------------------------
    private fun makeCard(accentStroke: Int = Color.argb(45, 255, 255, 255)): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(CARD_DARK, 18, strokeColor = accentStroke, strokeDp = 1)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            }
        }
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 15.5f
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun sectionSub(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.rgb(161, 161, 175))
        textSize = 12f
        setPadding(0, dp(3), 0, dp(6))
    }

    private fun smallLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.rgb(212, 212, 220))
        textSize = 12.5f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(Color.argb(30, 255, 255, 255))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(10)
            bottomMargin = dp(10)
        }
    }

    private fun makePermRow(title: String, btnText: String, onClick: () -> Unit): Pair<View, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 13f
        })
        val statusTv = TextView(this).apply {
            text = "…"
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
        }
        col.addView(statusTv)
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(makeSmallActionButton(btnText, onClick))
        return Pair(row, statusTv)
    }

    private fun makeSwitchRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit): Switch {
        return Switch(this).apply {
            text = label
            isChecked = initial
            setTextColor(Color.rgb(235, 235, 242))
            textSize = 13f
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
    }

    private fun makeSliderRow(title: String, min: Int, max: Int, initial: Int, unit: String, onChange: (Int) -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(4))
        }
        val labelTv = TextView(this).apply {
            text = "$title : $initial$unit"
            setTextColor(Color.rgb(212, 212, 224))
            textSize = 12.5f
        }
        val sb = SeekBar(this).apply {
            this.max = (max - min).coerceAtLeast(1)
            progress = (initial - min).coerceIn(0, this.max)
            thumbTintList = ColorStateList.valueOf(Color.rgb(244, 63, 94))
            progressTintList = ColorStateList.valueOf(Color.rgb(244, 63, 94))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = min + progress
                    labelTv.text = "$title : $v$unit"
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        box.addView(labelTv)
        box.addView(sb)
        return box
    }

    private fun <T> makeChipGroup(items: List<T>, selected: T, labelOf: (T) -> String, onSelect: (T) -> Unit): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(4))
        }
        var current = selected
        val buttons = ArrayList<Pair<T, Button>>()

        fun styleAll() {
            for ((item, b) in buttons) {
                val active = item == current
                b.background = roundedBg(
                    if (active) Color.rgb(225, 29, 72) else Color.rgb(34, 30, 48),
                    12,
                    strokeColor = if (active) Color.rgb(253, 164, 175) else Color.argb(40, 255, 255, 255),
                    strokeDp = 1
                )
                b.setTextColor(if (active) Color.WHITE else Color.rgb(212, 212, 224))
            }
        }

        // 2 buttons per row for crisp wrapping on all phone widths
        val chunked = items.chunked(2)
        for (rowItems in chunked) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for ((idx, item) in rowItems.withIndex()) {
                val btn = Button(this).apply {
                    text = labelOf(item)
                    isAllCaps = false
                    textSize = 12f
                    minHeight = 0
                    minimumHeight = 0
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    setOnClickListener {
                        current = item
                        styleAll()
                        onSelect(item)
                    }
                }
                buttons.add(Pair(item, btn))
                row.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx == 0 && rowItems.size > 1) marginEnd = dp(6)
                    if (idx == 1) marginStart = dp(6)
                })
            }
            wrap.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            })
        }
        styleAll()
        return wrap
    }

    private fun makeAccentButton(label: String, bgColor: Int = Color.rgb(225, 29, 72), onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = roundedBg(bgColor, 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minHeight = 0
            minimumHeight = 0
            setOnClickListener { onClick() }
        }
    }

    private fun makeSmallActionButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = roundedBg(Color.rgb(225, 29, 72), 10)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            minHeight = 0
            minimumHeight = 0
            setOnClickListener { onClick() }
        }
    }

    private fun makeSmallSecondaryButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            setTextColor(Color.rgb(228, 228, 236))
            background = roundedBg(Color.rgb(34, 31, 48), 10, strokeColor = Color.argb(55, 255, 255, 255), strokeDp = 1)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            minHeight = 0
            minimumHeight = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
                bottomMargin = dp(4)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun makeGridTestBtn(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = roundedBg(Color.rgb(42, 26, 46), 12, strokeColor = Color.argb(95, 244, 63, 94), strokeDp = 1)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            minHeight = 0
            minimumHeight = 0
            setOnClickListener { onClick() }
        }
    }

    private fun gridLp(startMargin: Int = 0, endMargin: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = startMargin
            marginEnd = endMargin
        }
    }

    private fun roundedBg(fillColor: Int, radiusDp: Int, strokeColor: Int = Color.TRANSPARENT, strokeDp: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fillColor)
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }
    }

    companion object {
        private val BG_DARK = Color.rgb(13, 11, 18)
        private val CARD_DARK = Color.rgb(23, 20, 32)
    }
}
