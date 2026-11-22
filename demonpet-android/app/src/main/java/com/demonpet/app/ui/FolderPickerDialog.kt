package com.demonpet.app.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.demonpet.app.media.MediaFolderHelper
import java.io.File

/**
 * Native Full-Storage Folder Browser supporting HIDDEN `.dotfolders`, `.nomedia`, Photos AND Videos!
 */
object FolderPickerDialog {

    fun show(context: Context, initialPath: String, onSelected: (String) -> Unit) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val rootDir = Environment.getExternalStorageDirectory()
        var currentDir = if (initialPath.isNotBlank() && File(initialPath).isDirectory) {
            File(initialPath)
        } else rootDir
        var showHidden = true

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 18, 24))
            setPadding(dp(16), dp(14), dp(16), dp(12))
        }

        val pathTv = TextView(context).apply {
            setTextColor(Color.rgb(253, 164, 175))
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(8))
        }
        container.addView(pathTv)

        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val upBtn = Button(context).apply {
            text = "⬆ Parent"
            isAllCaps = false
            textSize = 11.5f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.rgb(34, 34, 42))
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            minHeight = 0; minimumHeight = 0
        }
        val hiddenCb = CheckBox(context).apply {
            text = "Masqués (.*)"
            isChecked = showHidden
            setTextColor(Color.rgb(228, 228, 231))
            textSize = 11.5f
        }
        topBar.addView(upBtn)
        topBar.addView(hiddenCb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(10)
        })
        container.addView(topBar)

        val scroll = ScrollView(context)
        val listLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listLayout)
        container.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)))

        fun renderDir() {
            listLayout.removeAllViews()
            val curImgs = MediaFolderHelper.countImagesInFolder(currentDir, includeHidden = true)
            val curVids = MediaFolderHelper.countVideosInFolder(currentDir, includeHidden = true)
            val rel = currentDir.absolutePath.removePrefix(rootDir.absolutePath).ifEmpty { "/" }
            pathTv.text = "$rel  ($curImgs img • $curVids vid)"

            val rawDirs = currentDir.listFiles()?.filter { it.isDirectory }?.filter {
                showHidden || !it.name.startsWith(".")
            }?.sortedWith(compareByDescending<File> { it.name.startsWith(".") }.thenBy { it.name.lowercase() }).orEmpty()

            if (rawDirs.isEmpty()) {
                listLayout.addView(TextView(context).apply {
                    text = "Aucun sous-dossier ici."
                    setTextColor(Color.rgb(161, 161, 170))
                    textSize = 12f
                    setPadding(dp(8), dp(16), dp(8), dp(16))
                })
                return
            }

            for (dir in rawDirs) {
                val isHidden = dir.name.startsWith(".")
                val imgCnt = MediaFolderHelper.countImagesInFolder(dir, includeHidden = true)
                val vidCnt = MediaFolderHelper.countVideosInFolder(dir, includeHidden = true)
                val hasNomedia = File(dir, ".nomedia").exists()

                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(if (isHidden) Color.argb(40, 168, 85, 247) else Color.rgb(26, 26, 34))
                    }
                    setOnClickListener {
                        currentDir = dir
                        renderDir()
                    }
                }
                val title = TextView(context).apply {
                    text = buildString {
                        if (isHidden) append("🔒 ")
                        append(dir.name)
                        if (hasNomedia) append(" (.nomedia)")
                    }
                    setTextColor(if (isHidden) Color.rgb(233, 213, 255) else Color.WHITE)
                    textSize = 12.5f
                }
                val badge = buildString {
                    if (imgCnt > 0) append("${imgCnt}p ")
                    if (vidCnt > 0) append("${vidCnt}v ")
                    append("→")
                }
                val countTv = TextView(context).apply {
                    text = badge
                    setTextColor(if (imgCnt + vidCnt > 0) Color.rgb(52, 211, 153) else Color.rgb(113, 113, 122))
                    textSize = 11f
                }
                row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(countTv)
                listLayout.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(6)
                })
            }
        }

        upBtn.setOnClickListener {
            val parent = currentDir.parentFile
            if (parent != null && parent.absolutePath.startsWith(rootDir.absolutePath)) {
                currentDir = parent
                renderDir()
            }
        }
        hiddenCb.setOnCheckedChangeListener { _, checked ->
            showHidden = checked
            renderDir()
        }

        renderDir()

        AlertDialog.Builder(context)
            .setTitle("Choisir le dossier (Photos & Vidéos)")
            .setView(container)
            .setPositiveButton("Choisir ce dossier") { _, _ ->
                onSelected(currentDir.absolutePath)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
