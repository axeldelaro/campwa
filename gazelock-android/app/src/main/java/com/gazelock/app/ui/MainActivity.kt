package com.gazelock.app.ui

import android.Manifest
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
import com.gazelock.app.config.PenaltyMode
import com.gazelock.app.config.StrictnessPreset
import com.gazelock.app.media.VideoFeedRepository
import com.gazelock.app.overlay.AccessCodeWaitSpamView
import com.gazelock.app.service.GazeAccessibilityService
import com.gazelock.app.service.GazeSessionService

/**
 * GazeLock AI v2.0 — Fullscreen Unskippable Video Feed + Local On-Device Head & Iris Eye-Tracking:
 * - #2 Real Iris/Pupil Gaze Deviation Tracking via ML Kit 16-point Eye Contours + Y-plane centroid!
 * - #12 Fixed, Progressive (doubling), and Sudden Death (full timer reset!) penalty modes!
 * - #13 Total Anti-Escape Watchdog (blocks Settings, Force-Stop, Recents & Quick Settings + +15s penalty)!
 * - 3-Minute Imposed Wait + 0.1s Folder Image-Spam Gate to issue a single-use 6-digit access code!
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
            Toast.makeText(this, "Attente de 3 min en cours ! Appuie sur Annuler en bas si tu veux quitter.", Toast.LENGTH_SHORT).show()
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

    private fun launchGazeLockSession() {
        val cfg = gazeConfig.snapshot()
        if (!hasCameraPerm()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 302)
            Toast.makeText(this, "⚠️ Autorise la Caméra frontale pour le suivi du regard & iris !", Toast.LENGTH_LONG).show()
            return
        }
        if (!GazeAccessibilityService.isServiceActive() && !hasOverlayPerm()) {
            Toast.makeText(this, "⚠️ Active l'Accessibilité (recommandé pour l'Anti-Évasion) !", Toast.LENGTH_LONG).show()
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
                    Toast.makeText(this, "👁️ Fil Vidéo verrouillé (${list.size} vidéos · ${formatDuration(cfg.durationSec)}) !", Toast.LENGTH_SHORT).show()
                    mainHandler.postDelayed({ GazeSessionService.startSession(applicationContext) }, 180L)
                }
            }
        }.start()
    }

    private fun refreshUi() {
        val cfg = gazeConfig.snapshot()
        contentBox.removeAllViews()

        // Header
        val hdr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(12)) }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(this).apply {
            text = "👁️ GazeLock AI v2.0"
            setTextColor(Color.WHITE); textSize = 22.5f; typeface = Typeface.DEFAULT_BOLD
        })
        titleCol.addView(TextView(this).apply {
            text = "Fil Vidéo Verrouillé · Suivi Iris IA · Anti-Évasion Total"
            setTextColor(Color.rgb(216, 180, 254)); textSize = 11.4f
        })
        hdr.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val a11yOk = GazeAccessibilityService.isServiceActive()
        hdr.addView(TextView(this).apply {
            text = if (a11yOk) "⚡ A11Y ACTIF" else "⚠️ A11Y OFF"
            setTextColor(if (a11yOk) Color.rgb(167, 243, 208) else Color.rgb(253, 224, 71))
            textSize = 10.6f; typeface = Typeface.DEFAULT_BOLD
            background = roundedBg(if (a11yOk) Color.argb(45, 16, 185, 129) else Color.argb(45, 245, 158, 11), 12)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setOnClickListener { openAccessibilitySettings() }
        })
        contentBox.addView(hdr)

        // 1. Primary Launch & Duration Card
        val launchCard = makeCard(accentStroke = Color.argb(180, 225, 29, 72))
        launchCard.addView(sectionTitle("🎬 Durée du Fil Vidéo Verrouillé : ${formatDuration(cfg.durationSec)}"))
        launchCard.addView(sectionSub("Chrono figé dès que tu tournes la tête, détournes les pupilles (Iris) ou fermes les yeux. Sortie anticipée bloquée derrière 3 min d'attente + spam images 0,1 s !"))

        val durRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(5)) }
        listOf(60 to "1 min", 180 to "3 min", 300 to "5 min").forEachIndexed { idx, (sec, lbl) ->
            durRow1.addView(makeChip(lbl, cfg.durationSec == sec) {
                gazeConfig.update { putInt("duration_sec", sec) }; refreshUi()
            }, gridLp(if (idx > 0) dp(4) else 0, if (idx < 2) dp(4) else 0))
        }
        launchCard.addView(durRow1)

        val durRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(8)) }
        listOf(600 to "10 min", 900 to "15 min", 1800 to "30 min").forEachIndexed { idx, (sec, lbl) ->
            durRow2.addView(makeChip(lbl, cfg.durationSec == sec) {
                gazeConfig.update { putInt("duration_sec", sec) }; refreshUi()
            }, gridLp(if (idx > 0) dp(4) else 0, if (idx < 2) dp(4) else 0))
        }
        launchCard.addView(durRow2)

        launchCard.addView(makeSliderRow("Durée personnalisée", 1, 60, (cfg.durationSec / 60).coerceIn(1, 60), " min") { min ->
            gazeConfig.update { putInt("duration_sec", min * 60) }
        })

        launchCard.addView(makeAccentButton("👁️🎬 LANCER LE FIL VIDÉO (${formatDuration(cfg.durationSec)})", Color.rgb(225, 29, 72)) {
            launchGazeLockSession()
        }.apply {
            textSize = 14.5f; setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        })
        contentBox.addView(launchCard)

        // 2. Live Local AI Head + Iris Calibration Tester
        val calibCard = makeCard(accentStroke = Color.argb(150, 16, 185, 129))
        calibCard.addView(sectionTitle("📷 Test & Calibration Regard + Pupilles/Iris IA"))
        val cTv = TextView(this).apply {
            text = "Teste en direct la détection de tes angles de tête ET du mouvement de tes pupilles (Iris) — essaie de regarder à côté sans bouger la tête !"
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

        // 3. Video Folder & Spam Image Source Card
        val folderCard = makeCard(accentStroke = Color.argb(145, 168, 85, 247))
        folderCard.addView(sectionTitle("📂 Dossier Vidéos & Images Spam 0,1 s (.* inclus)"))
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

        folderCard.addView(makeSwitchRow("🔀 Ordre aléatoire (fil style TikTok)", cfg.shuffleVideos) {
            gazeConfig.update { putBoolean("shuffle", it) }
        })
        folderCard.addView(makeSwitchRow("⬆️ Autoriser glisser vers le haut (vidéo suivante)", cfg.allowSwipeNext) {
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

        // 4. #2 Iris Tracking, #12 Progressive / Sudden Death Penalties & #13 Anti-Escape Card
        val rulesCard = makeCard(accentStroke = Color.argb(155, 245, 158, 11))
        rulesCard.addView(sectionTitle("👁️ Sévérité IA (#2 Iris · #12 Mort Subite · #13 Anti-Évasion)"))
        val preRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(8)) }
        StrictnessPreset.values().forEachIndexed { idx, p ->
            preRow.addView(makeChip("${p.emoji} ${p.label}", cfg.preset == p) {
                gazeConfig.applyPreset(p)
                Toast.makeText(this, "Profil ${p.label} appliqué ${p.emoji}", Toast.LENGTH_SHORT).show()
                refreshUi()
            }, gridLp(if (idx > 0) dp(4) else 0, if (idx < 2) dp(4) else 0))
        }
        rulesCard.addView(preRow)

        // #12 Penalty Mode Selector!
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
            rulesCard.addView(smallLabel(if (cfg.penaltyMode == PenaltyMode.PROGRESSIVE) "Pénalité de départ (double à chaque écart) :" else "Pénalité fixe ajoutée au chrono :"))
            val penRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(6)) }
            listOf(0 to "0 s", 3 to "+3 s", 5 to "+5 s", 10 to "+10 s").forEachIndexed { idx, (sec, lbl) ->
                penRow.addView(makeChip(lbl, cfg.lookAwayPenaltySec == sec) {
                    gazeConfig.update { putInt("penalty_sec", sec) }; refreshUi()
                }, gridLp(if (idx > 0) dp(3) else 0, if (idx < 3) dp(3) else 0))
            }
            rulesCard.addView(penRow)
        }

        // #2 Iris Tracking & Head Angle Sliders
        rulesCard.addView(makeSwitchRow("👁️ #2 Suivi Précis des Pupilles / Iris (détecte regard de côté tête fixe)", cfg.irisTrackingEnabled) {
            gazeConfig.update { putBoolean("iris_en_v2", it) }
            calibrationTracker?.updateConfig(gazeConfig.snapshot())
        })
        rulesCard.addView(makeSliderRow("Sensibilité Pupilles/Iris (plus bas = ultra-strict)", 18, 52, cfg.irisThresholdPct, " %") { pct ->
            gazeConfig.update { putInt("iris_pct_v2", pct) }
            calibrationTracker?.updateConfig(gazeConfig.snapshot())
        })
        rulesCard.addView(makeSliderRow("Tolérance rotation tête (Yaw/Pitch)", 12, 38, cfg.yawThresholdDeg, "°") { deg ->
            gazeConfig.update { putInt("yaw_deg", deg); putInt("pitch_deg", (deg + 2).coerceAtMost(40)) }
            calibrationTracker?.updateConfig(gazeConfig.snapshot())
        })

        // #13 Anti-Escape Total Switch!
        rulesCard.addView(makeSwitchRow("🚨 #13 Anti-Évasion Total (bloque Paramètres, Récents & Force-Stop +15 s)", cfg.antiEscapeLockEnabled) {
            gazeConfig.update { putBoolean("anti_escape_v2", it) }
        })
        rulesCard.addView(makeSwitchRow("⏸️ Geler aussi la vidéo quand tu détournes le regard", cfg.pauseVideoOnLookAway) {
            gazeConfig.update { putBoolean("pause_video_away", it) }
        })
        rulesCard.addView(makeSwitchRow("🙈 Exiger les yeux ouverts (détecte yeux fermés)", cfg.requireEyesOpen) {
            gazeConfig.update { putBoolean("eyes_open", it) }
            calibrationTracker?.updateConfig(gazeConfig.snapshot())
        })
        rulesCard.addView(makeSwitchRow("🗣️ Alerte vocale hors ligne (« Regarde l'écran ! »)", cfg.voiceAlertEnabled) {
            gazeConfig.update { putBoolean("voice_alert", it) }
        })
        rulesCard.addView(makeSwitchRow("📳 Vibration d'alerte", cfg.vibrateAlertEnabled) {
            gazeConfig.update { putBoolean("vibrate_alert", it) }
        })
        rulesCard.addView(makeSwitchRow("🔊 Verrou Volume (empêche de couper le son à 0)", cfg.volumeLockEnabled) {
            gazeConfig.update { putBoolean("volume_lock", it) }
        })
        rulesCard.addView(makeSwitchRow("📊 Afficher télémétrie IA (Yaw/Pitch/Iris/Yeux)", cfg.showTelemetryBadge) {
            gazeConfig.update { putBoolean("show_telemetry", it) }
        })
        contentBox.addView(rulesCard)

        // 5. 3-Minute Imposed Wait + 0.1s Image-Spam Access Code Gate Card!
        val codeCard = makeCard(accentStroke = Color.argb(170, 192, 38, 211))
        codeCard.addView(sectionTitle("🔑 Code d'Accès (3 min imposées + Spam images 0,1 s)"))
        val curValidCode = gazeConfig.getActiveAccessCodeIfValid()
        codeCard.addView(sectionSub(
            if (curValidCode != null) {
                "✅ Code actif (valable < 2 min) : ${curValidCode.substring(0, 3)} ${curValidCode.substring(3)}\n" +
                    "Sinon, pour générer un code d'accès (ou quitter en urgence après 20 taps sur le chrono), il faut tenir 3 min sous le spam d'images (0,1 s) de ton dossier !"
            } else {
                "Aucun code actif. Pour obtenir un code d'accès unique (valable 2 min), tu dois attendre 3 minutes imposées avec un spam à 0,1 s des images du dossier des vidéos."
            }
        ))
        codeCard.addView(makeAccentButton("⏳ Obtenir le Code d'Accès (3 min + Spam images 0,1 s)", Color.rgb(162, 28, 175)) {
            launchThreeMinuteCodeWaitScreen()
        })
        contentBox.addView(codeCard)

        // 6. Optional Automatic Ambush Card
        val autoCard = makeCard(accentStroke = Color.argb(120, 14, 165, 233))
        autoCard.addView(sectionTitle("⏰ Embuscades Automatiques (via Accessibilité)"))
        autoCard.addView(makeSwitchRow("Déclencher un fil vidéo GazeLock automatiquement", cfg.autoTriggerEnabled) {
            gazeConfig.update { putBoolean("auto_en", it) }
        })
        autoCard.addView(makeSliderRow("Toutes les", 5, 60, cfg.autoTriggerEveryMin, " min d'écran") { m ->
            gazeConfig.update { putInt("auto_every_min", m) }
        })
        autoCard.addView(makeSliderRow("Durée de la session surprise", 1, 10, (cfg.autoTriggerDurationSec / 60).coerceIn(1, 10), " min") { m ->
            gazeConfig.update { putInt("auto_dur_sec", m * 60) }
        })
        contentBox.addView(autoCard)

        // 7. Permissions & Stats Card
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
            val imgNote = if (imgs > 0) "🖼️ $imgs image(s) pour spam 0,1 s" else "🎞️ frames vidéo auto pour spam 0,1 s"
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
    private fun sectionTitle(t: String) = TextView(this).apply { text = t; setTextColor(Color.WHITE); textSize = 14.4f; typeface = Typeface.DEFAULT_BOLD }
    private fun sectionSub(t: String) = TextView(this).apply { text = t; setTextColor(Color.rgb(168, 168, 192)); textSize = 11.4f; setPadding(0, dp(3), 0, dp(6)) }
    private fun smallLabel(t: String) = TextView(this).apply { text = t; setTextColor(Color.rgb(212, 212, 232)); textSize = 11.6f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(8), 0, dp(4)) }
    private fun makeSwitchRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label; isChecked = initial; setTextColor(Color.rgb(232, 232, 245)); textSize = 12f
        setPadding(0, dp(5), 0, dp(5)); setOnCheckedChangeListener { _, c -> onChange(c) }
    }
    private fun makeSliderRow(title: String, min: Int, max: Int, initial: Int, unit: String, onChange: (Int) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(5), 0, dp(4)) }
        val tv = TextView(this).apply { text = "$title : $initial$unit"; setTextColor(Color.rgb(216, 216, 232)); textSize = 11.8f }
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
        text = label; isAllCaps = false; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
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
        text = label; isAllCaps = false; textSize = 12.4f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        background = roundedBg(bg, 12); setPadding(dp(12), dp(11), dp(12), dp(11)); minHeight = 0; minimumHeight = 0; setOnClickListener { onClick() }
    }
    private fun makeSecondaryBtn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 11.4f; setTextColor(Color.rgb(225, 225, 240))
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
