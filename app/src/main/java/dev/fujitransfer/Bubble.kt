package dev.fujitransfer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.content.res.ResourcesCompat
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A 64 dp round badge floating over other apps while photos come in: an orange ring filling
 * with the transfer and "3/12" inside. Drag it anywhere (it settles against the nearer side
 * edge); tap it to come back to the app. Needs "Display over other apps".
 */
class Bubble(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val prefs = context.getSharedPreferences("look", 0)
    private val view = BubbleView(context)
    private val size = (64 * context.resources.displayMetrics.density).roundToInt()
    private val params = WindowManager.LayoutParams(
        size, size,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = prefs.getInt("bubbleX", -1).takeIf { it >= 0 } ?: (context.resources.displayMetrics.widthPixels - size - margin())
        y = prefs.getInt("bubbleY", -1).takeIf { it >= 0 } ?: (context.resources.displayMetrics.heightPixels / 3)
    }
    var shown = false
        private set

    init {
        view.setOnTouchListener(Dragger())
    }

    private fun margin() = (12 * context.resources.displayMetrics.density).roundToInt()

    fun show(t: Transfer?) {
        view.update(t)
        if (shown) return
        try {
            wm.addView(view, params)
            shown = true
        } catch (_: Exception) {
            // Permission withdrawn meanwhile: the notification still shows progress.
        }
    }

    fun hide() {
        if (!shown) return
        shown = false
        try {
            wm.removeView(view)
        } catch (_: Exception) {
        }
    }

    private inner class Dragger : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && abs(dx) + abs(dy) > 12 * context.resources.displayMetrics.density) moved = true
                    if (moved) {
                        params.x = (startX + dx).roundToInt().coerceAtLeast(0)
                        params.y = (startY + dy).roundToInt().coerceAtLeast(0)
                        if (shown) wm.updateViewLayout(view, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        context.startActivity(
                            Intent(context, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        )
                    } else {
                        // Rest against the nearer side, out of the way.
                        val w = context.resources.displayMetrics.widthPixels
                        params.x = if (params.x + size / 2 < w / 2) margin() else w - size - margin()
                        if (shown) wm.updateViewLayout(view, params)
                        prefs.edit().putInt("bubbleX", params.x).putInt("bubbleY", params.y).apply()
                    }
                }
            }
            return true
        }
    }

    companion object {
        fun allowed(context: Context) = Settings.canDrawOverlays(context)

        /** The system page where "Display over other apps" is switched on for this app. */
        fun permissionIntent(context: Context) =
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** Draws the badge: dark disc, grey track, orange ring, the count. */
class BubbleView(context: Context) : View(context) {
    private val d = context.resources.displayMetrics.density
    private val sans: Typeface = ResourcesCompat.getFont(context, R.font.inter_tight) ?: Typeface.DEFAULT
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF20E0E0E.toInt() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * d; color = 0xFF2A2A2A.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4 * d; strokeCap = Paint.Cap.ROUND; color = 0xFFFF9D28.toInt()
    }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(sans, Typeface.BOLD); textSize = 19 * d; color = 0xFFF2EFEA.toInt()
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(sans, Typeface.BOLD); textSize = 10 * d; color = 0xFFC9C5BD.toInt()
    }
    private val oval = RectF()
    private var progress = 0f
    private var index = ""
    private var total = ""
    private var done = false

    init {
        elevation = 6 * d
        contentDescription = "Tiến độ tải ảnh"
    }

    fun update(t: Transfer?) {
        done = t == null
        if (t != null) {
            val part = if (t.fileTotal > 0) t.fileDone.toFloat() / t.fileTotal else 0f
            progress = ((t.index + part) / t.total.coerceAtLeast(1)).coerceIn(0f, 1f)
            index = "${t.index + 1}"
            total = "/${t.total}"
        } else {
            progress = 1f
        }
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawCircle(w / 2, h / 2, w / 2, disc)
        val inset = 6 * d
        oval.set(inset, inset, w - inset, h - inset)
        c.drawArc(oval, 0f, 360f, false, track)
        c.drawArc(oval, -90f, 360f * progress, false, ring)
        if (done) {
            big.color = 0xFFFF9D28.toInt()
            val tick = "✓"
            c.drawText(tick, (w - big.measureText(tick)) / 2, h / 2 + big.textSize * 0.36f, big)
            return
        }
        big.color = 0xFFF2EFEA.toInt()
        // "3" large and "/12" small, centred together on one baseline; "112/340" shrinks to fit.
        big.textSize = 19 * d
        small.textSize = 10 * d
        val room = w - 2 * inset - 10 * d
        val k = minOf(1f, room / (big.measureText(index) + small.measureText(total)))
        big.textSize *= k
        small.textSize *= k
        val wi = big.measureText(index)
        val wt = small.measureText(total)
        val x = (w - wi - wt) / 2
        val base = h / 2 + big.textSize * 0.36f
        c.drawText(index, x, base, big)
        c.drawText(total, x + wi, base, small)
    }
}
