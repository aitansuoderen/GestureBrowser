package com.example.gesturebrowser

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator

/** 动效与玻璃材质的公共工具 */
object Motion {
    /** 接近 iOS 的顺滑曲线 */
    val EASE = PathInterpolator(0.2f, 0.9f, 0.1f, 1f)
    private val DEC = DecelerateInterpolator(2f)

    /**
     * 弹簧插值（阻尼谐振子），手感接近 iOS：
     * damping 阻尼比（1 = 不回弹，0.7 左右轻微回弹），response 一次振荡的周期（秒），durMs 动画总时长。
     */
    class Spring(private val damping: Float, private val response: Float, private val durMs: Long) : android.view.animation.Interpolator {
        private val w = (2 * Math.PI / response)
        private val wd = w * Math.sqrt((1 - damping * damping).toDouble().coerceAtLeast(1e-4))
        override fun getInterpolation(f: Float): Float {
            if (f >= 1f) return 1f
            val t = f * durMs / 1000.0
            val e = Math.exp(-damping * w * t)
            return (1 - e * (Math.cos(wd * t) + damping * w / wd * Math.sin(wd * t))).toFloat()
        }
    }
    /** 平滑无回弹（页面、托盘） */
    fun smooth(dur: Long = 320) = Spring(0.92f, 0.3f, dur)
    /** 轻快带一点回弹（按钮、角标、卡片） */
    fun bouncy(dur: Long = 380) = Spring(0.7f, 0.28f, dur)

    /** 按下缩小、松手弹回（不影响原有点击/长按） */
    @SuppressLint("ClickableViewAccessibility")
    fun press(v: View, scale: Float = 0.96f) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(scale).scaleY(scale).alpha(0.85f).setDuration(90).setInterpolator(EASE).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(360).setInterpolator(bouncy(360)).start()
            }
            false
        }
    }

    /** 淡入 + 轻微放大（页面切换） */
    fun enter(v: View, fromScale: Float = 0.96f, dur: Long = 300) {
        v.animate().cancel()
        v.alpha = 0f; v.scaleX = fromScale; v.scaleY = fromScale
        v.animate().alpha(1f).setDuration((dur * 0.5).toLong()).setInterpolator(EASE).start()
        v.animate().scaleX(1f).scaleY(1f).setDuration(dur).setInterpolator(smooth(dur)).start()
    }

    /** 从下方滑入淡入 */
    fun rise(v: View, dy: Float, delay: Long = 0, dur: Long = 320) {
        v.animate().cancel()
        v.alpha = 0f; v.translationY = dy
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(dur + 80).setInterpolator(Spring(0.86f, 0.3f, dur + 80)).start()
    }

    fun fadeOut(v: View, dur: Long = 180, scaleTo: Float = 1f, dy: Float = 0f, end: () -> Unit) {
        v.animate().cancel()
        v.animate().alpha(0f).scaleX(scaleTo).scaleY(scaleTo).translationY(dy).setStartDelay(0).setDuration(dur)
            .setInterpolator(DEC).withEndAction {
                end(); v.alpha = 1f; v.scaleX = 1f; v.scaleY = 1f; v.translationY = 0f
            }.start()
    }

    /** 弹一下（数字变化等） */
    fun bump(v: View) {
        v.animate().cancel()
        v.scaleX = 1.22f; v.scaleY = 1.22f
        v.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(bouncy(380)).start()
    }

    val canBlur get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** 把某个视图（比如背后的网页）动态模糊，打造毛玻璃。Android 12 以下不做 */
    fun blur(v: View, on: Boolean, max: Float = 60f) {
        if (!canBlur) return
        val tag = R.id.progress
        (v.getTag(tag) as? ValueAnimator)?.cancel()
        val from = (v.getTag(R.id.bottomBar) as? Float) ?: 0f
        val to = if (on) max else 0f
        val a = ValueAnimator.ofFloat(from, to).apply {
            duration = if (on) 260 else 200
            interpolator = EASE
            addUpdateListener {
                val r = it.animatedValue as Float
                v.setTag(R.id.bottomBar, r)
                v.setRenderEffect(if (r < 0.5f) null else RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
            }
        }
        v.setTag(tag, a)
        a.start()
    }

    /** 弹窗背后整窗口模糊（Android 12+） */
    fun windowBlur(w: Window?, radius: Int = 48) {
        if (w == null || !canBlur) return
        w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        w.attributes = w.attributes.apply { blurBehindRadius = radius }
    }

    /** 玻璃底色：半透明白 + 亮边 */
    /** 透明玻璃：低不透明度白 + 亮边（stroke 为亮边粗细） */
    fun glass(radius: Float, alpha: Int = 0x8C, stroke: Int = 2, tint: Int = 0xFFFFFF): GradientDrawable =
        GradientDrawable().apply {
            setColor((alpha shl 24) or tint)
            cornerRadius = radius
            setStroke(stroke.coerceAtLeast(1), 0xE6FFFFFF.toInt())
        }
}
