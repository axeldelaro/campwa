package com.aigallery.pro.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * Smooth Pinch-to-Zoom, Pan & Double-Tap ImageView for Simple Gallery AI Pro:
 * - Hands horizontal swipes cleanly back to ViewPager2 when at 1x scale!
 * - Double-tap smoothly toggles 1.0x <-> 2.6x centered on tap point.
 */
class ZoomableImageView(context: Context) : AppCompatImageView(context) {

    var onSingleTap: (() -> Unit)? = null

    private val matrixObj = Matrix()
    private val matVals = FloatArray(9)
    private var baseScale = 1f
    private var currentZoom = 1f
    private val minZoom = 1f
    private val maxZoom = 6f
    private val lastTouch = PointF()
    private var isDragging = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            val target = (currentZoom * factor).coerceIn(minZoom, maxZoom)
            val delta = target / currentZoom
            currentZoom = target
            matrixObj.postScale(delta, delta, detector.focusX, detector.focusY)
            clampTranslation()
            imageMatrix = matrixObj
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTap?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (currentZoom > 1.15f) {
                resetToFit()
            } else {
                val target = 2.6f
                val delta = target / currentZoom
                currentZoom = target
                matrixObj.postScale(delta, delta, e.x, e.y)
                clampTranslation()
                imageMatrix = matrixObj
            }
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { resetToFit() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetToFit()
    }

    fun resetToFit() {
        val d = drawable ?: return
        val vw = width.toFloat(); val vh = height.toFloat()
        val dw = d.intrinsicWidth.toFloat(); val dh = d.intrinsicHeight.toFloat()
        if (vw <= 0f || vh <= 0f || dw <= 0f || dh <= 0f) return

        val scale = minOf(vw / dw, vh / dh)
        baseScale = scale
        currentZoom = 1f
        val dx = (vw - dw * scale) * 0.5f
        val dy = (vh - dh * scale) * 0.5f
        matrixObj.reset()
        matrixObj.postScale(scale, scale)
        matrixObj.postTranslate(dx, dy)
        imageMatrix = matrixObj
    }

    private fun clampTranslation() {
        val d = drawable ?: return
        val vw = width.toFloat(); val vh = height.toFloat()
        val scaledW = d.intrinsicWidth * baseScale * currentZoom
        val scaledH = d.intrinsicHeight * baseScale * currentZoom

        matrixObj.getValues(matVals)
        var tx = matVals[Matrix.MTRANS_X]
        var ty = matVals[Matrix.MTRANS_Y]

        val minTx = if (scaledW > vw) vw - scaledW else (vw - scaledW) * 0.5f
        val maxTx = if (scaledW > vw) 0f else (vw - scaledW) * 0.5f
        val minTy = if (scaledH > vh) vh - scaledH else (vh - scaledH) * 0.5f
        val maxTy = if (scaledH > vh) 0f else (vh - scaledH) * 0.5f

        tx = tx.coerceIn(minTx, maxTx)
        ty = ty.coerceIn(minTy, maxTy)
        matVals[Matrix.MTRANS_X] = tx
        matVals[Matrix.MTRANS_Y] = ty
        matrixObj.setValues(matVals)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouch.set(event.x, event.y)
                isDragging = true
                parent?.requestDisallowInterceptTouchEvent(currentZoom > 1.05f)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging && !scaleDetector.isInProgress && currentZoom > 1.05f) {
                    val dx = event.x - lastTouch.x
                    val dy = event.y - lastTouch.y
                    matrixObj.postTranslate(dx, dy)
                    clampTranslation()
                    imageMatrix = matrixObj
                    lastTouch.set(event.x, event.y)
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                if (currentZoom <= 1.05f) parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}
