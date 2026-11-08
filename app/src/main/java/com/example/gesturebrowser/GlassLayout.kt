package com.example.gesturebrowser

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import android.widget.LinearLayout

/**
 * 透明玻璃容器（底栏、搜索栏用）。
 * - 半透明白底 + 顶部高光 + 内亮边 + 外细边，背后的内容透出来；
 * - Android 12+ 且背后是原生界面（主页）时，实时把背后内容高斯模糊并提高饱和度，做成真正的毛玻璃；
 * - 背后是网页时不做实时模糊（WebView 不适合二次绘制），只做透明玻璃，滚动时网页从玻璃后面穿过。
 * android:tag 写圆角半径（dp），默认 24。
 */
class GlassLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private val d = resources.displayMetrics.density
    private val radius get() = ((tag as? String)?.toFloatOrNull() ?: 24f) * d

    /** 背后要模糊的原生视图（null = 不模糊） */
    var source: (() -> View?)? = null
    /** 整窗背景（主页的氛围底色），模糊时一起画进去 */
    var backdrop: Drawable? = null

    private val node = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) RenderNode("glass") else null
    private val path = Path()
    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.4f * d; color = 0xD9FFFFFF.toInt() }
    private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.8f * d; color = 0x1A000000 }
    private val me = IntArray(2)
    private val them = IntArray(2)
    /** 返回 true 时每帧刷新（例如编辑态胶囊在晃动） */
    var live: (() -> Boolean)? = null
    private var liveUntil = 0L
    /** 接下来 ms 毫秒内每帧刷新（入场动画期间）；平时只在主页重绘时刷新，省电也更流畅 */
    fun pump(ms: Long) { liveUntil = android.os.SystemClock.uptimeMillis() + ms; invalidate() }
    private val pre = ViewTreeObserver.OnPreDrawListener {
        if ((live?.invoke() == true || android.os.SystemClock.uptimeMillis() < liveUntil) && source?.invoke() != null) invalidate()
        true
    }

    companion object { @JvmStatic var recording = false }

    init { setWillNotDraw(false) }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); viewTreeObserver.addOnPreDrawListener(pre) }
    override fun onDetachedFromWindow() { viewTreeObserver.removeOnPreDrawListener(pre); super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        path.reset(); path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        shine.shader = LinearGradient(0f, 0f, 0f, h * 0.55f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        node?.setRenderEffect(RenderEffect.createChainEffect(
            RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.7f) })),
            RenderEffect.createBlurEffect(18 * d, 18 * d, Shader.TileMode.CLAMP)))
    }

    override fun onDraw(canvas: Canvas) {
        val src = source?.invoke()
        val blurred = src != null && node != null && canvas.isHardwareAccelerated && width > 0 && height > 0
        canvas.save()
        canvas.clipPath(path)
        if (blurred) {
            node!!.setPosition(0, 0, width, height)
            val rc = node.beginRecording(width, height)
            getLocationInWindow(me)
            val bg = backdrop
            if (bg != null) {
                rc.save(); rc.translate(-me[0].toFloat(), -me[1].toFloat())
                bg.setBounds(0, 0, rootView.width, rootView.height); bg.draw(rc); rc.restore()
            }
            src!!.getLocationInWindow(them)
            rc.save()
            rc.translate((them[0] - me[0] - src.scrollX).toFloat(), (them[1] - me[1] - src.scrollY).toFloat())
            recording = true
            try { src.draw(rc) } finally { recording = false }
            rc.restore()
            node.endRecording()
            canvas.drawRenderNode(node)
        }
        fill.color = if (blurred) 0x66FFFFFF else 0x8CFFFFFF.toInt()
        canvas.drawRect(rect, fill)
        canvas.drawRect(rect, shine)
        canvas.restore()
        val h = inner.strokeWidth / 2
        canvas.drawRoundRect(h, h, width - h, height - h, radius - h, radius - h, inner)
        canvas.drawRoundRect(rect, radius, radius, outer)
        super.onDraw(canvas)
    }
}
