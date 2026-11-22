package com.demonpet.app.ai

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure Kotlin O(n) Integer StackBlur (~0.25ms on small patch) for real Gaussian blur on API 26–35.
 */
object FastStackBlur {
    fun blurInPlace(bitmap: Bitmap, radius: Int) {
        val r = radius.coerceIn(1, 28)
        val w = bitmap.width
        val h = bitmap.height
        if (w < 2 || h < 2) return

        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = r + r + 1

        val red = IntArray(wh)
        val grn = IntArray(wh)
        val blu = IntArray(wh)
        val vmin = IntArray(max(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        for (i in dv.indices) dv[i] = i / divsum

        var yw = 0
        var yi = 0
        val stack = Array(div) { IntArray(3) }
        val r1 = r + 1

        for (y in 0 until h) {
            var rsum = 0; var gsum = 0; var bsum = 0
            var routsum = 0; var goutsum = 0; var boutsum = 0
            var rinsum = 0; var ginsum = 0; var binsum = 0
            for (i in -r..r) {
                val p = pix[yi + min(wm, max(i, 0))]
                val sir = stack[i + r]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff
                val rbs = r1 - abs(i)
                rsum += sir[0] * rbs; gsum += sir[1] * rbs; bsum += sir[2] * rbs
                if (i > 0) { rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2] }
                else { routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2] }
            }
            var sp = r
            for (x in 0 until w) {
                red[yi] = dv[rsum]; grn[yi] = dv[gsum]; blu[yi] = dv[bsum]
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum
                val ss = sp - r + div
                var sir = stack[ss % div]
                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]
                if (y == 0) vmin[x] = min(x + r + 1, wm)
                val p = pix[yw + vmin[x]]
                sir[0] = (p and 0xff0000) shr 16; sir[1] = (p and 0x00ff00) shr 8; sir[2] = p and 0x0000ff
                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                rsum += rinsum; gsum += ginsum; bsum += binsum
                sp = (sp + 1) % div
                sir = stack[sp % div]
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]
                yi++
            }
            yw += w
        }

        for (x in 0 until w) {
            var rsum = 0; var gsum = 0; var bsum = 0
            var routsum = 0; var goutsum = 0; var boutsum = 0
            var rinsum = 0; var ginsum = 0; var binsum = 0
            var yp = -r * w
            for (i in -r..r) {
                yi = max(0, yp) + x
                val sir = stack[i + r]
                sir[0] = red[yi]; sir[1] = grn[yi]; sir[2] = blu[yi]
                val rbs = r1 - abs(i)
                rsum += red[yi] * rbs; gsum += grn[yi] * rbs; bsum += blu[yi] * rbs
                if (i > 0) { rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2] }
                else { routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2] }
                if (i < hm) yp += w
            }
            yi = x
            var sp = r
            for (y in 0 until h) {
                pix[yi] = (0xff000000.toInt() and pix[yi]) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum
                val ss = sp - r + div
                var sir = stack[ss % div]
                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]
                if (x == 0) vmin[y] = min(y + r1, hm) * w
                val p = x + vmin[y]
                sir[0] = red[p]; sir[1] = grn[p]; sir[2] = blu[p]
                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                rsum += rinsum; gsum += ginsum; bsum += binsum
                sp = (sp + 1) % div
                sir = stack[sp]
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]
                yi += w
            }
        }
        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
    }
}
