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
 * The pages in one strip, read by sliding along it: held upright, side by
 * side, each as tall as the screen (a phone is tall), so a page wider than
 * the screen is slid across freely; held sideways, one under the other,
 * each as wide as the screen, read down by sliding up. Either way a fling
 * rolls on within the page, and at a page's end the strip holds back: the
 * next page only comes if you push on past a point (or push a little and
 * flick), otherwise it springs back, and a fling stops at the page's end.
 * Pinch or double-tap to zoom (then slide the other way too).
 *
 * "Whole pages" instead: one page to a screen, whole, side by side, and a
 * slide (however short or slow) goes to the next one.
 *
 * Where you are is kept as a page and how far along it, so pages whose
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

    /** One whole page to a screen, rather than each as tall (or wide) as the screen. */
    var wholePages = false
        set(v) {
            field = v
            zoom = 1f
            across = 0f
            pos = floor(pos + 0.5f)
            startSlide()
            settle()
        }

    /** Manga: page 1 on the right, read leftwards (held upright). */
    var rightToLeft = false
        set(v) {
            field = v
            invalidate()
        }

    /** Where the screen's leading edge is: page, and how far along it (0 to 1). */
    private var pos = 0f
    private var zoom = 1f

    /** Where the strip's side is across the screen (its top; held sideways, its left): 0 or less. */
    private var across = 0f
    private var current = -1
    private var aspectGuess = 0.68f
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

    /** Held sideways (and not whole pages): pages one under the other, read downwards. */
    private val vertical get() = width > height && !wholePages

    /** The screen along the strip, and across it. */
    private val mainLen get() = (if (vertical) height else width).toFloat()
    private val crossLen get() = (if (vertical) width else height).toFloat()

    /** How long page [i]'s place in the strip is, on screen (its width; held sideways, its height). */
    private fun slot(i: Int): Float {
        if (width == 0 || height == 0) return 1f
        val l = when {
            wholePages -> width.toFloat()
            vertical -> crossLen / aspect(i)
            else -> crossLen * aspect(i)
        }
        return max(l * zoom, 1f)
    }

    /** How wide the strip is across (its height; held sideways, its width). */
    private val stripCross get() = crossLen * zoom

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

    /** How far page [p] can be slid along before its far end reaches the screen's. */
    private fun room(p: Int) = max(0f, slot(p) - mainLen)

    /** The leading edge [o] pixels into page [p]. */
    private fun placeAt(p: Int, o: Float) {
        pos = p.coerceIn(0, max(count - 1, 0)).toFloat()
        advance(o)
    }

    /** Past a page's ends the strip moves less than the finger: it holds back. */
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

    /** The furthest the strip goes: the last page's far end at the screen's. */
    private fun maxPos(): Float {
        if (count == 0) return 0f
        if (wholePages) return (count - 1).toFloat()
        var need = mainLen
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
        val sc = stripCross
        across = if (sc <= crossLen) 0f else across.coerceIn(crossLen - sc, 0f)
        return pos != before
    }

    /** Draws and reports what's on screen. */
    private fun changed() {
        invalidate()
        if (count == 0 || width == 0) return
        val len = mainLen
        // The page the middle of the screen is on.
        var p = floor(pos).toInt().coerceIn(0, count - 1)
        var x = -(pos - p) * slot(p)
        var middle = p
        var last = p
        while (x < len && last < count) {
            val w = slot(last)
            if (x <= len / 2f && x + w > len / 2f) middle = last
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
        across = 0f
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

    /** On (or back) a screenful: a long page is gone along first, then the next page. */
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
                glideToOffset(p, min(r, o + mainLen * 0.9f))
            } else if (p < count - 1) {
                glideToOffset(p + 1, 0f)
            }
        } else {
            if (o > 1f) {
                glideToOffset(p, max(0f, o - mainLen * 0.9f))
            } else if (p > 0) {
                glideToOffset(p - 1, room(p - 1))
            }
        }
    }

    /** Glides to [o] pixels into page [p]. */
    private fun glideToOffset(p: Int, o: Float) {
        stopMoving()
        val from = pos
        placeAt(p, o)
        clampPos()
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
        // Turned the other way: the strip runs the other way, from the page's start.
        if ((w > h) != (oldw > oldh)) {
            stopMoving()
            pos = floor(pos + 0.001f)
            across = 0f
        }
        clampPos()
        current = -1
        changed()
    }

    // ---- drawing ----

    override fun onDraw(canvas: Canvas) {
        val src = source ?: return
        if (count == 0 || width == 0) return
        val v = vertical
        val len = mainLen
        val sc = stripCross
        val side = if (sc <= crossLen) (crossLen - sc) / 2 else across
        var p = floor(pos).toInt().coerceIn(0, count - 1)
        var m = -(pos - p) * slot(p)
        while (m < len && p < count) {
            val s = slot(p)
            // The page's place on screen.
            val boxL: Float
            val boxT: Float
            val boxW: Float
            val boxH: Float
            if (v) {
                boxL = side; boxT = m; boxW = sc; boxH = s
            } else {
                boxL = if (rightToLeft) width - m - s else m; boxT = side; boxW = s; boxH = sc
            }
            val pic = src.picture(p)
            if (pic != null) {
                // Fitted inside its place, in the middle of it.
                val a = aspect(p)
                var dw = boxW
                var dh = dw / a
                if (dh > boxH) {
                    dh = boxH
                    dw = dh * a
                }
                val l = boxL + (boxW - dw) / 2
                val t = boxT + (boxH - dh) / 2
                pic.drawable.setBounds(l.roundToInt(), t.roundToInt(), (l + dw).roundToInt(), (t + dh).roundToInt())
                pic.drawable.draw(canvas)
            } else {
                val text = if (src.failed(p)) "Page ${p + 1} can't be shown" else "Page ${p + 1}…"
                val cx = if (v) width / 2f else boxL + boxW / 2
                val cy = if (v) (boxT + boxH / 2).coerceIn(0f, height.toFloat()) else height / 2f
                canvas.drawText(text, cx, cy, textPaint)
            }
            m += s
            p++
        }
    }

    override fun computeScroll() {
        if (flinging && scroller.computeScrollOffset()) {
            // Within one page: the fling stops at its end.
            placeAt(flingPage, scroller.currX.toFloat())
            if (pos >= maxPos()) scroller.forceFinished(true)
            across = scroller.currY.toFloat()
            clampPos()
            changed()
            postInvalidateOnAnimation()
        } else if (flinging) {
            flinging = false
        }
    }

    // ---- touch ----

    /**
     * How far a movement of the finger ([dx], [dy] screen pixels) goes along
     * the strip: sideways (manga's the other way), or held sideways, down.
     */
    private fun along(dx: Float, dy: Float) = when {
        vertical -> dy
        rightToLeft -> -dx
        else -> dx
    }

    /** How far it goes across the strip. */
    private fun crossOf(dx: Float, dy: Float) = if (vertical) dx else dy

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
        val sc = stripCross
        val side = if (sc <= crossLen) (crossLen - sc) / 2 else across
        zoom = target
        // What was under the fingers stays under them.
        val am = when {
            vertical -> fy
            rightToLeft -> width - fx
            else -> fx
        }
        val ac = if (vertical) fx else fy
        advance(am * (r - 1))
        across = ac - (ac - side) * r
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
            val dx = d.focusX - lastX
            val dy = d.focusY - lastY
            advance(-along(dx, dy))
            across += crossOf(dx, dy)
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
                advance(along(dx, dy))
            } else {
                dragRaw += along(dx, dy)
                placeAt(homePage, damp(dragRaw, room(homePage)))
            }
            if (stripCross > crossLen) across -= crossOf(dx, dy)
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
                val v = -along(vx, vy)
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
            val v = -along(vx, vy)
            val nudge = mainLen * FLICK_PAST
            when {
                // Pushed a little past the end, then flicked on: the next page.
                d > r + nudge && v > flingSpeed && p < count - 1 -> { glideToOffset(p + 1, 0f); flung = true; return true }
                d < -nudge && v < -flingSpeed && p > 0 -> { glideToOffset(p - 1, room(p - 1)); flung = true; return true }
                // Past the end but not far enough: back.
                d < 0f || d > r -> { settle(); flung = true; return true }
            }
            // Rolls on within the page, and stops at its end.
            flingPage = p
            flinging = true
            val sc = stripCross
            val wide = sc > crossLen
            val minC = if (wide) (crossLen - sc).toInt() else across.toInt()
            val vc = if (wide) crossOf(vx, vy) else 0f
            scroller.fling(d.toInt(), across.toInt(), v.toInt(), vc.toInt(),
                0, r.toInt(), minC, if (wide) 0 else across.toInt())
            postInvalidateOnAnimation()
            flung = true
            return true
        }
    })

    private var flung = false

    /**
     * After a slide: past the page's end far enough, on to the next page;
     * not far enough, back to the end. Whole pages: to the nearest page.
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
            val turn = mainLen * PUSH_PAST
            when {
                d > r + turn && p < count - 1 -> glideToOffset(p + 1, 0f)
                d < -turn && p > 0 -> glideToOffset(p - 1, room(p - 1))
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
        /** How much of the finger's movement past a page's end the strip follows. */
        const val HOLD = 0.45f

        /** How far past the end (of the screen's length along the strip) a slide must show to turn the page. */
        const val PUSH_PAST = 0.18f

        /** How far past the end a slide must show for a flick to turn the page. */
        const val FLICK_PAST = 0.06f
    }
}
