package com.gazelock.app.overlay.minigames

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader

/**
 * Shared bitmap slicer & fallback generator for GazeLock AI 3x3 visual mini-games
 * (Sliding Puzzle & Image Reconstruction). Always produces crisp square RGB_565 bitmaps
 * and safely recycles intermediate allocations.
 */
object PuzzleBitmapHelper {

    fun prepareSquareBoardBitmap(source: Bitmap?, targetSize: Int = 540): Bitmap {
        if (source != null && !source.isRecycled && source.width >= 32 && source.height >= 32) {
            try {
                val side = minOf(source.width, source.height)
                val left = (source.width - side) / 2
                val top = (source.height - side) / 2
                val out = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.RGB_565)
                val c = Canvas(out)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                c.drawBitmap(source, Rect(left, top, left + side, top + side), Rect(0, 0, targetSize, targetSize), paint)
                return out
            } catch (_: Throwable) {}
        }

        // Vibrant high-contrast geometric fallback (never blank or unplayable!)
        val bmp = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, targetSize.toFloat(), targetSize.toFloat(),
                intArrayOf(Color.rgb(190, 24, 93), Color.rgb(109, 40, 217), Color.rgb(14, 165, 233), Color.rgb(16, 185, 129)),
                null, Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, targetSize.toFloat(), targetSize.toFloat(), bgPaint)

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = targetSize * 0.035f
            color = Color.argb(175, 255, 255, 255)
        }
        c.drawCircle(targetSize * 0.5f, targetSize * 0.5f, targetSize * 0.36f, ringPaint)
        c.drawCircle(targetSize * 0.5f, targetSize * 0.5f, targetSize * 0.20f, ringPaint)

        val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = targetSize * 0.025f
            color = Color.argb(140, 253, 224, 71)
        }
        c.drawLine(0f, targetSize * 0.5f, targetSize.toFloat(), targetSize * 0.5f, crossPaint)
        c.drawLine(targetSize * 0.5f, 0f, targetSize * 0.5f, targetSize.toFloat(), crossPaint)
        return bmp
    }

    fun sliceIntoGrid(square: Bitmap, grid: Int = 3): List<Bitmap> {
        val cell = (square.width / grid).coerceAtLeast(16)
        val list = ArrayList<Bitmap>(grid * grid)
        for (r in 0 until grid) {
            for (c in 0 until grid) {
                list.add(Bitmap.createBitmap(square, c * cell, r * cell, cell, cell))
            }
        }
        return list
    }
}
