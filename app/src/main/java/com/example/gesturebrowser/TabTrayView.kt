package com.example.gesturebrowser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 标签页总览（仿 Firefox）：顶部标签数，两列卡片网格（图标 + 标题 + ✕，下面是网页缩略图），
 * 当前标签页紫色描边；左下角浮动 搜索/⋮，右下角浮动 + 新建。
 */
class TabTrayView(context: Context, private val icons: IconLoader, private val cb: Callbacks) : FrameLayout(context) {

    interface Callbacks {
        fun onSelect(index: Int)
        fun onClose(index: Int)
        fun onNewTab()
        fun onSearch()
        fun onCloseAll()
    }

    class Item(val title: String, val url: String?, val isHome: Boolean, val favicon: Bitmap?, val thumb: Bitmap?)

    private val accent = context.getColor(R.color.accent)
    private val textMain = context.getColor(R.color.text_main)
    private val textSub = context.getColor(R.color.text_sub)
    private val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context)
    private val countText = TextView(context)

    init {
        setBackgroundColor(if (Motion.canBlur) 0x99F6F5F3.toInt() else 0xFFF6F5F3.toInt())
        isClickable = true // 挡住下层点击
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // 顶部：标签数图标 + 紫色下划线
        val header = FrameLayout(context).apply { setBackgroundColor(if (Motion.canBlur) 0x66E8E5E1 else 0xFFE8E5E1.toInt()) }
        countText.apply {
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(textMain)
            paint.isFakeBoldText = true
            setBackgroundResource(R.drawable.bg_tab_count)
        }
        header.addView(countText, LayoutParams(dp(34), dp(28), Gravity.CENTER))
        header.addView(View(context).apply {
            background = GradientDrawable().apply { setColor(textMain); cornerRadius = dp(2).toFloat() }
        }, LayoutParams(dp(28), dp(3), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        col.addView(header, LinearLayout.LayoutParams(MATCH, dp(64)))

        grid.setPadding(dp(14), dp(18), dp(14), dp(120))
        scroll.clipToPadding = false
        scroll.addView(grid)
        col.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        addView(col, LayoutParams(MATCH, MATCH))

        // 左下角浮动胶囊：搜索 + ⋮
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Motion.glass(dp(32).toFloat(), 0xA6, dp(1))
            elevation = dp(6).toFloat()
            setPadding(dp(8), 0, dp(8), 0)
        }
        pill.addView(iconButton(R.drawable.ic_search) { cb.onSearch() }, LinearLayout.LayoutParams(dp(56), dp(56)))
        val more = iconButton(R.drawable.ic_more) {}
        more.setOnClickListener {
            PopupMenu(context, it).apply {
                menu.add("新建标签页")
                menu.add("关闭全部标签页")
                setOnMenuItemClickListener { mi ->
                    if (mi.title == "新建标签页") cb.onNewTab() else cb.onCloseAll()
                    true
                }
            }.show()
        }
        pill.addView(more, LinearLayout.LayoutParams(dp(56), dp(56)))
        addView(pill, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(64), Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(16), 0, 0, dp(16))
        })

        // 右下角 + 新建
        val fab = FrameLayout(context).apply {
            background = GradientDrawable().apply { setColor(0xFFFFD9C2.toInt()); cornerRadius = dp(20).toFloat() }
            elevation = dp(6).toFloat()
            isClickable = true
            setOnClickListener { cb.onNewTab() }
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_add) }, LayoutParams(dp(30), dp(30), Gravity.CENTER))
        }
        addView(fab, LayoutParams(dp(64), dp(64), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(16), dp(16))
        })

        // 避开状态栏和导航栏
        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
    }

    fun show(items: List<Item>, current: Int) {
        countText.text = if (items.size > 99) ":D" else items.size.toString()
        grid.removeAllViews()
        val screenW = resources.displayMetrics.widthPixels
        val cardW = (screenW - dp(14) * 2 - dp(12)) / 2
        val thumbH = cardW - dp(12)
        var row: LinearLayout? = null
        items.forEachIndexed { i, item ->
            if (i % 2 == 0) {
                row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(row, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(14)
                })
            }
            val cv = card(i, item, i == current, thumbH)
            Motion.press(cv, 0.95f)
            Motion.rise(cv, dp(24).toFloat(), (i * 30L).coerceAtMost(240), 360)
            row!!.addView(cv, LinearLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (i % 2 == 1) marginStart = dp(12)
            })
        }
        // 滚到当前标签页
        val targetRow = current / 2
        scroll.post { grid.getChildAt(targetRow)?.let { scroll.scrollTo(0, (it.top - dp(18)).coerceAtLeast(0)) } }
    }

    private fun card(index: Int, item: Item, selected: Boolean, thumbH: Int): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                cornerRadius = dp(22).toFloat()
                if (selected) setStroke(dp(3), accent)
            }
            elevation = dp(1).toFloat()
            setPadding(dp(6), dp(4), dp(6), dp(6))
            isClickable = true
            setOnClickListener { cb.onSelect(index) }
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, 0, 0)
        }
        val fav = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        when {
            item.favicon != null -> fav.setImageBitmap(item.favicon)
            item.isHome || item.url == null -> fav.setImageResource(android.R.drawable.ic_menu_compass)
            else -> icons.load(item.url, item.title, fav)
        }
        head.addView(fav, LinearLayout.LayoutParams(dp(20), dp(20)))
        head.addView(TextView(context).apply {
            text = item.title
            textSize = 16f
            setTextColor(textMain)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), 0, dp(4), 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(iconButton(R.drawable.ic_close) { cb.onClose(index) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        card.addView(head)

        val thumbBox = FrameLayout(context).apply {
            background = GradientDrawable().apply { setColor(0xFFF6F5F3.toInt()); cornerRadius = dp(16).toFloat() }
            clipToOutline = true
        }
        if (item.thumb != null) {
            thumbBox.addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(item.thumb)
            }, LayoutParams(MATCH, MATCH))
        } else {
            thumbBox.addView(TextView(context).apply {
                text = if (item.isHome) "主页" else (item.url?.let { Uri.parse(it).host } ?: "新标签页")
                textSize = 14f
                setTextColor(textSub)
                gravity = Gravity.CENTER
            }, LayoutParams(MATCH, MATCH))
        }
        card.addView(thumbBox, LinearLayout.LayoutParams(MATCH, thumbH))
        return card
    }

    private fun iconButton(res: Int, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(res)
        scaleType = ImageView.ScaleType.CENTER
        val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        background = ta.getDrawable(0)
        ta.recycle()
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object { private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT }
}
