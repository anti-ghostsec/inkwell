package io.github.inkwell.viewer

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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The pages side by side in one long strip, read by sliding sideways: each
 * page as tall as the screen (a phone is tall), so a page wider than the
 * screen is slid across, and the next page follows straight on. A fling
 * glides; pinch or double-tap to zoom (then drag up and down too).
 *
 * "Whole pages" instead: one page to a screen, whole, and a slide (however
 * short or slow) goes to the next one.
 *
 * Where you are is kept as a page and how far across it, so pages whose
 * size isn't known yet (still loading) never make the one you're on jump.
 */
class StripView(context: Context) : View(context) {
    interface Source {
        val count: Int

        /** Page [i], if it's loaded (null: still loading, or can't be shown). */
        fun picture(i: Int): Picture?

        /** Whether page [i] couldn't be shown at all. */
        fun failed(i: Int): Boolean
    }

    interface Listener {
        /** The page in the middle of the screen is now [i]. */
        fun onPage(i: Int)

        /** Pages [from] to [to] are on screen (or about to be): they're wanted. */
        fun onVisible(from: Int, to: Int)

        fun onTap()

        fun onHold(x: Float, y: Float)
    }

    var source: Source? = null
    var listener: Listener? = null

    /** One whole page to a screen, rather than each as tall as the screen. */
    var wholePages = false
        set(v) {
            field = v
            zoom = 1f
            ty = 0f
            pos = floor(pos + 0.5f)
            settle()
        }

    /** Manga: page 1 on the right, read leftwards. */
    var rightToLeft = false
        set(v) {
            field = v
            invalidate()
        }

    /** Where the screen's leading edge is: page, and how far across it (0 to 1). */
    private var pos = 0f
    private var zoom = 1f
    private var ty = 0f
    private var current = -1
    private var aspectGuess = 0.68f
    private var animator: ValueAnimator? = null
    private val scroller = OverScroller(context, DecelerateInterpolator())
    private var flingLast = 0
    private var flinging = false
    private val density = resources.displayMetrics.density
    private val flingSpeed = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 2f
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0xb4, 0xb4, 0xba)
        textSize = 15 * density
        textAlign = Paint.Align.CENTER
    }
    private val started = HashSet<Drawable>()

    private val count get() = source?.count ?: 0

    private fun aspect(i: Int): Float {
        val p = source?.picture(i) ?: return aspectGuess
        return if (p.width > 0 && p.height > 0) p.width.toFloat() / p.height else aspectGuess
    }

    /** How wide page [i]'s place in the strip is, on screen. */
    private fun slot(i: Int): Float {
        if (width == 0 || height == 0) return 1f
        val w = if (wholePages) width.toFloat() else height * aspect(i)
        return max(w * zoom, 1f)
    }

    private val stripHeight get() = height * zoom

    // ---- where things are ----

    /** Moves along the strip by [px] screen pixels (positive: on, in reading order). */
    private fun advance(px: Float) {
        var p = floor(pos).toInt().coerceIn(0, max(count - 1, 0))
        var f = pos - p
        var left = px
        var guard = 0
        while (left != 0f && guard++ < 10_000) {
            val w = slot(p)
            if (left > 0) {
                val remain = (1 - f) * w
                if (left < remain || p >= count - 1) {
                    f += left / w
                    left = 0f
                } else {
                    left -= remain
                    p++
                    f = 0f
                }
            } else {
                val remain = f * w
                if (-left <= remain || p <= 0) {
                    f += left / w
                    left = 0f
                } else {
                    left += remain
                    p--
                    f = 1f
                }
            }
        }
        pos = p + f
        clampPos()
    }

    /** The furthest the strip goes: the last page's far edge at the screen's. */
    private fun maxPos(): Float {
        if (count == 0) return 0f
        if (wholePages) return (count - 1).toFloat()
        var need = width.toFloat()
        var p = count - 1
        while (p > 0 && need > slot(p)) {
            need -= slot(p)
            p--
        }
        return if (need >= slot(p)) 0f else p + (slot(p) - need) / slot(p)
    }

    private fun clampPos(): Boolean {
        val before = pos
        pos = pos.coerceIn(0f, maxPos())
        ty = if (stripHeight <= height) 0f else ty.coerceIn(height - stripHeight, 0f)
        return pos != before
    }

    /** Draws and reports what's on screen. */
    private fun changed() {
        invalidate()
        if (count == 0 || width == 0) return
        // The page the middle of the screen is on.
        var p = floor(pos).toInt().coerceIn(0, count - 1)
        var x = -(pos - p) * slot(p)
        var middle = p
        var last = p
        while (x < width && last < count) {
            val w = slot(last)
            if (x <= width / 2f && x + w > width / 2f) middle = last
            x += w
            last++
        }
        if (middle != current) {
            current = middle
            listener?.onPage(middle)
        }
        listener?.onVisible(p, min(last, count - 1))
    }

    /** The page in the middle of the screen. */
    val page get() = current.coerceAtLeast(0)

    // ---- going places ----

    /** Straight to page [i], at its start. */
    fun jumpTo(i: Int) {
        stopMoving()
        pos = i.coerceIn(0, max(count - 1, 0)).toFloat()
        ty = 0f
        clampPos()
        current = -1
        changed()
    }

    /** Glides to page [i]'s start. */
    fun glideTo(i: Int) {
        stopMoving()
        val target = i.coerceIn(0, max(count - 1, 0)).toFloat().coerceAtMost(maxPos())
        val from = pos
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                pos = from + (target - from) * (it.animatedValue as Float)
                clampPos()
                changed()
            }
            start()
        }
    }

    /** On (or back) a screenful: a wide page is gone across first, then the next page. */
    fun step(forward: Boolean) {
        if (wholePages) {
            glideTo(page + if (forward) 1 else -1)
            return
        }
        stopMoving()
        val from = pos
        advance(if (forward) width * 0.9f else -width * 0.9f)
        val target = pos
        pos = from
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                pos = from + (target - from) * (it.animatedValue as Float)
                clampPos()
                changed()
            }
            start()
        }
    }

    /** Whether there's more strip that way. */
    fun canGo(forward: Boolean) = if (forward) pos < maxPos() - 0.001f else pos > 0.001f

    /** Back to no zoom. */
    fun fit() {
        stopMoving()
        zoomAround(1f / zoom, width / 2f, height / 2f)
    }

    /** A page loaded (or failed): its size may have changed, so the strip is laid out again. */
    fun pageChanged(i: Int) {
        source?.picture(i)?.let { p ->
            if (p.width > 0 && p.height > 0) aspectGuess = p.width.toFloat() / p.height
            (p.drawable as? Animatable)?.let { a ->
                p.drawable.callback = this
                if (started.add(p.drawable)) a.start()
            }
        }
        clampPos()
        changed()
    }

    /** The list changed: start again at [i]. */
    fun reset(i: Int) {
        started.clear()
        zoom = 1f
        current = -1
        jumpTo(i)
    }

    override fun verifyDrawable(who: Drawable) = who in started || super.verifyDrawable(who)

    private fun stopMoving() {
        animator?.cancel()
        animator = null
        scroller.forceFinished(true)
        flinging = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampPos()
        current = -1
        changed()
    }

    // ---- drawing ----

    override fun onDraw(canvas: Canvas) {
        val src = source ?: return
        if (count == 0 || width == 0) return
        val sh = stripHeight
        val top = if (sh <= height) (height - sh) / 2 else ty
        var p = floor(pos).toInt().coerceIn(0, count - 1)
        var x = -(pos - p) * slot(p)
        while (x < width && p < count) {
            val w = slot(p)
            val left = if (rightToLeft) width - x - w else x
            val pic = src.picture(p)
            if (pic != null) {
                // Fitted inside its place, in the middle of it.
                val a = aspect(p)
                var dw = w
                var dh = dw / a
                if (dh > sh) {
                    dh = sh
                    dw = dh * a
                }
                val l = left + (w - dw) / 2
                val t = top + (sh - dh) / 2
                pic.drawable.setBounds(l.roundToInt(), t.roundToInt(), (l + dw).roundToInt(), (t + dh).roundToInt())
                pic.drawable.draw(canvas)
            } else {
                val text = if (src.failed(p)) "Page ${p + 1} can't be shown" else "Page ${p + 1}…"
                canvas.drawText(text, left + w / 2, height / 2f, textPaint)
            }
            x += w
            p++
        }
    }

    override fun computeScroll() {
        if (flinging && scroller.computeScrollOffset()) {
            val now = scroller.currX
            val d = now - flingLast
            flingLast = now
            if (d != 0) {
                advance(d.toFloat())
                if (pos <= 0f || pos >= maxPos()) scroller.forceFinished(true)
            }
            ty = scroller.currY.toFloat()
            clampPos()
            changed()
            postInvalidateOnAnimation()
        } else if (flinging) {
            flinging = false
        }
    }

    // ---- touch ----

    /** Screen pixels the finger moved, as along the strip (manga's runs the other way). */
    private fun along(dx: Float) = if (rightToLeft) -dx else dx

    private var startPage = 0
    private var dragged = false

    private fun zoomAround(factor: Float, fx: Float, fy: Float) {
        val target = (zoom * factor).coerceIn(1f, 6f)
        val r = target / zoom
        if (r == 1f) return
        val sh = stripHeight
        val top = if (sh <= height) (height - sh) / 2 else ty
        zoom = target
        // What was under the fingers stays under them.
        val ax = if (rightToLeft) width - fx else fx
        advance(ax * (r - 1))
        ty = fy - (fy - top) * r
        clampPos()
        changed()
    }

    private val scaling = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var lastX = 0f
        private var lastY = 0f

        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            stopMoving()
            lastX = d.focusX
            lastY = d.focusY
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            // Two fingers move the strip as well as zooming it.
            advance(-along(d.focusX - lastX))
            ty += d.focusY - lastY
            lastX = d.focusX
            lastY = d.focusY
            zoomAround(d.scaleFactor, d.focusX, d.focusY)
            dragged = true
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            stopMoving()
            startPage = floor(pos + 0.5f).toInt()
            dragged = false
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            listener?.onTap()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1.05f) zoomAround(1f / zoom, e.x, e.y) else zoomAround(2.5f, e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (!scaling.isInProgress && !dragged) listener?.onHold(e.x, e.y)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaling.isInProgress || e2.pointerCount > 1) return false
            dragged = true
            advance(along(dx))
            if (zoom > 1f) ty -= dy
            clampPos()
            changed()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaling.isInProgress || e2.pointerCount > 1) return false
            if (wholePages && zoom <= 1.01f) {
                // Any slide that way is a page.
                val v = -along(vx)
                when {
                    v > flingSpeed && abs(vx) > abs(vy) * 0.5f -> glideTo(startPage + 1)
                    v < -flingSpeed && abs(vx) > abs(vy) * 0.5f -> glideTo(startPage - 1)
                    else -> settle()
                }
                flung = true
                return true
            }
            flingLast = 0
            flinging = true
            val sh = stripHeight
            val minY = if (sh > height) (height - sh).toInt() else ty.toInt()
            scroller.fling(0, ty.toInt(), (-along(vx)).toInt(), if (zoom > 1f) vy.toInt() else 0,
                Int.MIN_VALUE / 2, Int.MAX_VALUE / 2, minY, if (sh > height) 0 else ty.toInt())
            postInvalidateOnAnimation()
            flung = true
            return true
        }
    })

    private var flung = false

    /** Whole pages: back to the nearest page after a slide too slow to be a fling. */
    private fun settle() {
        if (!wholePages || zoom > 1.01f || count == 0) {
            invalidate()
            return
        }
        val target = when {
            pos > startPage + 0.15f -> startPage + 1
            pos < startPage - 0.15f -> startPage - 1
            else -> startPage
        }
        glideTo(target)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) flung = false
        scaling.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (dragged && !flung) settle()
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
