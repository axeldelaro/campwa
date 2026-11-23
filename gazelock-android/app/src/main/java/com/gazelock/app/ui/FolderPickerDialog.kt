package com.gazelock.app.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.gazelock.app.media.VideoFeedRepository
import java.io.File

/**
 * Dark-Theme Direct Folder Picker supporting hidden folders (`.*` & `.nomedia`) with instant video counts!
 */
object FolderPickerDialog {

    fun show(context: Context, initialPath: String, onPicked: (String) -> Unit) {
        val density = context.resources.displayMetrics.density
        val dp: (Int) -> Int = { (it * density).toInt() }

        val rootStorage = Environment.getExternalStorageDirectory()
        var currentDir = if (initialPath.isNotBlank() && File(initialPath).isDirectory) File(initialPath) else rootStorage

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(15, 11, 26))
            setPadding(dp(14), dp(14), dp(14), dp(12))
        }
        val pathTv = TextView(context).apply {
            setTextColor(Color.rgb(240, 171, 252)); textSize = 11.5f; typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(8))
        }
        val listBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply {
            addView(listBox)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340))
        }
        val selectBtn = Button(context).apply {
            text = "✅ Choisir ce dossier"
            isAllCaps = false; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.rgb(225, 29, 72)) }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        container.addView(pathTv); container.addView(scroll); container.addView(selectBtn)

        val dlg = AlertDialog.Builder(context)
            .setTitle("📂 Dossier de Vidéos (.* inclus)")
            .setView(container)
            .setNegativeButton("Annuler", null)
            .create()

        lateinit var renderDir: (File) -> Unit
        renderDir = { dir ->
            currentDir = dir
            val vidCount = VideoFeedRepository.countVideosInDir(dir)
            pathTv.text = "${dir.absolutePath}\n🎬 $vidCount vidéo(s) trouvée(s) ici (sous-dossiers inclus)"
            listBox.removeAllViews()

            val parent = dir.parentFile
            if (parent != null && dir.absolutePath != rootStorage.absolutePath) {
                listBox.addView(makeDirRow(context, dp, "⬆️ .. (Dossier parent)", Color.rgb(42, 34, 62)) {
                    renderDir(parent)
                })
            }

            val subs = dir.listFiles()?.filter { it.isDirectory }
                ?.sortedWith(compareBy<File>({ !it.name.startsWith(".") }, { it.name.lowercase() }))
                .orEmpty()

            if (subs.isEmpty()) {
                listBox.addView(TextView(context).apply {
                    text = "Aucun sous-dossier ici."
                    setTextColor(Color.rgb(150, 150, 170)); textSize = 12f; gravity = Gravity.CENTER; setPadding(0, dp(20), 0, dp(20))
                })
            } else {
                for (sub in subs) {
                    val hiddenTag = if (sub.name.startsWith(".")) "👁️‍🗨️ " else "📁 "
                    val quickVids = sub.listFiles()?.count { it.isFile && VideoFeedRepository.isVideoName(it.name) } ?: 0
                    val suffix = if (quickVids > 0) "  (🎬 $quickVids)" else ""
                    listBox.addView(makeDirRow(context, dp, "$hiddenTag${sub.name}$suffix", Color.rgb(28, 22, 42)) {
                        renderDir(sub)
                    })
                }
            }
        }

        selectBtn.setOnClickListener {
            dlg.dismiss()
            onPicked(currentDir.absolutePath)
        }
        renderDir(currentDir)
        dlg.show()
    }

    private fun makeDirRow(context: Context, dp: (Int) -> Int, label: String, bg: Int, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label; isAllCaps = false; textSize = 12f; gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(bg) }
            setPadding(dp(12), dp(9), dp(12), dp(9)); minHeight = 0; minimumHeight = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(5) }
            setOnClickListener { onClick() }
        }
}
