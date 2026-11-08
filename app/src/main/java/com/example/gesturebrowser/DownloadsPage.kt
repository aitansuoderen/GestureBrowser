package com.example.gesturebrowser

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** 下载页（参照 UC 下载管理）：顶部总网速 / 任务数，分"正在下载 / 已完成"两组，每行进度条 + 右侧暂停 / 继续键 */
class DownloadsPage(private val act: Activity, private val provider: () -> List<Item>, private val onFiles: () -> Unit) {

    enum class St { RUN, WAIT, PAUSE, FAIL, DONE }

    class Item(
        val key: String,
        val name: String,
        val total: Long,
        val got: Long,
        val speed: Long,
        val st: St,
        val status: String,          // 不在下载时左下角显示的文字（已暂停 / 等待中 / 失败原因）
        val note: String? = null,    // 第三行灰色小字提示
        val pct: Int = if (total > 0) (got * 100 / total).toInt().coerceIn(0, 100) else 0,
        val onTap: () -> Unit,
        val onButton: (() -> Unit)? = null,
        val menu: List<Pair<String, () -> Unit>>
    )

    private val c: Context = act
    private fun dp(v: Int) = (v * c.resources.displayMetrics.density).toInt()
    private val BLUE = 0xFF2F95FF.toInt()
    private val TEXT = 0xFF1E2329.toInt()
    private val SUB = 0xFF8A9099.toInt()

    val dialog = Dialog(c, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
    private val bigText = TextView(c).apply {
        textSize = 44f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val chip = TextView(c).apply {
        textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setPadding(dp(22), dp(3), dp(22), dp(3))
        background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = dp(20).toFloat() }
    }
    private val list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val empty = TextView(c).apply {
        text = "还没有下载任务"; textSize = 15f; setTextColor(SUB); gravity = Gravity.CENTER
        setPadding(0, dp(80), 0, 0)
    }
    private val rows = HashMap<String, RowView>()
    private val runHeader = header()
    private val doneHeader = header()
    private val ticker = object : Runnable { override fun run() { refresh(); list.postDelayed(this, 1000) } }

    init {
        val root = FrameLayout(c)
        // 蓝色渐变头部
        val head = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF74BBFF.toInt(), 0xFF2B92FF.toInt()))
        }
        val bar = FrameLayout(c)
        bar.addView(ImageView(c).apply {
            setImageResource(R.drawable.ic_back); setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER; background = Ui.ripple(c, true)
            setOnClickListener { dialog.dismiss() }
        }, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(6) })
        head.addView(bar, LinearLayout.LayoutParams(-1, dp(52)))
        head.addView(bigText, LinearLayout.LayoutParams(-1, -2))
        head.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(2) })
        head.addView(View(c), LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(head, FrameLayout.LayoutParams(-1, -2))

        // 白色圆角卡片，压在头部下沿
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                val r = dp(26).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        val scroll = ScrollView(c).apply { isFillViewport = true }
        val inner = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(24)) }
        inner.addView(list, LinearLayout.LayoutParams(-1, -2))
        inner.addView(empty, LinearLayout.LayoutParams(-1, -2))
        scroll.addView(inner)
        card.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        // 底部：下载 / 文件
        card.addView(View(c).apply { setBackgroundColor(0xFFEDEEF0.toInt()) }, LinearLayout.LayoutParams(-1, 1))
        val tabs = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.addView(tab(R.drawable.ic_download, "下载", true) {}, LinearLayout.LayoutParams(0, dp(62), 1f))
        tabs.addView(tab(0, "文件", false) { onFiles() }, LinearLayout.LayoutParams(0, dp(62), 1f))
        card.addView(tabs, LinearLayout.LayoutParams(-1, -2))
        val cardLp = FrameLayout.LayoutParams(-1, -1)
        root.addView(card, cardLp)
        head.viewTreeObserver.addOnGlobalLayoutListener {
            val top = head.height - dp(26)
            if (cardLp.topMargin != top) { cardLp.topMargin = top; card.layoutParams = cardLp }
        }

        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(-1, -1)
            statusBarColor = 0xFF5FB0FF.toInt()
            navigationBarColor = Color.WHITE
            decorView.systemUiVisibility = 0
            setWindowAnimations(android.R.style.Animation_Activity)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            head.setPadding(0, 0, 0, 0)
            v.setPadding(0, b.top, 0, b.bottom)
            ins
        }
        root.setBackgroundColor(0xFF5FB0FF.toInt())
        dialog.setOnDismissListener { list.removeCallbacks(ticker) }
    }

    fun show() { ticker.run(); dialog.show() }

    private fun tab(icon: Int, label: String, selected: Boolean, onClick: () -> Unit) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        background = Ui.ripple(c)
        setOnClickListener { onClick() }
        val col = if (selected) TEXT else 0xFF5A6068.toInt()
        if (icon != 0) addView(ImageView(c).apply { setImageResource(icon); setColorFilter(col) }, LinearLayout.LayoutParams(dp(24), dp(24)))
        else addView(FolderIcon(c, col), LinearLayout.LayoutParams(dp(24), dp(24)))
        addView(TextView(c).apply {
            text = label; textSize = 12f; setTextColor(col); paint.isFakeBoldText = selected; gravity = Gravity.CENTER
        })
    }

    private fun header() = TextView(c).apply {
        textSize = 16f; paint.isFakeBoldText = true; setTextColor(0xFF5A6068.toInt())
        setPadding(dp(22), dp(18), dp(22), dp(6))
    }

    fun refresh() {
        val items = provider()
        val running = items.filter { it.st != St.DONE }
        val done = items.filter { it.st == St.DONE }
        val active = running.filter { it.st == St.RUN }
        when {
            active.isNotEmpty() -> {
                bigText.text = speedText(active.sumOf { it.speed })
                chip.text = "${active.size} 任务"; chip.visibility = View.VISIBLE
            }
            running.any { it.st == St.PAUSE || it.st == St.FAIL } -> { bigText.text = "已暂停"; chip.visibility = View.INVISIBLE }
            running.isNotEmpty() -> { bigText.text = "等待中"; chip.visibility = View.INVISIBLE }
            else -> { bigText.text = "下载"; chip.text = "${done.size} 个文件"; chip.visibility = if (done.isEmpty()) View.INVISIBLE else View.VISIBLE }
        }
        runHeader.text = "正在下载  ${running.size}"
        doneHeader.text = "已完成  ${done.size}"
        // 按顺序摆放（复用行视图，避免每秒重建导致长按菜单失效）
        val wanted = mutableListOf<View>()
        if (running.isNotEmpty()) { wanted += runHeader; running.forEach { wanted += rowFor(it) } }
        if (done.isNotEmpty()) { wanted += doneHeader; done.forEach { wanted += rowFor(it) } }
        val keys = items.map { it.key }.toSet()
        rows.keys.retainAll(keys)
        val same = list.childCount == wanted.size && wanted.indices.all { list.getChildAt(it) === wanted[it] }
        if (!same) { list.removeAllViews(); wanted.forEach { (it.parent as? ViewGroup)?.removeView(it); list.addView(it) } }
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun rowFor(it: Item): RowView = (rows[it.key] ?: RowView().also { r -> rows[it.key] = r }).apply { bind(it) }

    private fun speedText(b: Long): String = when {
        b < 1024 -> "$b B/s"
        b < 1024 * 1024 -> "%.0f K/s".format(b / 1024.0)
        else -> "%.1f M/s".format(b / 1048576.0)
    }

    private fun size(b: Long): String = when {
        b <= 0 -> "0 B"
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.2f MB".format(b / 1048576.0)
        else -> "%.2f GB".format(b / 1073741824.0)
    }

    private fun rowSpeed(b: Long): String = when {
        b < 1024 -> "$b B/s"
        b < 1024 * 1024 -> "%.1f K/s".format(b / 1024.0)
        else -> "%.2f M/s".format(b / 1048576.0)
    }

    // ---------------------------------------------------------------- 行

    private inner class RowView : LinearLayout(c) {
        val icon = FileIcon(c)
        val name = TextView(c).apply { textSize = 16f; setTextColor(TEXT); maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
        val total = TextView(c).apply { textSize = 14f; setTextColor(SUB); setPadding(dp(8), 0, 0, 0) }
        val bar = Bar(c)
        val leftTv = TextView(c).apply { textSize = 13f; setTextColor(SUB); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val rightTv = TextView(c).apply { textSize = 13f; setTextColor(SUB); setPadding(dp(8), 0, 0, 0) }
        val note = TextView(c).apply { textSize = 12f; setTextColor(0xFFA0A6AE.toInt()); maxLines = 2 }
        val btn = RoundBtn(c)
        var item: Item? = null

        init {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(14), dp(12))
            background = Ui.ripple(c)
            addView(icon, LayoutParams(dp(42), dp(48)))
            val mid = LinearLayout(c).apply { orientation = VERTICAL; setPadding(dp(14), 0, dp(10), 0) }
            mid.addView(LinearLayout(c).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(this@RowView.name, LayoutParams(0, -2, 1f)); addView(this@RowView.total)
            })
            mid.addView(bar, LayoutParams(-1, dp(5)).apply { topMargin = dp(8); bottomMargin = dp(6) })
            mid.addView(LinearLayout(c).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(this@RowView.leftTv, LayoutParams(0, -2, 1f)); addView(this@RowView.rightTv)
            })
            mid.addView(note, LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(mid, LayoutParams(0, -2, 1f))
            addView(btn, LayoutParams(dp(46), dp(46)))
            setOnClickListener { item?.onTap?.invoke() }
            setOnLongClickListener { v -> item?.let { Ui.popup(v, it.menu, v.width / 3) }; true }
            btn.setOnClickListener { item?.let { (it.onButton ?: it.onTap)() } }
        }

        fun bind(it: Item) {
            item = it
            icon.type = it.name
            name.text = it.name
            total.text = if (it.total > 0) size(it.total) else ""
            if (it.st == St.DONE) {
                bar.visibility = GONE; btn.visibility = GONE
                leftTv.text = it.status.ifBlank { "已完成" }; rightTv.text = ""
            } else {
                bar.visibility = VISIBLE; btn.visibility = VISIBLE
                bar.pct = it.pct
                bar.color = when (it.st) { St.RUN -> BLUE; St.FAIL -> 0xFFF0605A.toInt(); else -> 0xFF8A8F96.toInt() }
                bar.indeterminate = it.total <= 0 && it.st == St.RUN
                bar.invalidate()
                leftTv.text = if (it.st == St.RUN) rowSpeed(it.speed) else it.status
                leftTv.setTextColor(if (it.st == St.FAIL) 0xFFE0524C.toInt() else SUB)
                rightTv.text = size(it.got)
                btn.mode = if (it.st == St.RUN || it.st == St.WAIT) RoundBtn.PAUSE else RoundBtn.DOWN
                btn.invalidate()
            }
            note.text = it.note ?: ""
            note.visibility = if (it.note.isNullOrBlank()) GONE else VISIBLE
        }
    }

    private class Bar(c: Context) : View(c) {
        var pct = 0; var color = 0xFF2F95FF.toInt(); var indeterminate = false
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        override fun onDraw(cv: Canvas) {
            val h = height.toFloat(); val w = width.toFloat()
            p.color = 0xFFE4E6EA.toInt(); r.set(0f, 0f, w, h); cv.drawRoundRect(r, h / 2, h / 2, p)
            p.color = color
            if (indeterminate) {
                val t = (System.currentTimeMillis() % 1500) / 1500f
                r.set(w * t - w * 0.25f, 0f, w * t, h); r.left = r.left.coerceAtLeast(0f)
                cv.drawRoundRect(r, h / 2, h / 2, p); postInvalidateDelayed(30)
            } else if (pct > 0) { r.set(0f, 0f, w * pct / 100f, h); cv.drawRoundRect(r, h / 2, h / 2, p) }
        }
    }

    private class RoundBtn(c: Context) : View(c) {
        companion object { const val PAUSE = 0; const val DOWN = 1 }
        var mode = PAUSE
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val d = c.resources.displayMetrics.density
        override fun onDraw(cv: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val rad = minOf(cx, cy) - d
            p.style = Paint.Style.STROKE; p.strokeWidth = d; p.color = 0xFFDDE0E4.toInt()
            cv.drawCircle(cx, cy, rad, p)
            p.color = 0xFF2A2F36.toInt()
            if (mode == PAUSE) {
                p.style = Paint.Style.FILL
                val bw = 3 * d; val bh = 8 * d; val gap = 3 * d
                cv.drawRoundRect(cx - gap - bw, cy - bh, cx - gap, cy + bh, d, d, p)
                cv.drawRoundRect(cx + gap, cy - bh, cx + gap + bw, cy + bh, d, d, p)
            } else {
                p.style = Paint.Style.STROKE; p.strokeWidth = 2.6f * d; p.strokeCap = Paint.Cap.ROUND
                cv.drawLine(cx, cy - 9 * d, cx, cy + 4 * d, p)
                cv.drawLine(cx - 6 * d, cy - 2 * d, cx, cy + 4 * d, p)
                cv.drawLine(cx + 6 * d, cy - 2 * d, cx, cy + 4 * d, p)
                cv.drawLine(cx - 8 * d, cy + 9 * d, cx + 8 * d, cy + 9 * d, p)
            }
        }
    }

    /** 文件类型图标：折角的圆角方块，视频画播放三角，其它写扩展名 */
    private class FileIcon(c: Context) : View(c) {
        var type: String = ""
            set(v) { if (field != v) { field = v; invalidate() } }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val d = c.resources.displayMetrics.density
        override fun onDraw(cv: Canvas) {
            val ext = type.substringAfterLast('.', "").lowercase()
            val (col, label) = when (ext) {
                "mp4", "mkv", "webm", "mov", "avi", "flv", "ts", "m4v", "m3u8" -> 0xFFEF2D23.toInt() to "▶"
                "mp3", "m4a", "flac", "wav", "aac", "ogg" -> 0xFFFF8A00.toInt() to "♪"
                "apk", "xapk", "apks" -> 0xFF2DBE60.toInt() to "APK"
                "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> 0xFF1E9BF0.toInt() to "IMG"
                "zip", "rar", "7z", "tar", "gz" -> 0xFF8E5BE8.toInt() to "ZIP"
                "pdf" -> 0xFFE5483D.toInt() to "PDF"
                "doc", "docx", "txt", "xls", "xlsx", "ppt", "pptx" -> 0xFF3D7BF0.toInt() to ext.take(3).uppercase()
                else -> 0xFF9AA1AB.toInt() to (ext.take(4).uppercase().ifBlank { "FILE" })
            }
            val w = width.toFloat(); val h = height.toFloat(); val fold = w * 0.3f; val r = 5 * d
            val path = Path().apply {
                moveTo(r, 0f); lineTo(w - fold, 0f); lineTo(w, fold); lineTo(w, h - r); quadTo(w, h, w - r, h)
                lineTo(r, h); quadTo(0f, h, 0f, h - r); lineTo(0f, r); quadTo(0f, 0f, r, 0f); close()
            }
            p.style = Paint.Style.FILL; p.color = col; cv.drawPath(path, p)
            p.color = 0x55FFFFFF; cv.drawPath(Path().apply { moveTo(w - fold, 0f); lineTo(w, fold); lineTo(w - fold, fold); close() }, p)
            p.color = Color.WHITE
            if (label == "▶") {
                val cx = w / 2; val cy = h * 0.58f; val s = w * 0.2f
                cv.drawPath(Path().apply { moveTo(cx - s * 0.8f, cy - s); lineTo(cx + s, cy); lineTo(cx - s * 0.8f, cy + s); close() }, p)
            } else {
                p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true
                p.textSize = if (label.length >= 4) w * 0.24f else if (label.length == 1) w * 0.45f else w * 0.3f
                cv.drawText(label, w / 2, h * 0.58f - (p.ascent() + p.descent()) / 2, p)
            }
        }
    }

    private class FolderIcon(c: Context, private val col: Int) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val d = c.resources.displayMetrics.density
        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = col; p.strokeJoin = Paint.Join.ROUND
            val path = Path().apply {
                moveTo(2 * d, 5 * d); lineTo(9 * d, 5 * d); lineTo(11 * d, 7.5f * d); lineTo(w - 2 * d, 7.5f * d)
                lineTo(w - 2 * d, h - 4 * d); lineTo(2 * d, h - 4 * d); close()
            }
            cv.drawPath(path, p)
            p.strokeCap = Paint.Cap.ROUND
            cv.drawLine(7 * d, h - 8.5f * d, 11 * d, h - 8.5f * d, p)
        }
    }
}
