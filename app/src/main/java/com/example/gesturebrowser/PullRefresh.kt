package com.example.gesturebrowser

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.WebView
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.min

/**
 * 长按网页后向下拖动 → 刷新。
 * 长按满 0.4 秒还没移动就"上膛"；之后手指向下拖，顶部出现刷新圆钮，拖过阈值松手就刷新。
 * 长按链接的菜单改成松手时才弹出（拖动刷新时就不弹）。
 */
@SuppressLint("ClickableViewAccessibility")
class PullRefresh(private val indicator: ImageView, private val onRefresh: (WebView) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val c = indicator.context
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private val threshold = dp(110f)
    private val maxPull = dp(150f)

    /** 长按链接时要弹的菜单，松手（且没在拖动刷新）时才执行 */
    var pendingLongPress: (() -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var armed = false
    private var dragging = false
    private var current: WebView? = null
    private val arm = Runnable { armed = true }

    init {
        indicator.apply {
            setImageResource(R.drawable.ic_refresh)
            scaleType = ImageView.ScaleType.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            elevation = dp(6f)
            visibility = View.GONE
        }
    }

    fun attach(wv: WebView) {
        wv.setOnTouchListener { _, e -> onTouch(wv, e) }
    }

    private fun onTouch(wv: WebView, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                current = wv
                downX = e.x; downY = e.y
                armed = false; dragging = false
                pendingLongPress = null
                main.removeCallbacks(arm)
                main.postDelayed(arm, 400)
            }
            MotionEvent.ACTION_POINTER_DOWN -> { main.removeCallbacks(arm); armed = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                val dy = e.y - downY
                if (!armed && (abs(dx) > slop || abs(dy) > slop)) main.removeCallbacks(arm)
                if (armed && !dragging && dy > slop * 2 && dy > abs(dx)) {
                    dragging = true
                    pendingLongPress = null
                    // 让网页放弃这次触摸（取消文字选择/滚动）
                    val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                    wv.onTouchEvent(cancel)
                    cancel.recycle()
                    wv.evaluateJavascript("try{getSelection().removeAllRanges()}catch(e){}", null)
                    wv.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    indicator.visibility = View.VISIBLE
                }
                if (dragging) {
                    update(dy)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(arm)
                if (dragging) {
                    val dy = e.y - downY
                    dragging = false; armed = false
                    if (e.actionMasked == MotionEvent.ACTION_UP && dy >= threshold) {
                        onRefresh(wv)
                        indicator.animate().rotationBy(360f).setDuration(450).withEndAction { hide() }.start()
                    } else hide()
                    return true
                }
                armed = false
                if (e.actionMasked == MotionEvent.ACTION_UP) pendingLongPress?.let { p -> pendingLongPress = null; main.post(p) }
                pendingLongPress = null
            }
        }
        return false
    }

    private fun update(dy: Float) {
        val pull = min(dy, maxPull)
        val ready = dy >= threshold
        indicator.translationY = pull - dp(20f)
        indicator.rotation = pull * 2.2f
        indicator.alpha = min(1f, pull / threshold + 0.2f)
        indicator.imageTintList = ColorStateList.valueOf(if (ready) c.getColor(R.color.accent) else 0xFF8F8A83.toInt())
        val s = if (ready) 1.1f else 0.8f + 0.2f * (pull / threshold)
        indicator.scaleX = s; indicator.scaleY = s
    }

    private fun hide() {
        indicator.animate().cancel()
        indicator.animate().translationY(-dp(60f)).alpha(0f).setDuration(180).withEndAction {
            indicator.visibility = View.GONE
            indicator.rotation = 0f
        }.start()
    }

    private fun dp(v: Float) = v * c.resources.displayMetrics.density
}
