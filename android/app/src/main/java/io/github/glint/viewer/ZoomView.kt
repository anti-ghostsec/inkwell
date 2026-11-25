package io.github.glint.viewer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One picture, fitted to the screen: pinch or double-tap to zoom, drag to
 * look around, fling to glide. A quick sideways swipe turns the page, but
 * only once the picture is at its edge that way, so a zoomed-in page is
 * looked round first.
 */
class ZoomView(context: Context) : View(context) {
    interface Listener {
        /** A tap, where along the width (0 to 1). */
        fun onTap(across: Float)

        /** A quick sideways swipe at the picture's edge; [leftward]: the finger went left. */
        fun onSwipe(leftward: Boolean)
    }

    var listener: Listener? = null

    /** Fit the width (a tall page scrolls down), rather than the whole picture. */
    var fitWidth = false
        set(v) {
            field = v
            reset(top = true)
        }

    private var drawable: Drawable? = null
    private var dw = 1
    private var dh = 1
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var message: String? = null
    private var animator: ValueAnimator? = null
    private val scroller = OverScroller(context)
    private val density = resources.displayMetrics.density
    private val swipeSpeed = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 6f
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0xb4, 0xb4, 0xba)
        textSize = 16 * density
        textAlign = Paint.Align.CENTER
    }

    // Whether the picture was at its left or right edge when the finger went down.
    private var atLeftAtDown = true
    private var atRightAtDown = true

    /** Shows [d] (or, with none, [text]); from its top, or its bottom when going back a page. */
    fun show(d: Drawable?, text: String? = null, fromBottom: Boolean = false) {
        (drawable as? Animatable)?.stop()
        drawable?.callback = null
        animator?.cancel()
        scroller.forceFinished(true)
        drawable = d
        message = text
        if (d != null) {
            dw = d.intrinsicWidth.coerceAtLeast(1)
            dh = d.intrinsicHeight.coerceAtLeast(1)
            d.setBounds(0, 0, dw, dh)
            d.callback = this
            (d as? Animatable)?.start()
        }
        reset(top = !fromBottom)
    }

    override fun verifyDrawable(who: Drawable) = who === drawable || super.verifyDrawable(who)

    override fun onDetachedFromWindow() {
        (drawable as? Animatable)?.stop()
        super.onDetachedFromWindow()
    }

    private fun fitScale() = if (width == 0 || height == 0) 1f else min(width / dw.toFloat(), height / dh.toFloat())

    private fun baseScale() = if (width == 0 || height == 0) 1f else if (fitWidth) width / dw.toFloat() else fitScale()

    private fun maxScale() = max(baseScale() * 8f, 4f)

    private fun reset(top: Boolean) {
        scale = baseScale()
        val w = dw * scale
        val h = dh * scale
        tx = (width - w) / 2
        ty = if (h <= height || top) 0f else height - h
        clamp()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reset(top = true)
    }

    /** Keeps the picture on screen: centred where it's smaller than the screen, no gaps where it's bigger. */
    private fun clamp() {
        val w = dw * scale
        val h = dh * scale
        tx = if (w <= width) (width - w) / 2 else tx.coerceIn(width - w, 0f)
        ty = if (h <= height) (height - h) / 2 else ty.coerceIn(height - h, 0f)
    }

    private fun zoomTo(target: Float, fx: Float, fy: Float) {
        val s = target.coerceIn(min(fitScale(), baseScale()), maxScale())
        tx = fx - (fx - tx) * (s / scale)
        ty = fy - (fy - ty) * (s / scale)
        scale = s
        clamp()
        invalidate()
    }

    private fun animateZoom(target: Float, fx: Float, fy: Float) {
        animator?.cancel()
        val from = scale
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { zoomTo(from + (target - from) * (it.animatedValue as Float), fx, fy) }
            start()
        }
    }

    private fun animateTo(toX: Float, toY: Float) {
        animator?.cancel()
        val (fromX, fromY) = tx to ty
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                tx = fromX + (toX - fromX) * f
                ty = fromY + (toY - fromY) * f
                clamp()
                invalidate()
            }
            start()
        }
    }

    /**
     * A screenful on, down a page taller than the screen (or back up):
     * true if it moved, false if the page is already at its end that way.
     */
    fun step(forward: Boolean): Boolean {
        val h = dh * scale
        if (h <= height + 1) return false
        val by = height * 0.85f
        return if (forward) {
            if (ty <= height - h + 1) false else true.also { animateTo(tx, max(ty - by, height - h)) }
        } else {
            if (ty >= -1) false else true.also { animateTo(tx, min(ty + by, 0f)) }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val d = drawable
        if (d == null) {
            val text = message ?: return
            // A line or two, in the middle.
            val lines = text.split('\n')
            var y = height / 2f - (lines.size - 1) * textPaint.textSize * 0.7f
            for (line in lines) {
                canvas.drawText(line, width / 2f, y, textPaint)
                y += textPaint.textSize * 1.4f
            }
            return
        }
        canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(scale, scale)
        d.draw(canvas)
        canvas.restore()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            tx = scroller.currX.toFloat()
            ty = scroller.currY.toFloat()
            clamp()
            postInvalidateOnAnimation()
        }
    }

    private val scaling = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            animator?.cancel()
            return drawable != null
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomTo(scale * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            val w = dw * scale
            atLeftAtDown = w <= width + 1 || tx >= -1
            atRightAtDown = w <= width + 1 || tx <= width - w + 1
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            listener?.onTap(if (width > 0) e.x / width else 0.5f)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (drawable == null) return false
            val base = baseScale()
            if (scale > base * 1.05f) animateZoom(base, e.x, e.y) else animateZoom(base * 2.5f, e.x, e.y)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaling.isInProgress || drawable == null) return false
            animator?.cancel()
            tx -= dx
            ty -= dy
            clamp()
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaling.isInProgress || e2.pointerCount > 1) return false
            if (abs(vx) > swipeSpeed && abs(vx) > abs(vy) * 1.5f) {
                if (vx < 0 && atRightAtDown) {
                    listener?.onSwipe(leftward = true)
                    return true
                }
                if (vx > 0 && atLeftAtDown) {
                    listener?.onSwipe(leftward = false)
                    return true
                }
            }
            if (drawable == null) return false
            val w = dw * scale
            val h = dh * scale
            val (minX, maxX) = if (w > width) (width - w).toInt() to 0 else tx.toInt() to tx.toInt()
            val (minY, maxY) = if (h > height) (height - h).toInt() to 0 else ty.toInt() to ty.toInt()
            scroller.fling(tx.toInt(), ty.toInt(), vx.toInt(), vy.toInt(), minX, maxX, minY, maxY)
            postInvalidateOnAnimation()
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaling.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
