package com.aigallery.pro.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aigallery.pro.data.AlbumFolder
import com.aigallery.pro.data.MediaType
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import java.io.File

/** Iconic Simple Gallery Pro Album Folder Grid Adapter (rounded cover cards + bottom gradient + badges). */
class AlbumAdapter(
    private val context: Context,
    private var spanCount: Int,
    private var accentColor: Int,
    private val onAlbumClick: (AlbumFolder) -> Unit,
    private val onAlbumLongClick: (AlbumFolder) -> Unit
) : RecyclerView.Adapter<AlbumAdapter.AlbumVH>() {

    private var items: List<AlbumFolder> = emptyList()
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    fun submitList(list: List<AlbumFolder>, newSpan: Int, newAccent: Int) {
        items = list
        spanCount = newSpan
        accentColor = newAccent
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumVH {
        val screenW = parent.resources.displayMetrics.widthPixels
        val pad = dp(4)
        val cellW = ((screenW - dp(16)) / spanCount.coerceAtLeast(2)) - pad * 2
        val cellH = (cellW * 1.04f).toInt().coerceAtLeast(dp(120))

        val card = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.rgb(26, 26, 34))
            }
            clipToOutline = true
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cellH).apply {
                setMargins(pad, pad, pad, pad)
            }
        }

        val iv = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        card.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Bottom gradient scrim for crisp Simple Gallery Pro legibility
        val scrim = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.argb(215, 8, 8, 12))
            )
            setPadding(dp(9), dp(22), dp(9), dp(8))
        }
        val nameTv = TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        val subTv = TextView(context).apply {
            setTextColor(Color.rgb(210, 210, 224)); textSize = 11f
            maxLines = 1
        }
        scrim.addView(nameTv); scrim.addView(subTv)
        card.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // Top badges (Pin / Hidden / AI Score)
        val topBadge = TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 10f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.argb(185, 16, 16, 24))
            }
        }
        card.addView(topBadge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(6); marginEnd = dp(6)
        })

        return AlbumVH(card, iv, nameTv, subTv, topBadge)
    }

    override fun onBindViewHolder(holder: AlbumVH, position: Int) {
        val album = items[position]
        val pinPrefix = if (album.isPinned) "📌 " else ""
        val hidPrefix = if (album.isHiddenFolder) "👁️ " else ""
        holder.nameTv.text = "$pinPrefix$hidPrefix${album.name}"
        val vidTag = if (album.coverType == MediaType.VIDEO) " · 🎬" else ""
        holder.subTv.text = "${album.mediaCount} médias$vidTag"

        if (album.avgAiScore >= 0) {
            holder.topBadge.text = "✨ ${album.avgAiScore}%"
            holder.topBadge.visibility = android.view.View.VISIBLE
            holder.topBadge.setTextColor(if (album.avgAiScore >= 70) Color.rgb(253, 224, 71) else Color.WHITE)
        } else {
            holder.topBadge.visibility = android.view.View.GONE
        }

        Glide.with(holder.iv)
            .load(File(album.coverPath))
            .centerCrop()
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .into(holder.iv)

        holder.itemView.setOnClickListener { onAlbumClick(album) }
        holder.itemView.setOnLongClickListener { onAlbumLongClick(album); true }
    }

    class AlbumVH(
        root: FrameLayout,
        val iv: ImageView,
        val nameTv: TextView,
        val subTv: TextView,
        val topBadge: TextView
    ) : RecyclerView.ViewHolder(root)
}
