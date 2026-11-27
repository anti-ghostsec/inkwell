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
 * The pages side by side in one strip, read by sliding sideways: each page
 * as tall as the screen (a phone is tall), so a page wider than the screen
 * is slid across freely. At a page's edge the strip holds back: the next
 * page only comes if you push on past a point (or push a little and flick),
 * otherwise it springs back, and a fling stops at the page's edge. Pinch
 * or double-tap to zoom (then drag up and down too).
 *
 * Held sideways, each page is as wide as the screen instead (a comic's
 * usual page shape, so most fill it exactly), read down by dragging up and
 * down: the next page starts at its top, the one before at its bottom.
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
            startSlide()
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

    /** The comic's usual page shape (its first upright page): held sideways, that fits the width. */
    private var fitAspect = 0f
    private var animator: ValueAnimator? = null
    private val scroller = OverScroller(context, DecelerateInterpolator())
    private var flingPage = 0
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

    /** Held sideways (and not whole pages): pages fitted to the width. */
    private val sideways get() = width > height && !wholePages

    /** The strip's height unzoomed: the screen's, or held sideways, a usual page's at the screen's width. */
    private fun baseHeight(): Float =
        if (sideways) width / (if (fitAspect > 0f) fitAspect else aspectGuess) else height.toFloat()

    /** How wide page [i]'s place in the strip is, on screen. */
    private fun slot(i: Int): Float {
        if (width == 0 || height == 0) return 1f
        val w = if (wholePages) width.toFloat() else baseHeight() * aspect(i)
        return max(w * zoom, 1f)
    }

    private val stripHeight get() = baseHeight() * zoom

    /** The strip's top for the page's top ([top]) or its bottom. */
    private fun edgeTy(top: Boolean) = if (top || stripHeight <= height) 0f else height - stripHeight

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

    /** Pixels from page [p]'s start to the screen's leading edge (negative: before it). */
    private fun offsetIn(p: Int): Float {
        val q = floor(pos).toInt().coerceIn(0, max(count - 1, 0))
        var o = (pos - q) * slot(q)
        if (q >= p) {
            for (i in p until q) o += slot(i)
        } else {
            for (i in q until p) o -= slot(i)
        }
        return o
    }

    /** How far page [p] can be slid across before its far edge reaches the screen's. */
    private fun room(p: Int) = max(0f, slot(p) - width)

    /** The leading edge [o] pixels into page [p]. */
    private fun placeAt(p: Int, o: Float) {
        pos = p.coerceIn(0, max(count - 1, 0)).toFloat()
        advance(o)
    }

    /** Past a page's edges the strip moves less than the finger: it holds back. */
    private fun damp(raw: Float, r: Float) = when {
        raw < 0f -> raw * HOLD
        raw > r -> r + (raw - r) * HOLD
        else -> raw
    }

    private fun undamp(d: Float, r: Float) = when {
        d < 0f -> d / HOLD
        d > r -> r + (d - r) / HOLD
        else -> d
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
        val p = floor(pos + 0.001f).toInt().coerceIn(0, max(count - 1, 0))
        val r = room(p)
        val o = offsetIn(p)
        if (forward) {
            if (o < r - 1f) {
                glideToOffset(p, min(r, o + width * 0.9f))
            } else if (p < count - 1) {
                glideToOffset(p + 1, 0f, top = true)
            }
        } else {
            if (o > 1f) {
                glideToOffset(p, max(0f, o - width * 0.9f))
            } else if (p > 0) {
                glideToOffset(p - 1, room(p - 1), top = false)
            }
        }
    }

    /**
     * Glides to [o] pixels into page [p]; to that page's top or bottom too
     * ([top]), on turning to it, when it's taller than the screen.
     */
    private fun glideToOffset(p: Int, o: Float, top: Boolean? = null) {
        stopMoving()
        val from = pos
        val fromTy = ty
        placeAt(p, o)
        clampPos()
        val target = pos
        val targetTy = top?.let { edgeTy(it) } ?: ty
        pos = from
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                pos = from + (target - from) * f
                ty = fromTy + (targetTy - fromTy) * f
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
            if (fitAspect == 0f && p.width > 0 && p.height > p.width) fitAspect = p.width.toFloat() / p.height
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
        fitAspect = 0f
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
        // Turned the other way: the page from its top.
        if ((w > h) != (oldw > oldh)) ty = 0f
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
            // Within one page: the fling stops at its edge.
            placeAt(flingPage, scroller.currX.toFloat())
            if (pos >= maxPos()) scroller.forceFinished(true)
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

    /**
     * This touch was a pinch. Lifting the fingers after one isn't a fling or
     * a slide to settle: the fingers' last movement would carry the page
     * away from where you zoomed.
     */
    private var pinched = false

    /** The page a slide started on, and how far the finger has gone into it (before holding back). */
    private var homePage = 0
    private var dragRaw = 0f
    private var rebase = false

    /** Takes where the strip is now as the slide's start (after a pinch moved it). */
    private fun startSlide() {
        homePage = floor(pos + 0.001f).toInt().coerceIn(0, max(count - 1, 0))
        dragRaw = undamp(offsetIn(homePage), room(homePage))
        rebase = false
    }

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
            pinched = true
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
            rebase = true
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            stopMoving()
            startPage = floor(pos + 0.5f).toInt()
            dragged = false
            startSlide()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            listener?.onTap()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1.05f) zoomAround(1f / zoom, e.x, e.y) else zoomAround(2.5f, e.x, e.y)
            rebase = true
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (!scaling.isInProgress && !dragged) listener?.onHold(e.x, e.y)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaling.isInProgress || e2.pointerCount > 1) return false
            dragged = true
            if (rebase) startSlide()
            if (wholePages && zoom <= 1.01f) {
                advance(along(dx))
            } else {
                dragRaw += along(dx)
                placeAt(homePage, damp(dragRaw, room(homePage)))
            }
            if (stripHeight > height) ty -= dy
            clampPos()
            changed()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaling.isInProgress || e2.pointerCount > 1) return false
            if (pinched) {
                flung = true
                return true
            }
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
            if (rebase) startSlide()
            val p = homePage
            val r = room(p)
            val d = damp(dragRaw, r)
            val v = -along(vx)
            val nudge = width * FLICK_PAST
            when {
                // Pushed a little past the edge, then flicked on: the next page.
                d > r + nudge && v > flingSpeed && p < count - 1 -> { glideToOffset(p + 1, 0f, top = true); flung = true; return true }
                d < -nudge && v < -flingSpeed && p > 0 -> { glideToOffset(p - 1, room(p - 1), top = false); flung = true; return true }
                // Past the edge but not far enough: back.
                d < 0f || d > r -> { settle(); flung = true; return true }
            }
            flingPage = p
            flinging = true
            val sh = stripHeight
            val minY = if (sh > height) (height - sh).toInt() else ty.toInt()
            scroller.fling(d.toInt(), ty.toInt(), v.toInt(), if (sh > height) vy.toInt() else 0,
                0, r.toInt(), minY, if (sh > height) 0 else ty.toInt())
            postInvalidateOnAnimation()
            flung = true
            return true
        }
    })

    private var flung = false

    /**
     * After a slide: past the page's edge far enough, on to the next page;
     * not far enough, back to the edge. Whole pages: to the nearest page.
     */
    private fun settle() {
        if (count == 0) {
            invalidate()
            return
        }
        if (!wholePages || zoom > 1.01f) {
            if (rebase) startSlide()
            val p = homePage
            val r = room(p)
            val d = damp(dragRaw, r)
            val turn = width * PUSH_PAST
            when {
                d > r + turn && p < count - 1 -> glideToOffset(p + 1, 0f, top = true)
                d < -turn && p > 0 -> glideToOffset(p - 1, room(p - 1), top = false)
                d < 0f || d > r -> glideToOffset(p, d.coerceIn(0f, r))
                else -> invalidate()
            }
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
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            flung = false
            pinched = false
        }
        scaling.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (dragged && !flung && !pinched) settle()
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private companion object {
        /** How much of the finger's movement past a page's edge the strip follows. */
        const val HOLD = 0.45f

        /** How far past the edge (of the screen's width) a slide must show to turn the page. */
        const val PUSH_PAST = 0.18f

        /** How far past the edge a slide must show for a flick to turn the page. */
        const val FLICK_PAST = 0.06f
    }
}
