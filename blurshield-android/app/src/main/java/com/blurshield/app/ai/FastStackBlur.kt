package com.blurshield.app.ai

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Ultra-fast pure Kotlin integer StackBlur (~0.25ms on a 36x44 live patch).
 * Delivers genuine smooth Gaussian-like blur on every Android API 26–35 without RenderScript deprecations.
 */
object FastStackBlur {

    fun blurInPlace(bitmap: Bitmap, radius: Int): Bitmap {
        val r = radius.coerceIn(1, 24)
        val w = bitmap.width
        val h = bitmap.height
        if (w < 3 || h < 3) return bitmap

        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = r + r + 1

        val red = IntArray(wh)
        val green = IntArray(wh)
        val blue = IntArray(wh)
        val vmin = IntArray(maxOf(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum) { it / divsum }

        val stack = Array(div) { IntArray(3) }
        val r1 = r + 1

        var yw = 0
        var yi = 0

        for (y in 0 until h) {
            var rsum = 0
            var gsum = 0
            var bsum = 0
            var routsum = 0
            var goutsum = 0
            var boutsum = 0
            var rinsum = 0
            var ginsum = 0
            var binsum = 0

            for (i in -r..r) {
                val p = pix[yi + i.coerceIn(0, wm)]
                val sir = stack[i + r]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff
                val rbs = r1 - abs(i)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
            }
            var stackpointer = r

            for (x in 0 until w) {
                red[yi] = dv[rsum]
                green[yi] = dv[gsum]
                blue[yi] = dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                val stackstart = stackpointer - r + div
                var sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (y == 0) {
                    vmin[x] = (x + r + 1).coerceAtMost(wm)
                }
                val p = pix[yw + vmin[x]]

                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi++
            }
            yw += w
        }

        for (x in 0 until w) {
            var rsum = 0
            var gsum = 0
            var bsum = 0
            var routsum = 0
            var goutsum = 0
            var boutsum = 0
            var rinsum = 0
            var ginsum = 0
            var binsum = 0
            var yp = -r * w

            for (i in -r..r) {
                yi = yp.coerceAtLeast(0) + x
                val sir = stack[i + r]
                sir[0] = red[yi]
                sir[1] = green[yi]
                sir[2] = blue[yi]
                val rbs = r1 - abs(i)
                rsum += red[yi] * rbs
                gsum += green[yi] * rbs
                bsum += blue[yi] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                if (i < hm) yp += w
            }
            yi = x
            var stackpointer = r
            for (y in 0 until h) {
                pix[yi] = (0xff shl 24) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                val stackstart = stackpointer - r + div
                var sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (x == 0) {
                    vmin[y] = (y + r1).coerceAtMost(hm) * w
                }
                val p = x + vmin[y]

                sir[0] = red[p]
                sir[1] = green[p]
                sir[2] = blue[p]

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi += w
            }
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
        return bitmap
    }
}
