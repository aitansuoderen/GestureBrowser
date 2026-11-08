package com.example.gesturebrowser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.io.File

/**
 * 原生主页（仿 UC）：居中标志 → 下载器 / 历史记录 / 书签 → 常用网站（长按出现关闭按钮和添加网站）/ 自定义网站。
 * 搜索栏在屏幕下方、紧挨底栏（在 activity_main.xml 里）。
 */
@SuppressLint("ViewConstructor")
class HomeView(
    context: Context,
    private val icons: IconLoader,
    private val common: ShortcutStore,
    private val shortcuts: ShortcutStore,
    private val onDownloads: () -> Unit,
    private val onHistory: () -> Unit,
    private val onBookmarks: () -> Unit,
    private val onOpen: (String) -> Unit
) : ScrollView(context) {

    /** 主页内容重绘时通知玻璃栏更新模糊（代替每帧重绘） */
    var onDrawn: (() -> Unit)? = null
    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        if (!GlassLayout.recording) onDrawn?.invoke()
    }

    data class LastPage(val title: String, val url: String, val snapshot: File?)

    private val textMain = ContextCompat.getColor(context, R.color.text_main)
    private val textSub = ContextCompat.getColor(context, R.color.text_sub)

    private val commonGrid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    private val grid = GridLayout(context).apply { columnCount = COLS }
    private var expanded = false
    /** 常用网站的编辑状态：图标右上角显示关闭按钮，并出现"添加网站" */
    var editing = false
        private set
    private lateinit var doneBtn: TextView

    init {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        // 浅色底 + 下方淡淡的暖橙渐变
        // 透明：和底下整窗的渐变背景连成一片，玻璃底栏也透出同样的颜色
        background = null
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(14), dp(150))  // 底部留出浮动玻璃栏的高度
            clipChildren = false; clipToPadding = false
        }
        // 上方留白，让标志落在屏幕约 1/4 处
        content.addView(View(context), LinearLayout.LayoutParams(MATCH, dp(110)))
        content.addView(TextView(context).apply {
            text = context.getString(R.string.app_name)
            textSize = 36f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            letterSpacing = 0.02f
            setTextColor(0xFF1F1D1A.toInt())
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH, WRAP))

        val tools = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(toolCell(R.drawable.ic_download, "下载器", onDownloads), cellLp())
        tools.addView(toolCell(R.drawable.ic_history, "历史记录", onHistory), cellLp())
        tools.addView(toolCell(R.drawable.ic_bookmark_outline, "书签", onBookmarks), cellLp())
        repeat(COLS - 3) { tools.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }  // 占位高度固定 1px，否则 WRAP 会吃掉剩余高度，把常用网站挤到屏幕底部
        content.addView(tools, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(64) })

        // 常用网站
        val head1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head1.addView(View(context), LinearLayout.LayoutParams(0, dp(1), 1f))
        doneBtn = TextView(context).apply {
            text = "完成"; textSize = 14f; setTextColor(0xFFD9480F.toInt())
            setPadding(dp(12), dp(6), dp(8), dp(6)); visibility = GONE
            setOnClickListener { exitEdit() }
        }
        head1.addView(doneBtn)
        content.addView(head1, LinearLayout.LayoutParams(MATCH, dp(34)).apply { topMargin = dp(10) })
        content.addView(commonGrid, LinearLayout.LayoutParams(MATCH, WRAP))
        // 点空白处退出编辑
        content.setOnClickListener { exitEdit() }
        addView(content)
    }

    private fun sectionTitle(t: String) = TextView(context).apply {
        text = t
        textSize = 16f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(0xFF1F1D1A.toInt())
        setPadding(dp(6), dp(6), 0, dp(4))
    }

    /** 退出常用网站编辑状态；返回 true 表示确实退出了（返回键用） */
    fun exitEdit(): Boolean {
        if (!editing) return false
        editing = false
        refreshCommon()
        return true
    }

    private fun enterEdit() {
        if (editing) return
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        editing = true
        refreshCommon()
    }

    private fun refreshCommon() {
        doneBtn.visibility = if (editing) VISIBLE else GONE
        commonGrid.removeAllViews()
        val cells = common.all().map { (t, u) -> commonCell(t, u) }.toMutableList()
        if (editing) cells += capsule("添加网站") { it.setImageResource(R.drawable.ic_add); it.setColorFilter(textSub) }.also { cell ->
            val pill = cell.getChildAt(0)
            pill.setOnClickListener { editDialog(null, null, common) }
            Motion.press(pill, 0.93f)
        }
        // 一行三个胶囊（用普通横排 + 等分权重，避免 GridLayout 把宽度算成 0）
        cells.chunked(3).forEach { rowCells ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
            rowCells.forEach { row.addView(it, LinearLayout.LayoutParams(0, WRAP, 1f)) }
            repeat(3 - rowCells.size) { row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
            commonGrid.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    /** 常用网站：点击打开；长按进入编辑，图标右上角出现关闭按钮 */
    /** 常用网站：圆角胶囊（图标 + 名称）。点击打开；长按进入编辑，胶囊右上角出现关闭按钮 */
    private fun commonCell(title: String, url: String): View = capsule(title) { img -> icons.load(url, title, img) }.also { cell ->
        val pill = cell.getChildAt(0)
        if (editing) {
            val x = TextView(context).apply {
                text = "×"
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, dp(2))
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFE5484D.toInt()) }
                elevation = dp(6).toFloat()
                setOnClickListener {
                    Motion.fadeOut(cell, 160, 0.6f) { common.remove(url); refreshCommon() }
                }
            }
            cell.addView(x, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.END))
            x.scaleX = 0f; x.scaleY = 0f
            x.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(Motion.EASE).start()
            // 编辑中胶囊轻轻晃动
            android.animation.ObjectAnimator.ofFloat(pill, View.ROTATION, -1.6f, 1.6f).apply {
                duration = 130 + (title.hashCode() and 31).toLong()
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
                pill.addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {}
                    override fun onViewDetachedFromWindow(v: View) { cancel() }
                })
            }
        }
        pill.setOnClickListener { if (editing) exitEdit() else onOpen(url) }
        pill.setOnLongClickListener { enterEdit(); true }
        Motion.press(pill, 0.93f)
    }

    /** 胶囊外壳：外层留出关闭按钮的位置，内层是玻璃胶囊 */
    private fun capsule(text: String, icon: (ImageView) -> Unit): FrameLayout {
        val cell = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(12), 0)
            // 透明玻璃胶囊：半透明白 + 亮边
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22000000),
                Motion.glass(dp(22).toFloat(), 0x73, dp(1)),
                null)
        }
        val img = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        icon(img)
        pill.addView(img, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(7) })
        pill.addView(TextView(context).apply {
            this.text = text
            textSize = 14f
            setTextColor(0xFF1F1D1A.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        cell.addView(pill, FrameLayout.LayoutParams(MATCH, dp(44)).apply { setMargins(dp(4), dp(7), dp(4), dp(7)) })
        return cell
    }

    private fun cellLp() = LinearLayout.LayoutParams(0, WRAP, 1f)

    private fun toolCell(icon: Int, label: String, action: () -> Unit): View {
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
            background = selectable()
            setOnClickListener { action() }
        }
        Motion.press(cell)
        cell.addView(ImageView(context).apply {
            setImageResource(icon)
            setColorFilter(0xFF1F1D1A.toInt())
        }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { bottomMargin = dp(10) })
        cell.addView(label(label))
        return cell
    }

    private fun label(t: String) = TextView(context).apply {
        text = t
        textSize = 14f
        setTextColor(0xFF1F1D1A.toInt())
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    /** 每次回到主页时调用 */
    @Suppress("UNUSED_PARAMETER")
    fun refresh(last: LastPage?, animate: Boolean = true) {
        editing = false
        refreshCommon()
        if (!animate) return
        // 回到主页时：标志、工具、网站图标依次浮现
        val content = getChildAt(0) as? ViewGroup ?: return
        var d = 0L
        for (i in 1 until content.childCount) {
            val v = content.getChildAt(i)
            if (v === commonGrid) {
                for (j in 0 until commonGrid.childCount) { Motion.rise(commonGrid.getChildAt(j), dp(16).toFloat(), d, 260); d += 25 }
            } else { Motion.rise(v, dp(10).toFloat(), d, 260); d += 25 }
        }
    }

    private fun refreshShortcuts() {
        if (grid.parent == null) return
        grid.removeAllViews()
        val all = shortcuts.all()
        val limit = COLS * 2 - 1           // 收起时最多两行，留一格给"更多/添加"
        if (!expanded && all.size > limit) {
            all.take(limit - 1).forEach { (t, u) -> grid.addView(siteCell(t, u)) }
            grid.addView(actionCell("更多", R.drawable.ic_expand) { expanded = true; refreshShortcuts() })
            grid.addView(actionCell("添加", R.drawable.ic_add) { editDialog(null, null) })
        } else {
            all.forEach { (t, u) -> grid.addView(siteCell(t, u)) }
            grid.addView(actionCell("添加", R.drawable.ic_add) { editDialog(null, null) })
            if (expanded) grid.addView(actionCell("收起", R.drawable.ic_expand, flip = true) { expanded = false; refreshShortcuts() })
        }
    }

    private fun gridLp() = GridLayout.LayoutParams(
        GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)
    ).apply { width = 0 }

    private fun circle(): FrameLayout = FrameLayout(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x73FFFFFF); setStroke(dp(1), 0xE6FFFFFF.toInt()) }
    }

    private fun siteCell(title: String, url: String): View {
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), dp(10), dp(2), dp(10))
            background = selectable()
        }
        val c = circle()
        val img = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        icons.load(url, title, img)
        c.addView(img, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        cell.addView(c, LinearLayout.LayoutParams(dp(54), dp(54)).apply { bottomMargin = dp(8) })
        cell.addView(label(title), LinearLayout.LayoutParams(MATCH, WRAP))
        cell.setOnClickListener { onOpen(url) }
        cell.setOnLongClickListener {
            showPopup(it, listOf(
                "打开" to { onOpen(url) },
                "编辑" to { editDialog(title, url) },
                "移除" to { shortcuts.remove(url); refreshShortcuts() }
            ))
            true
        }
        cell.layoutParams = gridLp()
        return cell
    }

    private fun actionCell(text: String, icon: Int, flip: Boolean = false, action: () -> Unit): View {
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), dp(10), dp(2), dp(10))
            background = selectable()
            setOnClickListener { action() }
        }
        Motion.press(cell)
        val c = circle()
        c.addView(ImageView(context).apply {
            setImageResource(icon)
            setColorFilter(textSub)
            if (flip) rotation = 180f
        }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        cell.addView(c, LinearLayout.LayoutParams(dp(54), dp(54)).apply { bottomMargin = dp(8) })
        cell.addView(label(text), LinearLayout.LayoutParams(MATCH, WRAP))
        cell.layoutParams = gridLp()
        return cell
    }

    /** 添加 / 编辑常用网站 */
    private fun editDialog(oldTitle: String?, oldUrl: String?, store: ShortcutStore = shortcuts) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        val name = EditText(context).apply { hint = "名称"; setText(oldTitle ?: ""); isSingleLine = true }
        val addr = EditText(context).apply {
            hint = "网址，例如 bilibili.com"
            setText(oldUrl ?: "")
            isSingleLine = true
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        box.addView(name)
        box.addView(addr)
        AlertDialog.Builder(context)
            .setTitle((if (oldUrl == null) "添加" else "编辑") + (if (store === common) "常用网站" else "自定义网站"))
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                var u = addr.text.toString().trim()
                if (u.isEmpty()) return@setPositiveButton
                if (!u.contains("://")) u = "https://$u"
                val t = name.text.toString().trim().ifEmpty {
                    android.net.Uri.parse(u).host?.removePrefix("www.")?.removePrefix("m.") ?: u
                }
                if (oldUrl == null) store.add(t, u) else store.update(oldUrl, t, u)
                if (store === common) refreshCommon() else refreshShortcuts()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 长按弹出的白色圆角小卡片 */
    private fun showPopup(anchor: View, items: List<Pair<String, () -> Unit>>) {
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(18).toFloat() }
            setPadding(0, dp(8), 0, dp(8))
        }
        val w = dp(160)
        val popup = android.widget.PopupWindow(card, w, WRAP, true).apply {
            elevation = dp(10).toFloat()
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            animationStyle = R.style.PopupAnim
        }
        items.forEach { (label, action) ->
            card.addView(TextView(context).apply {
                text = label
                textSize = 16f
                setTextColor(textMain)
                setPadding(dp(22), dp(14), dp(22), dp(14))
                background = selectable()
                setOnClickListener { popup.dismiss(); action() }
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        card.measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        popup.showAsDropDown(anchor, dp(8), -(anchor.height + card.measuredHeight - dp(14)))
    }

    private fun selectable() = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless)).let {
        val d = it.getDrawable(0); it.recycle(); d
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val COLS = 5
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
