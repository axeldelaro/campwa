package com.aigallery.pro.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.aigallery.pro.ai.VisualPreferenceAiEngine
import com.aigallery.pro.data.GalleryDatabase
import com.aigallery.pro.data.GalleryPreferences
import com.aigallery.pro.data.MediaItem
import com.aigallery.pro.data.MediaRepository
import com.aigallery.pro.data.MediaType
import com.bumptech.glide.Glide
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fullscreen Simple Gallery Pro Media Viewer (Photos, GIFs & Videos):
 * - Horizontal ViewPager2 swipe with Pinch-to-Zoom + Double-Tap Zoom on photos/GIFs.
 * - Full native Video Player with Play/Pause, Scrubber SeekBar, Mute & Loop controls.
 * - Immersive tap-to-hide chrome, Slideshow mode, Favorite/AI Learning, Built-in Photo Editor,
 *   Share via FileProvider, Detailed EXIF + Recognized Persons + AI Reason dialog, Rename & Recycle Bin!
 */
class MediaViewerActivity : AppCompatActivity() {

    private lateinit var db: GalleryDatabase
    private lateinit var prefs: GalleryPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    private val items = ArrayList<MediaItem>()
    private lateinit var pager: ViewPager2
    private lateinit var adapter: ViewerPagerAdapter
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var titleTv: TextView
    private lateinit var subTv: TextView
    private lateinit var aiBadgeTv: TextView
    private lateinit var favBtnTv: TextView
    private lateinit var slideshowBtnTv: TextView

    private var chromeVisible = true
    private var slideshowActive = false
    private var activeVideoHolder: ViewerPagerAdapter.PageVH? = null

    private val slideshowRunnable = object : Runnable {
        override fun run() {
            if (!slideshowActive || items.size <= 1) return
            val next = (pager.currentItem + 1) % items.size
            pager.setCurrentItem(next, true)
            mainHandler.postDelayed(this, prefs.slideshowIntervalSec * 1000L)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = GalleryDatabase(applicationContext)
        prefs = GalleryPreferences(applicationContext)

        val paths = sharedMediaPaths
        val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)
        if (paths.isEmpty()) {
            finish(); return
        }

        val metaMap = db.loadAllMediaMeta()
        val personMap = db.loadMediaToPersonsMap()
        for (p in paths) {
            val f = File(p)
            if (!f.exists()) continue
            val t = MediaRepository.detectMediaType(f.name) ?: MediaType.PHOTO
            val m = metaMap[p]
            items.add(
                MediaItem(
                    path = p,
                    name = f.name,
                    folderPath = f.parentFile?.absolutePath.orEmpty(),
                    folderName = f.parentFile?.name.orEmpty(),
                    type = t,
                    sizeBytes = f.length(),
                    dateModifiedSec = f.lastModified() / 1000L,
                    isFavorite = m?.isFavorite == true,
                    swipeRating = m?.swipeRating ?: 0,
                    aiScorePct = m?.aiScorePct ?: -1,
                    aiReason = m?.aiReason.orEmpty(),
                    personIds = personMap[p].orEmpty(),
                    faceCount = m?.faceCount ?: 0
                )
            )
        }
        if (items.isEmpty()) { finish(); return }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        pager = ViewPager2(this)
        adapter = ViewerPagerAdapter()
        pager.adapter = adapter
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                activeVideoHolder?.stopVideo()
                activeVideoHolder = null
                refreshTopAndBottomChrome()
            }
        })
        root.addView(pager, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Top Bar
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(215, 0, 0, 0), Color.TRANSPARENT))
            setPadding(dp(10), dp(36), dp(10), dp(14))
        }
        topBar.addView(makeBarIcon("←") { finish() })

        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(6), 0) }
        titleTv = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1 }
        subTv = TextView(this).apply { setTextColor(Color.rgb(200, 200, 215)); textSize = 11f; maxLines = 1 }
        titleCol.addView(titleTv); titleCol.addView(subTv)
        topBar.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        aiBadgeTv = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(4), dp(8), dp(4))
            visibility = View.GONE
            setOnClickListener { showMediaDetailsDialog() }
        }
        topBar.addView(aiBadgeTv)

        slideshowBtnTv = makeBarIcon("🎞️") { toggleSlideshow() }
        topBar.addView(slideshowBtnTv)
        topBar.addView(makeBarIcon("⋮") { showMoreActionsDialog() })
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // Iconic Simple Gallery Pro 5-Action Bottom Bar
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.argb(225, 0, 0, 0), Color.TRANSPARENT))
            setPadding(dp(8), dp(14), dp(8), dp(24))
        }
        favBtnTv = makeBottomAction("🤍", "Favori") { toggleCurrentFavorite() }
        bottomBar.addView(favBtnTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottomBar.addView(makeBottomAction("✏️", "Éditer") { openEditorForCurrent() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottomBar.addView(makeBottomAction("📤", "Partager") { shareCurrent() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottomBar.addView(makeBottomAction("ℹ️", "EXIF/IA") { showMediaDetailsDialog() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottomBar.addView(makeBottomAction("🗑️", "Suppr.") { deleteCurrent() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bottomBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        setContentView(root)
        pager.setCurrentItem(startIndex.coerceIn(0, items.lastIndex), false)
        refreshTopAndBottomChrome()
    }

    override fun onPause() {
        slideshowActive = false
        mainHandler.removeCallbacks(slideshowRunnable)
        activeVideoHolder?.pauseVideo()
        super.onPause()
    }

    private fun currentItem(): MediaItem? = items.getOrNull(pager.currentItem)

    private fun toggleChrome() {
        chromeVisible = !chromeVisible
        val vis = if (chromeVisible) View.VISIBLE else View.GONE
        topBar.visibility = vis
        bottomBar.visibility = vis
    }

    private fun toggleSlideshow() {
        slideshowActive = !slideshowActive
        mainHandler.removeCallbacks(slideshowRunnable)
        if (slideshowActive) {
            Toast.makeText(this, "🎞️ Diaporama (${prefs.slideshowIntervalSec} s)", Toast.LENGTH_SHORT).show()
            slideshowBtnTv.setTextColor(Color.rgb(253, 224, 71))
            mainHandler.postDelayed(slideshowRunnable, prefs.slideshowIntervalSec * 1000L)
        } else {
            slideshowBtnTv.setTextColor(Color.WHITE)
        }
    }

    private fun refreshTopAndBottomChrome() {
        val it = currentItem() ?: return
        titleTv.text = it.name
        subTv.text = "${pager.currentItem + 1} / ${items.size} · ${it.folderName}"
        if (it.aiScorePct >= 0) {
            aiBadgeTv.text = "✨ ${it.aiScorePct}%"
            aiBadgeTv.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (it.aiScorePct >= 75) Color.rgb(16, 150, 95) else Color.rgb(126, 34, 206))
            }
            aiBadgeTv.visibility = View.VISIBLE
        } else {
            aiBadgeTv.visibility = View.GONE
        }
        favBtnTv.text = if (it.isFavorite) "❤️\nFavori" else "🤍\nFavori"
    }

    private fun toggleCurrentFavorite() {
        val idx = pager.currentItem
        val it = items.getOrNull(idx) ?: return
        val nextFav = !it.isFavorite
        db.setFavorite(it.path, nextFav)
        if (nextFav) {
            VisualPreferenceAiEngine.applySwipeFeedback(db, it, rating = 1)
        }
        items[idx] = it.copy(isFavorite = nextFav, swipeRating = if (nextFav) 1 else it.swipeRating)
        refreshTopAndBottomChrome()
        Toast.makeText(this, if (nextFav) "❤️ Ajouté aux Favoris (+ apprentissage IA)" else "Retiré des Favoris", Toast.LENGTH_SHORT).show()
    }

    private fun openEditorForCurrent() {
        val it = currentItem() ?: return
        if (it.type == MediaType.VIDEO) {
            Toast.makeText(this, "L'éditeur intégré s'applique aux photos.", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, PhotoEditorActivity::class.java).apply { putExtra(PhotoEditorActivity.EXTRA_PATH, it.path) })
    }

    private fun shareCurrent() {
        val it = currentItem() ?: return
        val intent = MediaRepository.buildShareIntent(this, listOf(it)) ?: return
        try { startActivity(Intent.createChooser(intent, "Partager via")) } catch (_: Throwable) {}
    }

    private fun deleteCurrent() {
        val idx = pager.currentItem
        val it = items.getOrNull(idx) ?: return
        val useTrash = prefs.useRecycleBin
        AlertDialog.Builder(this)
            .setTitle(if (useTrash) "🗑️ Déplacer vers la Corbeille ?" else "⚠️ Supprimer définitivement ?")
            .setMessage(it.name)
            .setPositiveButton(if (useTrash) "Corbeille" else "Supprimer") { _, _ ->
                val ok = if (useTrash) MediaRepository.moveToRecycleBin(this, db, it)
                else MediaRepository.deletePermanently(this, db, it.path)
                if (ok) {
                    activeVideoHolder?.stopVideo()
                    items.removeAt(idx)
                    if (items.isEmpty()) { finish(); return@setPositiveButton }
                    adapter.notifyDataSetChanged()
                    pager.setCurrentItem(idx.coerceAtMost(items.lastIndex), false)
                    refreshTopAndBottomChrome()
                    Toast.makeText(this, if (useTrash) "Déplacé dans la corbeille ✅" else "Supprimé ✅", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "⚠️ Permission « Accès à tous les fichiers » requise pour supprimer.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showMoreActionsDialog() {
        val it = currentItem() ?: return
        val opts = arrayOf("✏️ Renommer le fichier", "⭐ Marquer Super-Like IA (×2,5)", "ℹ️ Détails EXIF & Personnes IA")
        AlertDialog.Builder(this)
            .setTitle(it.name)
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> showRenameDialog(it)
                    1 -> {
                        val idx = pager.currentItem
                        VisualPreferenceAiEngine.applySwipeFeedback(db, it, rating = 2)
                        items[idx] = it.copy(isFavorite = true, swipeRating = 2)
                        refreshTopAndBottomChrome()
                        Toast.makeText(this, "⭐ Super-Like enregistré ! L'IA renforce ce style & ces visages.", Toast.LENGTH_SHORT).show()
                    }
                    2 -> showMediaDetailsDialog()
                }
            }
            .show()
    }

    private fun showRenameDialog(item: MediaItem) {
        val f = File(item.path)
        val edit = EditText(this).apply {
            setText(f.nameWithoutExtension); setTextColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12)); setBackgroundColor(Color.rgb(30, 30, 38))
        }
        AlertDialog.Builder(this)
            .setTitle("✏️ Renommer")
            .setView(edit)
            .setPositiveButton("Renommer") { _, _ ->
                val renamed = MediaRepository.renameFile(this, db, item.path, edit.text?.toString().orEmpty())
                if (renamed != null) {
                    val idx = pager.currentItem
                    items[idx] = item.copy(path = renamed.absolutePath, name = renamed.name)
                    refreshTopAndBottomChrome()
                    Toast.makeText(this, "Renommé en ${renamed.name} ✅", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "⚠️ Impossible de renommer (vérifie l'accès fichiers).", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showMediaDetailsDialog() {
        val it = currentItem() ?: return
        val sb = StringBuilder()
        val df = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE)
        sb.append("📄 Nom : ${it.name}\n")
        sb.append("📂 Chemin : ${it.path}\n")
        sb.append("💾 Taille : ${MediaRepository.formatFileSize(it.sizeBytes)}\n")
        sb.append("📅 Date : ${df.format(Date(it.dateModifiedSec * 1000L))}\n")

        if (it.type == MediaType.PHOTO) {
            try {
                val exif = ExifInterface(it.path)
                val w = exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH)
                val h = exif.getAttribute(ExifInterface.TAG_IMAGE_LENGTH)
                val model = exif.getAttribute(ExifInterface.TAG_MODEL)
                val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                val fnum = exif.getAttribute(ExifInterface.TAG_F_NUMBER)
                if (!w.isNullOrBlank() && !h.isNullOrBlank()) sb.append("📐 Résolution : ${w} × ${h}\n")
                if (!model.isNullOrBlank()) sb.append("📷 Appareil : $model\n")
                if (!iso.isNullOrBlank() || !fnum.isNullOrBlank()) sb.append("⚙️ Optique : f/${fnum ?: "?"} • ISO ${iso ?: "?"}\n")
            } catch (_: Throwable) {}
        }

        sb.append("\n🧠 ANALYSE IA LOCALE :\n")
        val scoreStr = if (it.aiScorePct >= 0) "${it.aiScorePct} %" else "En cours d'indexation"
        sb.append("✨ Probabilité Favori : $scoreStr\n")
        if (it.aiReason.isNotBlank()) sb.append("💡 Raison : ${it.aiReason}\n")

        val allPersons = db.loadAllPersons().associateBy { p -> p.id }
        val names = it.personIds.mapNotNull { pid -> allPersons[pid]?.let { p -> "${p.displayName()} (${p.affinityPct()} %)" } }
        sb.append("👥 Personnes détectées : ${if (names.isNotEmpty()) names.joinToString(", ") else "Aucun visage détecté"}")

        AlertDialog.Builder(this)
            .setTitle("ℹ️ Propriétés EXIF & IA")
            .setMessage(sb.toString())
            .setPositiveButton("Fermer", null)
            .show()
    }

    private fun makeBarIcon(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6)); setOnClickListener { onClick() }
    }

    private fun makeBottomAction(icon: String, label: String, onClick: () -> Unit) = TextView(this).apply {
        text = "$icon\n$label"; setTextColor(Color.WHITE); textSize = 11.5f; gravity = Gravity.CENTER
        setPadding(dp(4), dp(6), dp(4), dp(6)); setOnClickListener { onClick() }
    }

    private inner class ViewerPagerAdapter : RecyclerView.Adapter<ViewerPagerAdapter.PageVH>() {

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
            val container = FrameLayout(this@MediaViewerActivity).apply {
                setBackgroundColor(Color.BLACK)
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            val zoomIv = ZoomableImageView(this@MediaViewerActivity).apply { onSingleTap = { toggleChrome() } }
            container.addView(zoomIv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

            val videoBox = FrameLayout(this@MediaViewerActivity).apply { visibility = View.GONE }
            val videoView = VideoView(this@MediaViewerActivity)
            videoBox.addView(videoView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))

            val controls = LinearLayout(this@MediaViewerActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.argb(195, 16, 16, 22)) }
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            val playBtn = TextView(this@MediaViewerActivity).apply { text = "⏸"; textSize = 18f; setTextColor(Color.WHITE); setPadding(dp(4), 0, dp(8), 0) }
            val seekBar = SeekBar(this@MediaViewerActivity)
            val timeTv = TextView(this@MediaViewerActivity).apply { text = "0:00"; textSize = 11f; setTextColor(Color.WHITE); typeface = Typeface.MONOSPACE; setPadding(dp(6), 0, dp(6), 0) }
            val muteBtn = TextView(this@MediaViewerActivity).apply { text = "🔊"; textSize = 16f; setTextColor(Color.WHITE); setPadding(dp(6), 0, dp(4), 0) }
            controls.addView(playBtn)
            controls.addView(seekBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            controls.addView(timeTv)
            controls.addView(muteBtn)
            videoBox.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                marginStart = dp(14); marginEnd = dp(14); bottomMargin = dp(86)
            })

            container.addView(videoBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return PageVH(container, zoomIv, videoBox, videoView, playBtn, seekBar, timeTv, muteBtn)
        }

        override fun onBindViewHolder(holder: PageVH, position: Int) {
            holder.bind(items[position])
        }

        override fun onViewRecycled(holder: PageVH) {
            holder.stopVideo()
            super.onViewRecycled(holder)
        }

        inner class PageVH(
            root: FrameLayout,
            val zoomIv: ZoomableImageView,
            val videoBox: FrameLayout,
            val videoView: VideoView,
            val playBtn: TextView,
            val seekBar: SeekBar,
            val timeTv: TextView,
            val muteBtn: TextView
        ) : RecyclerView.ViewHolder(root) {

            private var mpRef: MediaPlayer? = null
            private var muted = false
            private val scrubTick = object : Runnable {
                override fun run() {
                    try {
                        if (videoView.isPlaying) {
                            val pos = videoView.currentPosition
                            val dur = videoView.duration.coerceAtLeast(1)
                            seekBar.max = dur; seekBar.progress = pos
                            timeTv.text = "${MediaRepository.formatDuration(pos.toLong())} / ${MediaRepository.formatDuration(dur.toLong())}"
                            mainHandler.postDelayed(this, 300L)
                        }
                    } catch (_: Throwable) {}
                }
            }

            fun bind(item: MediaItem) {
                stopVideo()
                if (item.type == MediaType.VIDEO) {
                    zoomIv.visibility = View.GONE
                    videoBox.visibility = View.VISIBLE
                    videoView.setVideoURI(Uri.fromFile(File(item.path)))
                    videoView.setOnPreparedListener { mp ->
                        mpRef = mp
                        mp.isLooping = true
                        muted = false; muteBtn.text = "🔊"
                        mp.setVolume(1f, 1f)
                        videoView.start()
                        playBtn.text = "⏸"
                        activeVideoHolder = this
                        mainHandler.post(scrubTick)
                    }
                    videoBox.setOnClickListener { toggleChrome() }
                    playBtn.setOnClickListener {
                        if (videoView.isPlaying) { videoView.pause(); playBtn.text = "▶" }
                        else { videoView.start(); playBtn.text = "⏸"; mainHandler.post(scrubTick) }
                    }
                    muteBtn.setOnClickListener {
                        muted = !muted
                        muteBtn.text = if (muted) "🔇" else "🔊"
                        try { mpRef?.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f) } catch (_: Throwable) {}
                    }
                    seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                            if (fromUser) try { videoView.seekTo(p) } catch (_: Throwable) {}
                        }
                        override fun onStartTrackingTouch(s: SeekBar?) {}
                        override fun onStopTrackingTouch(s: SeekBar?) {}
                    })
                } else {
                    videoBox.visibility = View.GONE
                    zoomIv.visibility = View.VISIBLE
                    Glide.with(zoomIv).load(File(item.path)).into(zoomIv)
                }
            }

            fun pauseVideo() {
                mainHandler.removeCallbacks(scrubTick)
                try { if (videoView.isPlaying) videoView.pause(); playBtn.text = "▶" } catch (_: Throwable) {}
            }

            fun stopVideo() {
                mainHandler.removeCallbacks(scrubTick)
                mpRef = null
                try { videoView.stopPlayback() } catch (_: Throwable) {}
            }
        }
    }

    companion object {
        const val EXTRA_START_INDEX = "extra_start_index"
        var sharedMediaPaths: List<String> = emptyList()
    }
}
