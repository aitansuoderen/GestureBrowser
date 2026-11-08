package com.example.gesturebrowser

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** 通用界面小工具（统一 Firefox 风格） */
object Ui {
    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    const val SHEET_BG = 0xFFF6F5F3.toInt()

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = r }

    fun ripple(c: Context, borderless: Boolean = false): Drawable? {
        val ta = c.obtainStyledAttributes(intArrayOf(
            if (borderless) android.R.attr.selectableItemBackgroundBorderless else android.R.attr.selectableItemBackground
        ))
        val d = ta.getDrawable(0)
        ta.recycle()
        return d
    }

    /** 长按弹出的白色圆角小卡片 */
    fun popup(anchor: View, items: List<Pair<String, () -> Unit>>, atX: Int = -1) {
        val c = anchor.context
        anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = Motion.glass(dp(c, 18).toFloat(), 0xD9, dp(c, 1))
            setPadding(0, dp(c, 8), 0, dp(c, 8))
        }
        val w = dp(c, 200)
        val pw = PopupWindow(card, w, WRAP, true).apply {
            elevation = dp(c, 10).toFloat()
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            animationStyle = R.style.PopupAnim
        }
        items.forEach { (label, action) ->
            card.addView(TextView(c).apply {
                text = label
                textSize = 16f
                setTextColor(c.getColor(R.color.text_main))
                setPadding(dp(c, 22), dp(c, 14), dp(c, 22), dp(c, 14))
                background = ripple(c)
                setOnClickListener { pw.dismiss(); action() }
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        pw.showAsDropDown(anchor, if (atX >= 0) atX else dp(c, 16), -anchor.height / 2)
    }

    // ---------------- 底部菜单 ----------------

    class SheetItem(val icon: Int, val label: String, val enabled: Boolean = true, val action: () -> Unit)

    /** 顶部当前网页卡片 */
    class SheetHeader(val title: String, val url: String)

    /**
     * 底部弹出菜单（仿 Firefox）：拖动条、当前网页、导航图标、四宫格（历史/书签/下载/密码）、操作列表。
     */
    fun bottomSheet(
        activity: Activity,
        header: SheetHeader?,
        icons: IconLoader,
        navRow: List<SheetItem>,
        tiles: List<SheetItem>,
        rows: List<SheetItem>
    ) {
        val c = activity
        val d = Dialog(c)
        val accent = c.getColor(R.color.accent)
        val textMain = c.getColor(R.color.text_main)
        val textSub = c.getColor(R.color.text_sub)
        val disabled = 0xFFC8C4BE.toInt()
        fun tint(color: Int) = android.content.res.ColorStateList.valueOf(color)
        fun card() = LinearLayout(c).apply {
            background = rounded(Color.WHITE, dp(c, 24).toFloat())
            clipToOutline = true
        }
        fun gap(v: Int) = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(c, v) }

        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                // 玻璃：Android 12+ 背后整窗模糊，底色半透明
                setColor(if (Motion.canBlur) (SHEET_BG and 0x00FFFFFF) or 0xA6000000.toInt() else SHEET_BG)
                setStroke(dp(c, 1), 0xB3FFFFFF.toInt())
                val r = dp(c, 28).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        root.addView(View(c).apply { background = rounded(0xFFC8C4BE.toInt(), dp(c, 3).toFloat()) },
            LinearLayout.LayoutParams(dp(c, 40), dp(c, 5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(c, 16) })

        // ① 当前网页
        if (header != null) {
            val h = card().apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12))
            }
            val icon = ImageView(c).apply {
                background = rounded(0xFFEFEDEA.toInt(), dp(c, 12).toFloat())
                setPadding(dp(c, 8), dp(c, 8), dp(c, 8), dp(c, 8))
            }
            icons.load(header.url, header.title, icon)
            h.addView(icon, LinearLayout.LayoutParams(dp(c, 42), dp(c, 42)))
            h.addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(c, 14), 0, 0, 0)
                addView(TextView(c).apply {
                    text = header.title; textSize = 16f; setTextColor(textMain)
                    paint.isFakeBoldText = true; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
                addView(TextView(c).apply {
                    text = android.net.Uri.parse(header.url).host ?: header.url
                    textSize = 12f; setTextColor(textSub); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(c, 2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            root.addView(h, gap(10))
        }

        // ② 导航：一条白色胶囊里放 5 个圆形图标按钮
        val nav = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 6))
        }
        navRow.forEach { item ->
            val cell = FrameLayout(c)
            cell.addView(ImageView(c).apply {
                setImageResource(item.icon)
                imageTintList = tint(if (item.enabled) textMain else disabled)
                scaleType = ImageView.ScaleType.CENTER
                background = ripple(c, true)
                contentDescription = item.label
                isEnabled = item.enabled
                setOnClickListener { d.dismiss(); item.action() }
            }, FrameLayout.LayoutParams(dp(c, 48), dp(c, 48), Gravity.CENTER))
            nav.addView(cell, LinearLayout.LayoutParams(0, dp(c, 48), 1f))
        }
        root.addView(nav, gap(10))

        // ③ 四宫格：浅紫圆底图标 + 居中文字
        val grid = card().apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(c, 6), dp(c, 14), dp(c, 6), dp(c, 12))
        }
        tiles.forEach { item ->
            val t = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = rounded(Color.TRANSPARENT, dp(c, 16).toFloat())
                foreground = ripple(c)
                setPadding(0, dp(c, 4), 0, dp(c, 4))
                setOnClickListener { d.dismiss(); item.action() }
            }
            t.addView(ImageView(c).apply {
                setImageResource(item.icon)
                imageTintList = tint(accent)
                scaleType = ImageView.ScaleType.CENTER
                background = rounded(0xFFEFEDEA.toInt(), dp(c, 26).toFloat())
            }, LinearLayout.LayoutParams(dp(c, 52), dp(c, 52)))
            t.addView(TextView(c).apply {
                text = item.label
                textSize = 14f
                setTextColor(textMain)
                gravity = Gravity.CENTER
                setPadding(0, dp(c, 8), 0, 0)
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            grid.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        root.addView(grid, gap(10))

        // ④ 操作列表
        val list = card().apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(c, 6), 0, dp(c, 6)) }
        rows.filter { it.enabled }.forEachIndexed { i, item ->
            if (i > 0) list.addView(View(c).apply { setBackgroundColor(0xFFEFEDEA.toInt()) },
                LinearLayout.LayoutParams(MATCH, 1).apply { marginStart = dp(c, 62) })
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(c, 20), dp(c, 13), dp(c, 20), dp(c, 13))
                background = ripple(c)
                isEnabled = item.enabled
                setOnClickListener { d.dismiss(); item.action() }
            }
            row.addView(ImageView(c).apply {
                setImageResource(item.icon)
                imageTintList = tint(if (item.enabled) textSub else disabled)
            }, LinearLayout.LayoutParams(dp(c, 22), dp(c, 22)))
            row.addView(TextView(c).apply {
                text = item.label
                textSize = 16f
                setTextColor(if (item.enabled) textMain else disabled)
                setPadding(dp(c, 20), 0, 0, 0)
            })
            list.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        root.addView(list, LinearLayout.LayoutParams(MATCH, WRAP))

        d.setContentView(root)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(MATCH, WRAP)
            setGravity(Gravity.BOTTOM)
            setDimAmount(if (Motion.canBlur) 0.18f else 0.35f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { windowAnimations = R.style.SheetAnim }
            Motion.windowBlur(this, 40)
            decorView.setPadding(0, 0, 0, 0)
        }
        val pad = { bottom: Int -> root.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 14) + bottom) }
        pad(0)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            pad(ins.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            ins
        }
        d.show()
    }

    // ---------------- 全屏列表页（历史 / 书签 / 下载 / 密码） ----------------

    class Row(
        val header: String? = null,
        val title: String = "",
        val subtitle: String = "",
        val iconUrl: String? = null,
        val iconRes: Int = 0,
        val onClick: ((View) -> Unit)? = null,
        val onLongClick: ((View) -> Unit)? = null
    )

    class ListScreen(
        private val activity: Activity,
        title: String,
        private val icons: IconLoader,
        searchable: Boolean = false,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null
    ) {
        private val c = activity
        val dialog = Dialog(c, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        private var rows: List<Row> = emptyList()
        private var filter = ""
        private val empty = TextView(c).apply {
            textSize = 16f
            setTextColor(c.getColor(R.color.text_sub))
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        var emptyText: String
            get() = empty.text.toString()
            set(v) { empty.text = v }
        var onFilter: ((String) -> Unit)? = null
        var onDismiss: (() -> Unit)? = null

        private val adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(p: Int) = rows[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getViewTypeCount() = 2
            override fun getItemViewType(p: Int) = if (rows[p].header != null) 0 else 1
            override fun isEnabled(p: Int) = rows[p].header == null
            override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
                val r = rows[p]
                if (r.header != null) {
                    val tv = (convert as? TextView) ?: TextView(c).apply {
                        textSize = 14f
                        paint.isFakeBoldText = true
                        setTextColor(c.getColor(R.color.text_sub))
                        setPadding(dp(c, 20), dp(c, 18), dp(c, 20), dp(c, 6))
                    }
                    tv.text = r.header
                    return tv
                }
                val v = (convert as? LinearLayout) ?: itemView()
                val icon = v.getChildAt(0) as ImageView
                val texts = v.getChildAt(1) as LinearLayout
                (texts.getChildAt(0) as TextView).text = r.title
                val sub = texts.getChildAt(1) as TextView
                sub.text = r.subtitle
                sub.visibility = if (r.subtitle.isEmpty()) View.GONE else View.VISIBLE
                icon.tag = null
                when {
                    r.iconUrl != null -> icons.load(r.iconUrl, r.title, icon)
                    r.iconRes != 0 -> icon.setImageResource(r.iconRes)
                    else -> icon.setImageDrawable(null)
                }
                v.setOnClickListener { r.onClick?.invoke(it) }
                v.setOnLongClickListener { r.onLongClick?.invoke(it); r.onLongClick != null }
                return v
            }
        }

        private fun itemView() = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(c, 20), dp(c, 12), dp(c, 16), dp(c, 12))
            background = ripple(c)
            addView(ImageView(c).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = rounded(Color.WHITE, dp(c, 10).toFloat())
                setPadding(dp(c, 8), dp(c, 8), dp(c, 8), dp(c, 8))
                elevation = dp(c, 1).toFloat()
            }, LinearLayout.LayoutParams(dp(c, 40), dp(c, 40)))
            val texts = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(c, 16), 0, 0, 0)
                addView(TextView(c).apply {
                    textSize = 16f
                    setTextColor(c.getColor(R.color.text_main))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(TextView(c).apply {
                    textSize = 14f
                    setTextColor(c.getColor(R.color.text_sub))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(c, 2), 0, 0)
                })
            }
            addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            layoutParams = android.widget.AbsListView.LayoutParams(MATCH, WRAP)
        }

        init {
            val root = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(SHEET_BG)
            }
            val bar = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(c, 4), 0, dp(c, 8), 0)
            }
            bar.addView(ImageView(c).apply {
                setImageResource(R.drawable.ic_back)
                scaleType = ImageView.ScaleType.CENTER
                background = ripple(c, true)
                setOnClickListener { dialog.dismiss() }
            }, LinearLayout.LayoutParams(dp(c, 52), dp(c, 52)))
            bar.addView(TextView(c).apply {
                text = title
                textSize = 20f
                setTextColor(c.getColor(R.color.text_main))
                setPadding(dp(c, 8), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (actionLabel != null) bar.addView(TextView(c).apply {
                text = actionLabel
                textSize = 16f
                setTextColor(c.getColor(R.color.accent))
                setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10))
                background = ripple(c)
                setOnClickListener { onAction?.invoke() }
            })
            root.addView(bar, LinearLayout.LayoutParams(MATCH, dp(c, 60)))

            if (searchable) {
                root.addView(EditText(c).apply {
                    hint = "搜索"
                    textSize = 16f
                    isSingleLine = true
                    background = rounded(0xFFE8E5E1.toInt(), dp(c, 24).toFloat())
                    setPadding(dp(c, 20), 0, dp(c, 20), 0)
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                        override fun afterTextChanged(s: Editable?) {
                            filter = s.toString().trim()
                            onFilter?.invoke(filter)
                        }
                    })
                }, LinearLayout.LayoutParams(MATCH, dp(c, 46)).apply { setMargins(dp(c, 16), 0, dp(c, 16), dp(c, 6)) })
            }

            val frame = FrameLayout(c)
            frame.addView(ListView(c).apply {
                adapter = this@ListScreen.adapter
                divider = null
                selector = ColorDrawable(Color.TRANSPARENT)
                clipToPadding = false
                setPadding(0, 0, 0, dp(c, 24))
            }, FrameLayout.LayoutParams(MATCH, MATCH))
            frame.addView(empty, FrameLayout.LayoutParams(MATCH, MATCH))
            root.addView(frame, LinearLayout.LayoutParams(MATCH, 0, 1f))

            dialog.setContentView(root)
            dialog.window?.apply {
                setLayout(MATCH, MATCH)
                statusBarColor = SHEET_BG
                navigationBarColor = SHEET_BG
                setWindowAnimations(android.R.style.Animation_Activity)
            }
            ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
                val b = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
                v.setPadding(0, b.top, 0, b.bottom)
                ins
            }
            dialog.setOnDismissListener { onDismiss?.invoke() }
        }

        val query get() = filter

        fun setRows(list: List<Row>) {
            rows = list
            adapter.notifyDataSetChanged()
            empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }

        fun show() = dialog.show()
        fun dismiss() = dialog.dismiss()
    }
}
