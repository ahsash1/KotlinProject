package org.example.project.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Full-screen transparent canvas: draws a pulsing ring around the current
 * target and a high-contrast instruction banner. Plain View + Canvas rather
 * than Compose -- a ComposeView hosted in a non-Activity WindowManager
 * window needs its own Lifecycle/SavedStateRegistry/ViewModelStore owners
 * wired up by hand, which is extra surface area for no visual benefit here.
 */
class OverlayView(context: Context) : View(context) {

    enum class Mode { POINTING, STUCK, LOST, FINISHED }

    var mode: Mode = Mode.POINTING
        set(value) { field = value; invalidate() }

    /** Target bounds in screen coordinates, or null when nothing to point at. */
    var targetRect: Rect? = null
        set(value) { field = value; invalidate() }

    var message: String = ""
        set(value) { field = value; invalidate() }

    private val ringFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
    }
    private val bannerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bannerTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 46f
        isFakeBoldText = true
    }

    private var pulsePhase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulsePhase = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        setWillNotDraw(false)
    }

    fun startAnimating() {
        if (!animator.isStarted) animator.start()
    }

    fun stopAnimating() {
        animator.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (mode) {
            Mode.POINTING -> drawPointing(canvas)
            Mode.STUCK -> drawBanner(canvas, STUCK_COLOR, message)
            Mode.LOST -> drawBanner(canvas, LOST_COLOR, message)
            Mode.FINISHED -> drawBanner(canvas, FINISHED_COLOR, message)
        }
    }

    private fun drawPointing(canvas: Canvas) {
        val raw = targetRect ?: return drawBanner(canvas, STUCK_COLOR, message)

        val clamped = Rect(
            raw.left.coerceIn(0, width),
            raw.top.coerceIn(0, height),
            raw.right.coerceIn(0, width),
            raw.bottom.coerceIn(0, height),
        )
        if (clamped.width() <= 0 || clamped.height() <= 0) {
            drawBanner(canvas, STUCK_COLOR, message)
            return
        }

        val cx = clamped.exactCenterX()
        val cy = clamped.exactCenterY()
        // Capped, not just element-size/2: a matched element's nearest clickable ancestor can be
        // a full-width row (e.g. a list item wrapper spanning the whole screen), which would
        // otherwise balloon the ring to hundreds of pixels for something the user just needs a
        // small, clear pointer to.
        val baseRadius = minOf(maxOf(clamped.width(), clamped.height()) / 2f + 12f, MAX_BASE_RADIUS_PX)
        val radius = baseRadius + pulsePhase * 10f
        val alpha = (255 * (1f - pulsePhase * 0.55f)).toInt().coerceIn(60, 255)

        ringFillPaint.color = Color.argb((alpha * 0.22f).toInt(), 255, 106, 0)
        canvas.drawCircle(cx, cy, radius, ringFillPaint)
        ringStrokePaint.color = Color.argb(alpha, 255, 59, 0)
        canvas.drawCircle(cx, cy, radius, ringStrokePaint)

        drawBanner(canvas, POINTING_COLOR, message, anchorRect = clamped)
    }

    private fun drawBanner(canvas: Canvas, bgColor: Int, text: String, anchorRect: Rect? = null) {
        if (text.isBlank()) return

        val padding = 32f
        val maxWidth = (width * 0.82f).toInt().coerceAtLeast(200)
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, bannerTextPaint, maxWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()

        val bannerWidth = layout.width + padding * 2
        val bannerHeight = layout.height + padding * 2

        // Pin to whichever screen edge is further from the target.
        val pinToTop = anchorRect != null && anchorRect.exactCenterY() > height / 2f
        val left = ((width - bannerWidth) / 2f).coerceAtLeast(16f)
        val top = if (pinToTop) 64f else (height - bannerHeight - 64f)

        bannerBgPaint.color = bgColor
        canvas.drawRoundRect(RectF(left, top, left + bannerWidth, top + bannerHeight), 28f, 28f, bannerBgPaint)

        canvas.save()
        canvas.translate(left + padding, top + padding)
        layout.draw(canvas)
        canvas.restore()
    }

    companion object {
        private const val MAX_BASE_RADIUS_PX = 70f
        private val POINTING_COLOR = Color.argb(235, 33, 33, 33)
        private val STUCK_COLOR = Color.argb(235, 230, 120, 0)
        private val LOST_COLOR = Color.argb(235, 198, 40, 40)
        private val FINISHED_COLOR = Color.argb(235, 27, 94, 32)
    }
}
