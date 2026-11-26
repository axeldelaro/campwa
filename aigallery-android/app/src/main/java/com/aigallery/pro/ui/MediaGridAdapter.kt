package com.aigallery.pro.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aigallery.pro.data.MediaItem
import com.aigallery.pro.data.MediaRepository
import com.aigallery.pro.data.MediaType
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import java.io.File

/** Blazing-fast Simple Gallery Pro Media Grid Adapter with Video Duration, Favorite, AI Match % & Multi-Select! */
class MediaGridAdapter(
    private val context: Context,
    private var spanCount: Int,
    private var accentColor: Int,
    private val onItemClick: (MediaItem, Int) -> Unit,
    private val onItemLongClick: (MediaItem, Int) -> Unit
) : RecyclerView.Adapter<MediaGridAdapter.MediaVH>() {

    private var items: List<MediaItem> = emptyList()
    private val selectedPaths = HashSet<String>()
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    fun submitList(list: List<MediaItem>, newSpan: Int, newAccent: Int) {
        items = list
        spanCount = newSpan
        accentColor = newAccent
        notifyDataSetChanged()
    }

    fun isSelectionMode(): Boolean = selectedPaths.isNotEmpty()
    fun getSelectedItems(): List<MediaItem> = items.filter { it.path in selectedPaths }
    fun selectedCount(): Int = selectedPaths.size

    fun toggleSelect(path: String) {
        if (!selectedPaths.add(path)) selectedPaths.remove(path)
        notifyDataSetChanged()
    }

    fun selectAll() {
        selectedPaths.clear()
        for (it in items) selectedPaths.add(it.path)
        notifyDataSetChanged()
    }

    fun clearSelection() {
        if (selectedPaths.isNotEmpty()) {
            selectedPaths.clear()
            notifyDataSetChanged()
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaVH {
        val screenW = parent.resources.displayMetrics.widthPixels
        val cellSize = (screenW / spanCount.coerceAtLeast(2)).coerceAtLeast(dp(72))

        val box = FrameLayout(context).apply {
            setBackgroundColor(Color.rgb(22, 22, 28))
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cellSize).apply {
                setMargins(dp(1), dp(1), dp(1), dp(1))
            }
        }
        val iv = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        box.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Top-Left: AI Score Badge (when scored!)
        val aiTv = TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 9.5f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(5), dp(1), dp(5), dp(1))
            visibility = View.GONE
        }
        box.addView(aiTv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(4); marginStart = dp(4)
        })

        // Top-Right: Favorite / Super-Like Heart
        val favTv = TextView(context).apply {
            textSize = 12f; visibility = View.GONE
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        }
        box.addView(favTv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(3); marginEnd = dp(4)
        })

        // Bottom-Right: Video Duration / GIF Pill
        val typeTv = TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 9.5f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(5), dp(1), dp(5), dp(1))
            background = GradientDrawable().apply { cornerRadius = dp(5).toFloat(); setColor(Color.argb(180, 0, 0, 0)) }
            visibility = View.GONE
        }
        box.addView(typeTv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = dp(4); marginEnd = dp(4)
        })

        // Multi-Select Highlight Veil + Checkmark
        val selVeil = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(115, 245, 124, 0))
            visibility = View.GONE
        }
        selVeil.addView(TextView(context).apply {
            text = "✓"; setTextColor(Color.WHITE); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(16, 185, 129)) }
        }, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
        box.addView(selVeil, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        return MediaVH(box, iv, aiTv, favTv, typeTv, selVeil)
    }

    override fun onBindViewHolder(holder: MediaVH, position: Int) {
        val item = items[position]
        Glide.with(holder.iv)
            .load(File(item.path))
            .centerCrop()
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .into(holder.iv)

        if (item.aiScorePct >= 0) {
            holder.aiTv.visibility = View.VISIBLE
            holder.aiTv.text = "✨${item.aiScorePct}%"
            val bgCol = when {
                item.aiScorePct >= 80 -> Color.argb(215, 16, 150, 95)
                item.aiScorePct >= 60 -> Color.argb(205, 147, 51, 234)
                else -> Color.argb(175, 24, 24, 34)
            }
            holder.aiTv.background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(bgCol) }
        } else {
            holder.aiTv.visibility = View.GONE
        }

        if (item.swipeRating == 2) {
            holder.favTv.text = "⭐"; holder.favTv.visibility = View.VISIBLE
        } else if (item.isFavorite) {
            holder.favTv.text = "❤️"; holder.favTv.visibility = View.VISIBLE
        } else {
            holder.favTv.visibility = View.GONE
        }

        when (item.type) {
            MediaType.VIDEO -> {
                holder.typeTv.text = if (item.durationMs > 0L) "▶ ${MediaRepository.formatDuration(item.durationMs)}" else "▶ VID"
                holder.typeTv.visibility = View.VISIBLE
            }
            MediaType.GIF -> {
                holder.typeTv.text = "GIF"
                holder.typeTv.visibility = View.VISIBLE
            }
            MediaType.PHOTO -> holder.typeTv.visibility = View.GONE
        }

        val isSel = item.path in selectedPaths
        holder.selVeil.visibility = if (isSel) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener {
            if (isSelectionMode()) toggleSelect(item.path)
            else onItemClick(item, position)
        }
        holder.itemView.setOnLongClickListener {
            onItemLongClick(item, position)
            true
        }
    }

    class MediaVH(
        root: FrameLayout,
        val iv: ImageView,
        val aiTv: TextView,
        val favTv: TextView,
        val typeTv: TextView,
        val selVeil: FrameLayout
    ) : RecyclerView.ViewHolder(root)
}
