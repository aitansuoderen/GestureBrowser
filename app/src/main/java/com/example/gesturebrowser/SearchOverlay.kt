package com.example.gesturebrowser

import android.graphics.Typeface
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject

/** 搜索历史：用户在搜索栏里输入过的内容（关键词或网址），最新在前，最多 100 条 */
class SearchHistoryStore(context: Context) {
    data class Item(val text: String, val isUrl: Boolean)
    private val prefs = context.getSharedPreferences("search_history", Context.MODE_PRIVATE)

    fun all(): List<Item> {
        val arr = JSONArray(prefs.getString("list", "[]"))
        return (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Item(o.getString("t"), o.optBoolean("u")) } }
    }

    fun add(text: String, isUrl: Boolean) {
        val t = text.trim()
        if (t.isEmpty()) return
        save((listOf(Item(t, isUrl)) + all().filterNot { it.text == t }).take(100))
    }

    fun remove(text: String) = save(all().filterNot { it.text == text })
    fun clear() = save(emptyList())

    private fun save(list: List<Item>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("t", it.text).put("u", it.isUrl)) }
        prefs.edit().putString("list", arr.toString()).apply()
    }
}

/**
 * 点开搜索栏后的全屏搜索页（仿 UC）：
 * 顶部输入框 + 下方搜索历史（输入时同时匹配浏览历史）；
 * 输入法上方一条深色快捷栏：www.  /  .  .com  .cn  .net  .org  以及光标左移、右移。
 */
@SuppressLint("ViewConstructor")
class SearchOverlay(
    context: Context,
    private val searchHistory: SearchHistoryStore,
    private val history: HistoryStore,
    private val engineIconRes: () -> Int,
    private val onEngineClick: (View) -> Unit,
    private val onSubmit: (String) -> Unit
) : LinearLayout(context) {

    private val textMain = context.getColor(R.color.text_main)
    private val textSub = 0xFF8F8A83.toInt()
    private val engineIcon = ImageView(context)
    val input = EditText(context)
    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private val quickBar = LinearLayout(context)

    init {
        orientation = VERTICAL
        // 玻璃：Android 12+ 背后网页被模糊，这里只铺一层半透明底
        setBackgroundColor(if (Motion.canBlur) 0xA6F6F5F3.toInt() else 0xFFF6F5F3.toInt())
        isClickable = true
        visibility = GONE

        // 顶部输入框
        val pill = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(0xFFEFEDEA.toInt()); cornerRadius = dp(26).toFloat() }
            setPadding(dp(10), 0, dp(4), 0)
        }
        val engineBtn = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { onEngineClick(it) }
        }
        engineBtn.addView(ImageView(context).apply { setImageResource(R.drawable.ic_expand); alpha = 0.45f },
            LayoutParams(dp(14), dp(14)))
        engineBtn.addView(engineIcon, LayoutParams(dp(26), dp(26)).apply { leftMargin = dp(4) })
        pill.addView(engineBtn, LayoutParams(WRAP, dp(44)))
        input.apply {
            background = null
            hint = "搜索或输入网址"
            setHintTextColor(0xFF8F8A83.toInt())
            setTextColor(textMain)
            textSize = 16f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI or android.text.InputType.TYPE_CLASS_TEXT
            setSelectAllOnFocus(true)
            setPadding(dp(10), 0, dp(6), 0)
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_GO || id == EditorInfo.IME_ACTION_DONE ||
                    ev?.keyCode == KeyEvent.KEYCODE_ENTER) { submit(text.toString()); true } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { renderList() }
            })
        }
        pill.addView(input, LayoutParams(0, dp(52), 1f))
        pill.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { if (input.text.isNotEmpty()) input.setText("") else close() }
        }, LayoutParams(dp(44), dp(44)))
        addView(pill, LayoutParams(MATCH, dp(52)).apply { setMargins(dp(14), dp(8), dp(14), dp(6)) })

        // 历史列表
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(list)
        addView(scroll, LayoutParams(MATCH, 0, 1f))

        // 输入法上方的快捷栏
        quickBar.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF4A4A4C.toInt())
        }
        val chips = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listOf("www.", "/", ".", ".com", ".cn", ".net", ".org", "https://").forEachIndexed { i, s ->
            if (i > 0) chips.addView(divider())
            chips.addView(quickKey(s) { insert(s) })
        }
        val hs = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chips) }
        quickBar.addView(hs, LayoutParams(0, MATCH, 1f))
        quickBar.addView(quickKey("←") { moveCursor(-1) })
        quickBar.addView(divider())
        quickBar.addView(quickKey("→") { moveCursor(1) })
        quickBar.visibility = GONE
        addView(quickBar, LayoutParams(MATCH, dp(46)))

        ViewCompat.setOnApplyWindowInsetsListener(this) { v, ins ->
            val bars = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = ins.getInsets(WindowInsetsCompat.Type.ime())
            val imeOn = ins.isVisible(WindowInsetsCompat.Type.ime())
            quickBar.visibility = if (imeOn) VISIBLE else GONE
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            ins
        }
    }

    private fun divider() = View(context).apply { setBackgroundColor(0x55FFFFFF) }.also {
        it.layoutParams = LayoutParams(dp(1), dp(20))
    }

    private fun quickKey(label: String, action: () -> Unit) = TextView(context).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 16f
        typeface = android.graphics.Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setPadding(dp(14), 0, dp(14), 0)
        minWidth = dp(44)
        setOnClickListener { action() }
        layoutParams = LayoutParams(WRAP, MATCH)
    }

    private fun insert(s: String) {
        val a = input.selectionStart.coerceAtLeast(0)
        val b = input.selectionEnd.coerceAtLeast(0)
        input.text.replace(minOf(a, b), maxOf(a, b), s)
    }

    private fun moveCursor(d: Int) {
        val p = (input.selectionEnd + d).coerceIn(0, input.text.length)
        input.setSelection(p)
    }

    var onShowChange: ((Boolean) -> Unit)? = null

    fun open(current: String) {
        engineIcon.setImageResource(engineIconRes())
        visibility = VISIBLE
        alpha = 0f
        animate().cancel()
        animate().alpha(1f).setDuration(220).setInterpolator(Motion.EASE).start()
        (getChildAt(0))?.let { Motion.rise(it, resources.displayMetrics.density * 28) }
        onShowChange?.invoke(true)
        input.setText(current)
        input.requestFocus()
        input.selectAll()
        renderList()
        post {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        ViewCompat.requestApplyInsets(this)
    }

    fun refreshEngine() = engineIcon.setImageResource(engineIconRes())

    fun close() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        if (visibility != VISIBLE) return
        onShowChange?.invoke(false)
        Motion.fadeOut(this, 170, 1f, resources.displayMetrics.density * 12) { visibility = GONE }
    }

    private fun submit(text: String) {
        val t = text.trim()
        close()
        if (t.isEmpty()) return
        val isUrl = !t.contains(' ') && (t.contains("://") || t.contains('.'))
        searchHistory.add(t, isUrl)
        onSubmit(t)
    }

    private fun renderList() {
        list.removeAllViews()
        val q = input.text.toString().trim()
        // 输入框里还是原网址（全选状态）时，按空查询显示全部搜索历史
        val typing = q.isNotEmpty() && !(input.hasSelection() && input.selectionStart == 0 && input.selectionEnd == input.text.length)
        val searches = searchHistory.all().filter { !typing || it.text.contains(q, true) }.take(if (typing) 6 else 30)
        searches.forEach { item -> list.addView(row(item.text, if (item.isUrl) item.text.substringAfter("://") else null, item.isUrl,
            onDelete = { searchHistory.remove(item.text); renderList() })) }
        if (typing) {
            history.all().asSequence()
                .filter { it.url.contains(q, true) || it.title.contains(q, true) }
                .filter { h -> searches.none { it.text == h.url } }
                .take(8).toList()
                .forEach { h -> list.addView(row(h.title, h.url, true, fill = h.url, onDelete = null)) }
        }
        if (!typing && searches.isNotEmpty()) {
            list.addView(TextView(context).apply {
                text = "清除搜索历史"
                textSize = 14f
                setTextColor(textSub)
                gravity = Gravity.CENTER
                setPadding(0, dp(18), 0, dp(18))
                setOnClickListener {
                    android.app.AlertDialog.Builder(context).setMessage("清除全部搜索历史？")
                        .setPositiveButton("清除") { _, _ -> searchHistory.clear(); renderList() }
                        .setNegativeButton("取消", null).show()
                }
            }, LayoutParams(MATCH, WRAP))
        }
    }

    private fun row(title: String, sub: String?, isUrl: Boolean, fill: String = title, onDelete: (() -> Unit)?): View {
        val r = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(6), dp(10))
            minimumHeight = dp(60)
            val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            background = ta.getDrawable(0); ta.recycle()
            setOnClickListener { submit(fill) }
            if (onDelete != null) setOnLongClickListener {
                android.app.AlertDialog.Builder(context).setMessage("删除这条记录？")
                    .setPositiveButton("删除") { _, _ -> onDelete() }
                    .setNegativeButton("取消", null).show()
                true
            }
        }
        r.addView(ImageView(context).apply {
            setImageResource(if (isUrl) R.drawable.ic_globe else R.drawable.ic_search)
            setColorFilter(0xFF5E5A54.toInt())
        }, LayoutParams(dp(22), dp(22)))
        val texts = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(16), 0, dp(8), 0) }
        texts.addView(TextView(context).apply {
            text = title; textSize = 16f; setTextColor(textMain); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        if (sub != null && sub != title) texts.addView(TextView(context).apply {
            text = sub; textSize = 14f; setTextColor(textSub); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        r.addView(texts, LayoutParams(0, WRAP, 1f))
        // 左上箭头：把这条填进输入框继续编辑
        r.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_fill_arrow)
            setColorFilter(0xFF5E5A54.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { input.setText(fill); input.setSelection(input.text.length) }
        }, LayoutParams(dp(48), dp(48)))
        return r
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
