package com.gazelock.app.ui

import android.Manifest
import android.app.AlertDialog
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
import com.gazelock.app.ai.GazeTrackerEngine
import com.gazelock.app.config.GazeConfig
import com.gazelock.app.config.GazeConfigSnapshot
import com.gazelock.app.config.OverlayMode
import com.gazelock.app.config.PenaltyMode
import com.gazelock.app.config.StrictnessPreset
import com.gazelock.app.media.VideoFeedRepository
import com.gazelock.app.overlay.AccessCodeWaitSpamView
import com.gazelock.app.service.GazeAccessibilityService
import com.gazelock.app.service.GazeSessionService

/**
 * GazeLock AI v3.0 — Semi-Transparent Ghost Overlay (45–50%, Touch-Unlocked) & Full-Lock Video Feed:
 * - Ghost Pass-Through Overlay: 45-50% video opacity over ALL apps (tactile unlocked!), removable ONLY via the
 *   3-minute imposed overlay portal (0.1s folder image spam + interactive pop-ups + Sliding Puzzle & Image Reconstruction)!
 * - #7 Subliminal Image Flashes (0.08s–0.22s) + Custom Neon Mantras!
 * - #8 Absolute Volume Lock (floor 40–100% + hardware Volume Down/Mute interception) + Forced 100% Brightness
 *   + Local Synthesized Hypnotic Binaural Pulse!
 * - #2 Real Iris/Pupil Tracking + #12 Fixed/Progressive/Sudden Death Penalties + #13 Anti-Escape Watchdog!
 */
class MainActivity : AppCompatActivity() {

    private lateinit var gazeConfig: GazeConfig
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var rootFrame: FrameLayout
    private lateinit var contentBox: LinearLayout
    private lateinit var spamWaitHost: FrameLayout

    private var videoCountTv: TextView? = null
    private var calibStatusTv: TextView? = null
    private var calibToggleBtn: Button? = null
    private var calibrationTracker: GazeTrackerEngine? = null
    private var calibrationActive = false
    private var activeMainWaitView: AccessCodeWaitSpamView? = null

    private val pickSafLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
        gazeConfig.update { putString("folder_path", ""); putString("tree_uri", uri.toString()) }
        Toast.makeText(this, "Dossier SAF enregistré ✅", Toast.LENGTH_SHORT).show()
        refreshUi()
    }

    private val stateRefresh: () -> Unit = {
        mainHandler.post { if (!isFinishing) refreshUi() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gazeConfig = GazeConfig(applicationContext)

        rootFrame = FrameLayout(this).apply { setBackgroundColor(BG_DARK) }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        contentBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(18), dp(15), dp(34))
        }
        scroll.addView(contentBox)

        spamWaitHost = FrameLayout(this).apply {
            visibility = View.GONE; isClickable = true; isFocusable = true
        }
        rootFrame.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        rootFrame.addView(spamWaitHost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(rootFrame)

        requestBasicPermissions()
        refreshUi()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (activeMainWaitView != null) {
            Toast.makeText(this, "Portail de 3 min en cours ! Appuie sur Annuler en bas si tu veux quitter.", Toast.LENGTH_SHORT).show()
            return
        }
        super.onBackPressed()
    }

    override fun onStart() {
        super.onStart()
        GazeAccessibilityService.addStateListener(stateRefresh)
        GazeSessionService.addStateListener(stateRefresh)
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun onPause() {
        stopCalibrationPreview()
        super.onPause()
    }

    override fun onStop() {
        GazeAccessibilityService.removeStateListener(stateRefresh)
        GazeSessionService.removeStateListener(stateRefresh)
        super.onStop()
    }

    override fun onDestroy() {
        stopCalibrationPreview()
        activeMainWaitView?.stopAndRelease(); activeMainWaitView = null
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun hasCameraPerm() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun hasOverlayPerm() = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun requestBasicPermissions() {
        val req = ArrayList<String>()
        if (!hasCameraPerm()) req.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) req.add(Manifest.permission.POST_NOTIFICATIONS)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) req.add(Manifest.permission.READ_MEDIA_VIDEO)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) req.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            req.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (req.isNotEmpty()) ActivityCompat.requestPermissions(this, req.toTypedArray(), 301)
    }

    // ==================== LIVE FRONT-CAMERA LOCAL AI HEAD + IRIS CALIBRATION ====================
    private fun toggleCalibrationPreview() {
        if (calibrationActive) {
            stopCalibrationPreview()
            calibToggleBtn?.text = "📷 Tester le Suivi Regard + Iris IA en direct"
            calibStatusTv?.text = "Appuie ci-dessous pour tester en direct tes angles de tête et la position de tes pupilles (Iris)."
            calibStatusTv?.setTextColor(Color.rgb(190, 190, 212))
            return
        }
        if (GazeSessionService.isSessionRunning) {
            Toast.makeText(this, "Une session GazeLock est déjà active !", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasCameraPerm()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 302)
            Toast.makeText(this, "Autorise la caméra frontale pour l'IA du regard !", Toast.LENGTH_LONG).show()
            return
        }

        calibrationActive = true
        calibToggleBtn?.text = "⏹️ Arrêter le Test Caméra IA"
        val tr = GazeTrackerEngine(
            context = applicationContext,
            onReading = { r ->
                mainHandler.post {
                    if (!calibrationActive) return@post
                    val statusLine = if (r.isLookingAtScreen) "✅ REGARD & IRIS FIXÉS — CHRONO ACTIF" else "⏸️ ${r.reason} — CHRONO FIGÉ !"
                    val detail = if (r.faceDetected) {
                        val irisTxt = if (r.irisMeasured) " • 👁️ Iris G/D : ${r.irisOffsetPct} %" else " • 👁️ Iris : calibrage…"
                        "\nYaw : ${r.yawDeg.toInt()}° • Pitch : ${r.pitchDeg.toInt()}°$irisTxt • Yeux : ${(r.eyeOpenAvg * 100).toInt()} %"
                    } else "\nAucun visage détecté devant la caméra frontale."
                    calibStatusTv?.text = "$statusLine$detail"
                    calibStatusTv?.setTextColor(if (r.isLookingAtScreen) Color.rgb(134, 239, 172) else Color.rgb(252, 165, 165))
                }
            },
            onCameraError = { err ->
                mainHandler.post { calibStatusTv?.text = "⚠️ $err"; calibStatusTv?.setTextColor(Color.rgb(253, 186, 116)) }
            }
        )
        calibrationTracker = tr
        tr.start(this, gazeConfig.snapshot())
    }

    private fun stopCalibrationPreview() {
        calibrationActive = false
        calibrationTracker?.shutdown()
        calibrationTracker = null
    }

    private fun launchThreeMinuteCodeWaitScreen() {
        if (GazeSessionService.isSessionRunning) {
            GazeSessionService.openUnlockPortalOverlay()
            return
        }
        val cfg = gazeConfig.snapshot()
        stopCalibrationPreview()
        spamWaitHost.removeAllViews()
        val wv = AccessCodeWaitSpamView(
            context = this,
            cfg = cfg,
            onIssueCode = { gazeConfig.issueUnlockedAccessCode() },
            onCodeUnlockedAndExit = null,
            onCancelWait = {
                activeMainWaitView?.stopAndRelease()
                activeMainWaitView = null
                spamWaitHost.removeAllViews()
                spamWaitHost.visibility = View.GONE
                refreshUi()
            }
        )
        activeMainWaitView = wv
        spamWaitHost.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        spamWaitHost.visibility = View.VISIBLE
        wv.startThreeMinuteSpamWait()
    }

    private fun showEditMantrasDialog(cfg: GazeConfigSnapshot) {
        val edit = EditText(this).apply {
            setText(cfg.subliminalMantras)
            minLines = 5; maxLines = 10
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "Une phrase par ligne…"
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.rgb(26, 22, 42))
        }
        AlertDialog.Builder(this)
            .setTitle("✨ Mantras Néon Subliminaux (1 par ligne)")
            .setView(edit)
            .setPositiveButton("Enregistrer") { _, _ ->
                val txt = edit.text?.toString()?.trim().orEmpty().ifBlank { GazeConfig.DEFAULT_MANTRAS_TEXT }
                gazeConfig.update { putString("subliminal_mantras_v3", txt) }
                Toast.makeText(this, "Mantras mis à jour ✅", Toast.LENGTH_SHORT).show()
                refreshUi()
            }
            .setNeutralButton("Réinitialiser") { _, _ ->
                gazeConfig.update { putString("subliminal_mantras_v3", GazeConfig.DEFAULT_MANTRAS_TEXT) }
                refreshUi()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun launchGazeLockSession() {
        val cfg = gazeConfig.snapshot()
        if (!hasCameraPerm()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 302)
            Toast.makeText(this, "⚠️ Autorise la Caméra frontale pour le suivi du regard & iris !", Toast.LENGTH_LONG).show()
            return
        }
        if (!GazeAccessibilityService.isServiceActive() && !hasOverlayPerm()) {
            Toast.makeText(this, "⚠️ Active l'Accessibilité (recommandé pour l'overlay semi-transparent fiable & le verrou volume) !", Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            return
        }
        if (cfg.folderPath.isBlank() && cfg.treeUri.isBlank()) {
            Toast.makeText(this, "📂 Sélectionne d'abord un dossier contenant des vidéos !", Toast.LENGTH_LONG).show()
            return
        }
        stopCalibrationPreview()
        Thread {
            val list = VideoFeedRepository.loadVideoPlaylist(applicationContext, cfg)
            mainHandler.post {
                if (list.isEmpty()) {
                    Toast.makeText(this, "⚠️ Aucune vidéo trouvée dans ce dossier !", Toast.LENGTH_LONG).show()
                } else {
                    val modeTxt = if (cfg.overlayMode == OverlayMode.GHOST_PASSTHROUGH) {
                        "👻 Overlay Semi-Transparent ${cfg.ghostOpacityPct}% (tactile libre)"
                    } else {
                        "🔒 Plein Écran Verrouillé"
                    }
                    val durTxt = if (cfg.infiniteUntilUnlock) "∞ Infini" else formatDuration(cfg.durationSec)
                    Toast.makeText(this, "$modeTxt · ${list.size} vidéos · $durTxt !", Toast.LENGTH_SHORT).show()
                    mainHandler.postDelayed({ GazeSessionService.startSession(applicationContext) }, 180L)
                }
            }
        }.start()
    }

    private fun refreshUi() {
        val cfg = gazeConfig.snapshot()
        val lockedNow = GazeSessionService.isSessionRunning
        contentBox.removeAllViews()

        // Header
        val hdr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(12)) }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(this).apply {
            text = "👁️ GazeLock AI v3.0"
            setTextColor(Color.WHITE); textSize = 22.5f; typeface = Typeface.DEFAULT_BOLD
        })
        titleCol.addView(TextView(this).apply {
            text = "Overlay Ghost 48 % · Subliminal #7 · Audio/Lum #8 · Mini-Jeux 3 min"
            setTextColor(Color.rgb(216, 180, 254)); textSize = 11f
        })
        hdr.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val a11yOk = GazeAccessibilityService.isServiceActive()
        hdr.addView(TextView(this).apply {
            text = if (a11yOk) "⚡ A11Y ACTIF" else "⚠️ A11Y OFF"
            setTextColor(if (a11yOk) Color.rgb(167, 243, 208) else Color.rgb(253, 224, 71))
            textSize = 10.6f; typeface = Typeface.DEFAULT_BOLD
            background = roundedBg(if (a11yOk) Color.argb(45, 16, 185, 129) else Color.argb(45, 245, 158, 11), 12)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setOnClickListener { if (!lockedNow) openAccessibilitySettings() }
        })
        contentBox.addView(hdr)

        // ==================== ACTIVE SESSION LOCKDOWN BANNER (ZERO BYPASS!) ====================
        if (lockedNow) {
            val activeCard = makeCard(accentStroke = Color.rgb(225, 29, 72))
            activeCard.addView(sectionTitle("🔒 SESSION EN COURS — VIDÉOS VERROUILLÉES"))
            activeCard.addView(sectionSub(
                "Les vidéos tournent en overlay. Les réglages sont figés et aucun bouton d'arrêt direct n'existe.\n" +
                    "Seul le créneau imposé de 3 minutes en overlay (spam 0,1 s + pop-ups + puzzle à glissement & reconstitution d'image) permet de retirer les vidéos !"
            ))
            activeCard.addView(makeAccentButton("⏳ OUVRIR LE PORTAIL 3 MIN EN OVERLAY (Mini-Jeux)", Color.rgb(162, 28, 175)) {
                GazeSessionService.openUnlockPortalOverlay()
            }.apply { textSize = 13.5f; setPadding(dp(14), dp(14), dp(14), dp(14)) })
            contentBox.addView(activeCard)
            return
        }

        // ==================== 1. OVERLAY MODE (GHOST 45-50% vs FULL-LOCK) & LAUNCH CARD ====================
        val launchCard = makeCard(accentStroke = Color.argb(200, 225, 29, 72))
        launchCard.addView(sectionTitle("👻 Mode d'Affichage Overlay"))
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(4)) }
        OverlayMode.values().forEachIndexed { idx, m ->
            modeRow.addView(makeChip(m.label, cfg.overlayMode == m) {
                gazeConfig.update { putString("overlay_mode_v3", m.id) }; refreshUi()
            }, gridLp(if (idx > 0) dp(4) else 0, if (idx < 1) dp(4) else 0))
        }
        launchCard.addView(modeRow)
        launchCard.addView(TextView(this).apply {
            text = "→ ${cfg.overlayMode.desc}"
            setTextColor(Color.rgb(216, 180, 254)); textSize = 11.2f; setPadding(0, 0, 0, dp(6))
        })

        if (cfg.overlayMode == OverlayMode.GHOST_PASSTHROUGH) {
            launchCard.addView(smallLabel("Opacité de la vidéo en surimpression (tactile 100 % déverrouillé) :"))
            val opRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(4)) }
            listOf(45 to "45 %", 48 to "48 % (Idéal)", 50 to "50 %", 55 to "55 %").forEachIndexed { idx, (pct, lbl) ->
                opRow.addView(makeChip(lbl, cfg.ghostOpacityPct == pct) {
                    gazeConfig.update { putInt("ghost_opacity_v3", pct) }; refreshUi()
                }, gridLp(if (idx > 0) dp(3) else 0, if (idx < 3) dp(3) else 0))
            }
            launchCard.addView(opRow)
            launchCard.addView(makeSliderRow("Opacité précise Ghost", 35, 65, cfg.ghostOpacityPct, " %") { pct ->
                gazeConfig.update { putInt("ghost_opacity_v3", pct) }
            })
        }

        val durHeader = if (cfg.infiniteUntilUnlock) "🎬 Durée : ∞ Infini (Sortie 3 min uniquement)" else "🎬 Durée : ${formatDuration(cfg.durationSec)}"
        launchCard.addView(smallLabel(durHeader))

        val durRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(5)) }
        listOf(180 to "3 min", 300 to "5 min", 600 to "10 min").forEachIndexed { idx, (sec, lbl) ->
            durRow1.addView(makeChip(lbl, !cfg.infiniteUntilUnlock && cfg.durationSec == sec) {
                gazeConfig.update { putBoolean("infinite_unlock_v3", false); putInt("duration_sec", sec) }; refreshUi()
            }, gridLp(if (idx > 0) dp(4) else 0, if (idx < 2) dp(4) else 0))
        }
        launchCard.addView(durRow1)

        val durRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(6)) }
        durRow2.addView(makeChip("15 min", !cfg.infiniteUntilUnlock && cfg.durationSec == 900) {
            gazeConfig.update { putBoolean("infinite_unlock_v3", false); putInt("duration_sec", 900) }; refreshUi()
        }, gridLp(endMargin = dp(4)))
        durRow2.addView(makeChip("30 min", !cfg.infiniteUntilUnlock && cfg.durationSec == 1800) {
            gazeConfig.update { putBoolean("infinite_unlock_v3", false); putInt("duration_sec", 1800) }; refreshUi()
        }, gridLp(startMargin = dp(4), endMargin = dp(4)))
        durRow2.addView(makeChip("∞ Infini (3m)", cfg.infiniteUntilUnlock) {
            gazeConfig.update { putBoolean("infinite_unlock_v3", true) }; refreshUi()
        }, gridLp(startMargin = dp(4)))
        launchCard.addView(durRow2)

        if (!cfg.infiniteUntilUnlock) {
            launchCard.addView(makeSliderRow("Durée personnalisée", 1, 60, (cfg.durationSec / 60).coerceIn(1, 60), " min") { min ->
                gazeConfig.update { putInt("duration_sec", min * 60) }
            })
        }

        val ctaLabel = if (cfg.overlayMode == OverlayMode.GHOST_PASSTHROUGH) {
            val d = if (cfg.infiniteUntilUnlock) "∞" else formatDuration(cfg.durationSec)
            "👻🎬 LANCER OVERLAY SEMI-TRANSPARENT (${cfg.ghostOpacityPct}% · $d)"
        } else {
            val d = if (cfg.infiniteUntilUnlock) "∞" else formatDuration(cfg.durationSec)
            "🔒🎬 LANCER PLEIN ÉCRAN VERROUILLÉ ($d)"
        }
        launchCard.addView(makeAccentButton(ctaLabel, Color.rgb(225, 29, 72)) {
            launchGazeLockSession()
        }.apply {
            textSize = 13.8f; setPadding(dp(12), dp(14), dp(12), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        })
        contentBox.addView(launchCard)

        // ==================== 2. #7 SUBLIMINAL IMAGE FLASHES & NEON MANTRAS ====================
        val subCard = makeCard(accentStroke = Color.argb(170, 217, 70, 239))
        subCard.addView(sectionTitle("⚡ #7 Flashs Subliminaux (0,08 s–0,22 s) & Mantras Néon"))
        subCard.addView(sectionSub("Injecte aléatoirement par-dessus la vidéo des flashs ultra-courts d'images du dossier + tes phrases néon (fonctionne aussi en mode Semi-Transparent sans gêner le tactile !)."))
        subCard.addView(makeSwitchRow("✨ Activer les flashs subliminaux en pleine vidéo", cfg.subliminalEnabled) {
            gazeConfig.update { putBoolean("subliminal_en_v3", it) }
        })
        subCard.addView(makeSliderRow("Fréquence moyenne (toutes les)", 2, 20, cfg.subliminalIntervalSec, " s") { v ->
            gazeConfig.update { putInt("subliminal_int_v3", v) }
        })
        subCard.addView(makeSliderRow("Durée d'un flash subliminal", 80, 220, cfg.subliminalDurationMs, " ms") { v ->
            gazeConfig.update { putInt("subliminal_dur_v3", v) }
        })
        subCard.addView(makeSliderRow("Opacité du flash subliminal", 30, 95, cfg.subliminalAlphaPct, " %") { v ->
            gazeConfig.update { putInt("subliminal_alpha_v3", v) }
        })
        subCard.addView(makeSecondaryBtn("✏️ Éditer les mantras néon (${cfg.mantraList().size} phrases actives)") {
            showEditMantrasDialog(cfg)
        })
        contentBox.addView(subCard)

        // ==================== 3. #8 ABSOLUTE VOLUME LOCK, MAX BRIGHTNESS & BINAURAL PULSE ====================
        val audioCard = makeCard(accentStroke = Color.argb(170, 14, 165, 233))
        audioCard.addView(sectionTitle("🔊 #8 Audio Verrouillé, Luminosité Max & Battement Hypnotique"))
        audioCard.addView(sectionSub("Intercepte physiquement les boutons Volume Bas/Muet via l'Accessibilité, force la luminosité à 100 % et synthétise un battement binaural basse fréquence en local."))
        audioCard.addView(makeSwitchRow("🔒 Verrou Volume Absolu + Blocage Boutons Volume Bas/Muet", cfg.volumeLockEnabled) {
            gazeConfig.update { putBoolean("volume_lock", it) }
        })
        audioCard.addView(makeSliderRow("Plancher minimum du volume média", 40, 100, cfg.volumeFloorPct, " %") { v ->
            gazeConfig.update { putInt("volume_floor_v3", v) }
        })
        audioCard.addView(makeSwitchRow("☀️ Forcer la luminosité de l'overlay à 100 %", cfg.forceMaxBrightness) {
            gazeConfig.update { putBoolean("force_brightness_v3", it) }
        })
        audioCard.addView(makeSwitchRow("🎧 Battement Binaural / Pulsation Hypnotique locale", cfg.binauralBeatEnabled) {
            gazeConfig.update { putBoolean("binaural_en_v3", it) }
        })
        audioCard.addView(makeSliderRow("Intensité du battement hypnotique", 10, 80, cfg.binauralVolumePct, " %") { v ->
            gazeConfig.update { putInt("binaural_vol_v3", v) }
        })
        audioCard.addView(makeSliderRow("Fréquence Binaurale (4–7 Hz Theta · 8–12 Hz Alpha)", 4, 12, cfg.binauralFreqHz, " Hz") { v ->
            gazeConfig.update { putInt("binaural_hz_v3", v) }
        })
        contentBox.addView(audioCard)

        // ==================== 4. 3-MINUTE OVERLAY UNLOCK PORTAL (POP-UPS & MINI-GAMES!) ====================
        val codeCard = makeCard(accentStroke = Color.argb(175, 192, 38, 211))
        codeCard.addView(sectionTitle("🧩 Portail de Déblocage 3 min en Overlay (Pop-ups & Mini-Jeux)"))
        val curValidCode = gazeConfig.getActiveAccessCodeIfValid()
        codeCard.addView(sectionSub(
            if (curValidCode != null) {
                "✅ Code actif (< 2 min) : ${curValidCode.substring(0, 3)} ${curValidCode.substring(3)}\n" +
                    "Pour retirer les vidéos hors fin de chrono : 3 min en overlay avec spam 0,1 s + pop-ups + Puzzle à glissement 3×3 & Reconstitution d'image !"
            } else {
                "Seul moyen d'arrêter les vidéos en cours : 3 minutes imposées en overlay avec spam 0,1 s des images du dossier, pop-ups chronométrés et mini-jeux (Taquin 3×3 + Échange & Rotation d'image)."
            }
        ))
        codeCard.addView(makeSwitchRow("🔔 Pop-ups interactifs pendant les 3 min (+8–10 s si ignorés)", cfg.unlockPopupsEnabled) {
            gazeConfig.update { putBoolean("unlock_popups_v3", it) }
        })
        codeCard.addView(makeSwitchRow("🧩 Mini-jeux obligatoires (Puzzle glissement + Reconstitution)", cfg.unlockMinigamesEnabled) {
            gazeConfig.update { putBoolean("unlock_minigames_v3", it) }
        })
        codeCard.addView(makeAccentButton("⏳ Lancer le Portail 3 min (Spam 0,1 s + Pop-ups + Mini-Jeux)", Color.rgb(162, 28, 175)) {
            launchThreeMinuteCodeWaitScreen()
        })
        contentBox.addView(codeCard)

        // ==================== 5. VIDEO & IMAGE FOLDER SOURCE CARD ====================
        val folderCard = makeCard(accentStroke = Color.argb(145, 168, 85, 247))
        folderCard.addView(sectionTitle("📂 Dossier Vidéos & Images (.* & .nomedia inclus)"))
        val vcTv = TextView(this).apply {
            text = "⏳ Analyse du dossier…"
            setTextColor(Color.rgb(216, 180, 254)); textSize = 11.4f; typeface = Typeface.MONOSPACE
            background = roundedBg(Color.rgb(26, 20, 42), 10); setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        videoCountTv = vcTv
        folderCard.addView(vcTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6); bottomMargin = dp(8) })

        folderCard.addView(makeAccentButton("🔍 Parcourir dossiers (masqués .* & .nomedia inclus)", Color.rgb(147, 51, 234)) {
            if (!VideoFeedRepository.hasAllFilesAccess()) {
                Toast.makeText(this, "Autorise « Accès à tous les fichiers » pour voir les dossiers masqués !", Toast.LENGTH_LONG).show()
                requestAllFilesAccess()
            } else {
                FolderPickerDialog.show(this, cfg.folderPath) { chosen ->
                    gazeConfig.update { putString("folder_path", chosen); putString("tree_uri", "") }
                    refreshUi()
                }
            }
        })
        folderCard.addView(makeSecondaryBtn("📁 Ou sélecteur Android standard (SAF)") { pickSafLauncher.launch(null) })

        folderCard.addView(makeSwitchRow("🔀 Ordre aléatoire (fil continu)", cfg.shuffleVideos) {
            gazeConfig.update { putBoolean("shuffle", it) }
        })
        folderCard.addView(makeSwitchRow("⏭ Autoriser passer à la vidéo suivante", cfg.allowSwipeNext) {
            gazeConfig.update { putBoolean("swipe_next", it) }
        })
        folderCard.addView(smallLabel("Changement auto de vidéo dans le fil :"))
        val clipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(0 to "Complète", 15 to "15 s", 30 to "30 s", 60 to "60 s").forEachIndexed { idx, (sec, lbl) ->
            clipRow.addView(makeChip(lbl, cfg.maxClipSec == sec) {
                gazeConfig.update { putInt("max_clip_sec", sec) }; refreshUi()
            }, gridLp(if (idx > 0) dp(3) else 0, if (idx < 3) dp(3) else 0))
        }
        folderCard.addView(clipRow)
        contentBox.addView(folderCard)
        refreshVideoCountAsync(cfg)

        // ==================== 6. LIVE AI HEAD + IRIS CALIBRATION TESTER ====================
        val calibCard = makeCard(accentStroke = Color.argb(150, 16, 185, 129))
        calibCard.addView(sectionTitle("📷 Test & Calibration Regard + Pupilles/Iris IA"))
        val cTv = TextView(this).apply {
            text = "Teste en direct la détection de tes angles de tête ET du mouvement de tes pupilles (Iris)."
            setTextColor(Color.rgb(190, 190, 212)); textSize = 11.4f
            background = roundedBg(Color.rgb(24, 20, 38), 11); setPadding(dp(10), dp(9), dp(10), dp(9))
        }
        calibStatusTv = cTv
        calibCard.addView(cTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6); bottomMargin = dp(8) })
        val cBtn = makeAccentButton(
            if (calibrationActive) "⏹️ Arrêter le Test Caméra IA" else "📷 Tester le Suivi Regard + Iris IA en direct",
            Color.rgb(16, 128, 96)
        ) { toggleCalibrationPreview() }
        calibToggleBtn = cBtn
        calibCard.addView(cBtn)
        contentBox.addView(calibCard)

        // ==================== 7. #2 IRIS, #12 PENALTIES & #13 ANTI-ESCAPE ====================
        val rulesCard = makeCard(accentStroke = Color.argb(155, 245, 158, 11))
        rulesCard.addView(sectionTitle("👁️ Sévérité IA (#2 Iris · #12 Sanction · #13 Anti-Évasion)"))
        val preRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(8)) }
        StrictnessPreset.values().forEachIndexed { idx, p ->
            preRow.addView(makeChip("${p.emoji} ${p.label}", cfg.preset == p) {
                gazeConfig.applyPreset(p)
                Toast.makeText(this, "Profil ${p.label} appliqué ${p.emoji}", Toast.LENGTH_SHORT).show()
                refreshUi()
            }, gridLp(if (idx > 0) dp(4) else 0, if (idx < 2) dp(4) else 0))
        }
        rulesCard.addView(preRow)

        rulesCard.addView(smallLabel("Mode de Sanction si regard détourné (#12) :"))
        val pmRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(4)) }
        PenaltyMode.values().forEachIndexed { idx, m ->
            pmRow.addView(makeChip(m.label, cfg.penaltyMode == m) {
                gazeConfig.update { putString("penalty_mode_v2", m.id) }; refreshUi()
            }, gridLp(if (idx > 0) dp(3) else 0, if (idx < 2) dp(3) else 0))
        }
        rulesCard.addView(pmRow)
        rulesCard.addView(TextView(this).apply {
            text = "→ ${cfg.penaltyMode.desc}"
            setTextColor(Color.rgb(253, 224, 71)); textSize = 10.8f; setPadding(0, 0, 0, dp(6))
        })

        if (cfg.penaltyMode != PenaltyMode.SUDDEN_DEATH) {
            rulesCard.addView(smallLabel(if (cfg.penaltyMode == PenaltyMode.PROGRESSIVE) "Pénalité de départ (double à chaque écart) :" else "Pénalité fixe ajoutée :"))
            val penRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(6)) }
            listOf(0 to "0 s", 3 to "+3 s", 5 to "+5 s", 10 to "+10 s").forEachIndexed { idx, (sec, lbl) ->
                penRow.addView(makeChip(lbl, cfg.lookAwayPenaltySec == sec) {
                    gazeConfig.update { putInt("penalty_sec", sec) }; refreshUi()
                }, gridLp(if (idx > 0) dp(3) else 0, if (idx < 3) dp(3) else 0))
            }
            rulesCard.addView(penRow)
        }

        rulesCard.addView(makeSwitchRow("👁️ #2 Suivi Précis des Pupilles / Iris", cfg.irisTrackingEnabled) {
            gazeConfig.update { putBoolean("iris_en_v2", it) }
            calibrationTracker?.updateConfig(gazeConfig.snapshot())
        })
        rulesCard.addView(makeSliderRow("Sensibilité Pupilles/Iris (plus bas = strict)", 18, 52, cfg.irisThresholdPct, " %") { pct ->
            gazeConfig.update { putInt("iris_pct_v2", pct) }
            calibrationTracker?.updateConfig(gazeConfig.snapshot())
        })
        rulesCard.addView(makeSliderRow("Tolérance rotation tête (Yaw/Pitch)", 12, 38, cfg.yawThresholdDeg, "°") { deg ->
            gazeConfig.update { putInt("yaw_deg", deg); putInt("pitch_deg", (deg + 2).coerceAtMost(40)) }
            calibrationTracker?.updateConfig(gazeConfig.snapshot())
        })
        rulesCard.addView(makeSwitchRow("🚨 #13 Anti-Évasion (bloque Paramètres, Désinstallation & Force-Stop +15 s)", cfg.antiEscapeLockEnabled) {
            gazeConfig.update { putBoolean("anti_escape_v2", it) }
        })
        rulesCard.addView(makeSwitchRow("⏸️ Geler aussi la vidéo quand tu détournes le regard", cfg.pauseVideoOnLookAway) {
            gazeConfig.update { putBoolean("pause_video_away", it) }
        })
        rulesCard.addView(makeSwitchRow("🙈 Exiger les yeux ouverts", cfg.requireEyesOpen) {
            gazeConfig.update { putBoolean("eyes_open", it) }
            calibrationTracker?.updateConfig(gazeConfig.snapshot())
        })
        rulesCard.addView(makeSwitchRow("🗣️ Alerte vocale hors ligne", cfg.voiceAlertEnabled) {
            gazeConfig.update { putBoolean("voice_alert", it) }
        })
        rulesCard.addView(makeSwitchRow("📳 Vibration d'alerte", cfg.vibrateAlertEnabled) {
            gazeConfig.update { putBoolean("vibrate_alert", it) }
        })
        rulesCard.addView(makeSwitchRow("📊 Afficher télémétrie IA (mode Verrouillé)", cfg.showTelemetryBadge) {
            gazeConfig.update { putBoolean("show_telemetry", it) }
        })
        contentBox.addView(rulesCard)

        // ==================== 8. OPTIONAL AUTOMATIC AMBUSH ====================
        val autoCard = makeCard(accentStroke = Color.argb(120, 14, 165, 233))
        autoCard.addView(sectionTitle("⏰ Embuscades Automatiques (via Accessibilité)"))
        autoCard.addView(makeSwitchRow("Déclencher l'overlay GazeLock automatiquement", cfg.autoTriggerEnabled) {
            gazeConfig.update { putBoolean("auto_en", it) }
        })
        autoCard.addView(makeSliderRow("Toutes les", 5, 60, cfg.autoTriggerEveryMin, " min d'écran") { m ->
            gazeConfig.update { putInt("auto_every_min", m) }
        })
        autoCard.addView(makeSliderRow("Durée de la session surprise", 1, 10, (cfg.autoTriggerDurationSec / 60).coerceIn(1, 10), " min") { m ->
            gazeConfig.update { putInt("auto_dur_sec", m * 60) }
        })
        contentBox.addView(autoCard)

        // ==================== 9. PERMISSIONS & STATS ====================
        val permCard = makeCard(accentStroke = Color.argb(120, 16, 185, 129))
        permCard.addView(sectionTitle("🛡️ Autorisations & Statistiques"))
        val pRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(5)) }
        pRow1.addView(makePermChip("Accessibilité", GazeAccessibilityService.isServiceActive()) { openAccessibilitySettings() }, gridLp(endMargin = dp(4)))
        pRow1.addView(makePermChip("Caméra IA", hasCameraPerm()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 302)
        }, gridLp(startMargin = dp(4)))
        permCard.addView(pRow1)

        val pRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pRow2.addView(makePermChip("Fichiers .*", VideoFeedRepository.hasAllFilesAccess()) { requestAllFilesAccess() }, gridLp(endMargin = dp(4)))
        pRow2.addView(makePermChip("Superposition", hasOverlayPerm()) { openOverlaySettings() }, gridLp(startMargin = dp(4)))
        permCard.addView(pRow2)

        if (!GazeAccessibilityService.isServiceActive() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permCard.addView(makeSecondaryBtn("ℹ️ Android 13+ grisé ? Infos Appli (⋮ → Paramètres restreints)") {
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Throwable) {}
            })
        }

        val totalMin = cfg.totalFocusedSec / 60L
        permCard.addView(TextView(this).apply {
            text = "\n📊 Sessions accomplies : ${cfg.sessionsCompleted} • Temps fixé : $totalMin min ${cfg.totalFocusedSec % 60L} s\n" +
                "👀 Écarts regard : ${cfg.totalLookAways} • 🚨 Évasions bloquées : ${cfg.escapeBlocksCount} • 🔥 Record : ${cfg.bestStreakSec} s"
            setTextColor(Color.rgb(210, 210, 232)); textSize = 11.4f
        })
        contentBox.addView(permCard)
    }

    private fun refreshVideoCountAsync(cfg: GazeConfigSnapshot) {
        val tv = videoCountTv ?: return
        Thread {
            val (vids, imgs) = VideoFeedRepository.countMediaInConfig(applicationContext, cfg)
            val imgNote = if (imgs > 0) "🖼️ $imgs image(s) (spam, subliminal & puzzles)" else "🎞️ frames vidéo auto (spam, subliminal & puzzles)"
            val text = when {
                cfg.folderPath.isNotBlank() -> "${cfg.folderPath}\n→ 🎬 $vids vidéo(s) • $imgNote"
                cfg.treeUri.isNotBlank() -> "Dossier SAF\n→ 🎬 $vids vidéo(s) • $imgNote"
                else -> "Aucun dossier choisi — sélectionne ton dossier de vidéos ci-dessous !"
            }
            mainHandler.post { tv.text = text }
        }.start()
    }

    private fun openAccessibilitySettings() { try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Throwable) {} }
    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: Throwable) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
        }
    }
    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: Throwable) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 303)
    }
    private fun formatDuration(sec: Int) = if (sec % 60 == 0) "${sec / 60} min" else String.format("%d:%02d", sec / 60, sec % 60)

    private fun makeCard(accentStroke: Int = Color.argb(40, 255, 255, 255)) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBg(CARD_DARK, 18, strokeColor = accentStroke, strokeDp = 1)
        setPadding(dp(14), dp(13), dp(14), dp(13))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
    }
    private fun sectionTitle(t: String) = TextView(this).apply { text = t; setTextColor(Color.WHITE); textSize = 14.2f; typeface = Typeface.DEFAULT_BOLD }
    private fun sectionSub(t: String) = TextView(this).apply { text = t; setTextColor(Color.rgb(168, 168, 192)); textSize = 11.3f; setPadding(0, dp(3), 0, dp(6)) }
    private fun smallLabel(t: String) = TextView(this).apply { text = t; setTextColor(Color.rgb(212, 212, 232)); textSize = 11.5f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(8), 0, dp(4)) }
    private fun makeSwitchRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label; isChecked = initial; setTextColor(Color.rgb(232, 232, 245)); textSize = 11.8f
        setPadding(0, dp(5), 0, dp(5)); setOnCheckedChangeListener { _, c -> onChange(c) }
    }
    private fun makeSliderRow(title: String, min: Int, max: Int, initial: Int, unit: String, onChange: (Int) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(5), 0, dp(4)) }
        val tv = TextView(this).apply { text = "$title : $initial$unit"; setTextColor(Color.rgb(216, 216, 232)); textSize = 11.6f }
        val sb = SeekBar(this).apply {
            this.max = (max - min).coerceAtLeast(1); progress = (initial - min).coerceIn(0, this.max)
            thumbTintList = ColorStateList.valueOf(Color.rgb(225, 29, 72))
            progressTintList = ColorStateList.valueOf(Color.rgb(225, 29, 72))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = min + p; tv.text = "$title : $v$unit"; if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(tv); box.addView(sb); return box
    }
    private fun makeChip(label: String, active: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 10.8f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (active) Color.WHITE else Color.rgb(195, 195, 215))
        background = roundedBg(if (active) Color.rgb(225, 29, 72) else Color.rgb(30, 25, 44), 11, strokeColor = if (active) Color.rgb(253, 164, 175) else Color.argb(38, 255, 255, 255), strokeDp = 1)
        minHeight = 0; minimumHeight = 0; setPadding(dp(5), dp(8), dp(5), dp(8)); setOnClickListener { onClick() }
    }
    private fun makePermChip(label: String, ok: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = if (ok) "✅ $label" else "⚠️ $label"; isAllCaps = false; textSize = 11.2f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (ok) Color.rgb(167, 243, 208) else Color.rgb(254, 240, 138))
        background = roundedBg(if (ok) Color.argb(42, 16, 185, 129) else Color.argb(45, 245, 158, 11), 11, strokeColor = if (ok) Color.argb(120, 16, 185, 129) else Color.argb(120, 245, 158, 11), strokeDp = 1)
        minHeight = 0; minimumHeight = 0; setPadding(dp(6), dp(9), dp(6), dp(9)); setOnClickListener { onClick() }
    }
    private fun makeAccentButton(label: String, bg: Int, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 12.2f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        background = roundedBg(bg, 12); setPadding(dp(12), dp(11), dp(12), dp(11)); minHeight = 0; minimumHeight = 0; setOnClickListener { onClick() }
    }
    private fun makeSecondaryBtn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 11.3f; setTextColor(Color.rgb(225, 225, 240))
        background = roundedBg(Color.rgb(30, 26, 46), 11, strokeColor = Color.argb(42, 255, 255, 255), strokeDp = 1)
        setPadding(dp(10), dp(9), dp(10), dp(9)); minHeight = 0; minimumHeight = 0
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        setOnClickListener { onClick() }
    }
    private fun gridLp(startMargin: Int = 0, endMargin: Int = 0) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = startMargin; marginEnd = endMargin }
    private fun roundedBg(fill: Int, radiusDp: Int, strokeColor: Int = Color.TRANSPARENT, strokeDp: Int = 0) =
        GradientDrawable().apply { cornerRadius = dp(radiusDp).toFloat(); setColor(fill); if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor) }

    companion object {
        private val BG_DARK = Color.rgb(9, 7, 18)
        private val CARD_DARK = Color.rgb(20, 16, 32)
    }
}
