package com.example.gesturebrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 盖在全屏视频上方的手势层：
 *  - 左半屏上下滑：亮度；右半屏上下滑：音量
 *  - 左右滑：快进/快退（中间显示目标时间）
 *  - 双击左侧：后退 10 秒；双击右侧：快进 10 秒；双击中间：播放/暂停
 *  - 长按：2 倍速，松手恢复
 *  - 单击：显示/隐藏控制栏（仿 UC：返回+标题、锁定、下载、进度条、倍速、画面、静音、旋转）
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class VideoGestureOverlay(
    private val activity: Activity,
    private val webView: WebView,
    @Suppress("unused") private val target: View,
    private val title: String,
    private val cb: Callbacks
) : FrameLayout(activity) {

    interface Callbacks {
        fun onExit()
        fun onDownload(info: org.json.JSONObject): Boolean
    }

    private enum class Mode { NONE, SEEK, BRIGHTNESS, VOLUME, SPEED }

    private val helperJs = """
        (function(){
          if (window.__gb3) return;
          function pick(){
            var fs = document.fullscreenElement || document.webkitFullscreenElement;
            if (fs && fs.tagName === 'VIDEO') return fs;
            var list = fs ? fs.querySelectorAll('video') : [];
            if (!list.length) list = document.querySelectorAll('video');
            var best = null, area = 0;
            for (var i = 0; i < list.length; i++) {
              var v = list[i];
              if (v.__gbpv) continue;
              if (!v.paused) return v;
              var r = v.getBoundingClientRect(), a = r.width * r.height;
              if (a > area) { area = a; best = v; }
            }
            return best;
          }
          window.__gb3 = {
            info: function(){
              var v = pick(); if (!v) return null;
              return { t: v.currentTime, d: isFinite(v.duration) ? v.duration : 0, p: v.paused, r: v.playbackRate, m: v.muted, l: v.loop };
            },
            toggle: function(){ var v = pick(); if (!v) return null;
              if (v.paused) v.play(); else v.pause(); return v.paused; },
            seek: function(t){ var v = pick(); if (v) v.currentTime = t; },
            skip: function(s){ var v = pick(); if (!v) return null;
              v.currentTime = Math.max(0, Math.min((v.duration || 1e9) - 0.5, v.currentTime + s));
              return [v.currentTime, isFinite(v.duration) ? v.duration : 0]; },
            rate: function(r){ var v = pick(); if (v) v.playbackRate = r; },
            mute: function(){ var v = pick(); if (!v) return null; v.muted = !v.muted; return v.muted; },
            loop: function(){ var v = pick(); if (!v) return null; v.loop = !v.loop; return v.loop; },
            fit: function(f){ var v = pick(); if (v) v.style.setProperty('object-fit', f, 'important'); },
            src: function(){
              var v = pick(), o = { src: v ? (v.currentSrc || v.src || '') : '', page: location.href, title: document.title, hls: [], dash: null };
              try { var es = performance.getEntriesByType('resource');
                for (var i = 0; i < es.length; i++) { var n = es[i].name; if (/\.m3u8(\?|#|$)/i.test(n) || /[?&](type|format)=m3u8/i.test(n)) { if (o.hls.indexOf(n) < 0) o.hls.push(n); } } } catch (e) {}
              try { var pi = window.__playinfo__, d = pi && (pi.data || pi.result); d = d && (d.dash || (d.video_info && d.video_info.dash));
                if (d && d.video && d.video.length) {
                  var pref = function (x) { return x.codecid === 7 ? 3 : x.codecid === 12 ? 2 : 1; };
                  var bv = d.video.slice().sort(function (a, b) { return (b.id - a.id) || (pref(b) - pref(a)) || (b.bandwidth - a.bandwidth); })[0];
                  var au = (d.audio || []).slice().sort(function (a, b) { return b.bandwidth - a.bandwidth; })[0];
                  o.dash = { v: bv.baseUrl || bv.base_url, a: au ? (au.baseUrl || au.base_url) : '' };
                } } catch (e) {}
              return o; },
            thumb: function(t){ var v = pick(); if (!v || !window.__gbt) return null; return window.__gbt.get(v, t); }
          };
        })();
    """.trimIndent()

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var startBrightness = 0.5f
    private var startVolume = 0

    private var infoReady = false
    private var duration = 0.0
    private var startTime = 0.0
    private var targetTime = 0.0
    private var savedRate = 1.0

    private val hint = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 18f
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(10), dp(20), dp(10))
        background = GradientDrawable().apply { setColor(0xAA000000.toInt()); cornerRadius = dp(12).toFloat() }
        visibility = GONE
    }
    private val hideHint = Runnable { hint.visibility = GONE }

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (controls.isShown || lockBtn.isShown) hideControls() else showControls()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (locked) return true
            when {
                e.x < width / 3f -> skip(-10)
                e.x > width * 2 / 3f -> skip(10)
                else -> js("window.__gb3.toggle()") { r ->
                    when (r) {
                        "true" -> flash("⏸ 已暂停")
                        "false" -> flash("▶ 播放")
                    }
                }
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (mode != Mode.NONE || locked) return
            mode = Mode.SPEED
            js("window.__gb3.rate(2)")
            showHint("⏩ 2 倍速播放中")
        }
    })

    // ---------- 控制层（仿 UC）----------
    private val white = Color.WHITE
    private val controls = FrameLayout(context)
    private val lockBtn = iconBtn(R.drawable.ic_v_unlock) { toggleLock() }
    private val playBtn = iconBtn(R.drawable.ic_v_play) { js("window.__gb3.toggle()") { poll() } }
    private val muteBtn = iconBtn(R.drawable.ic_v_vol) { js("window.__gb3.mute()") { poll() } }
    private val curText = label("00:00:00", 12f)
    private val durText = label("00:00:00", 12f)
    private val speedText = label("1.0x", 15f).apply {
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { showSpeedMenu(this) }
    }
    private val clock = label("", 12f)
    private val seek = android.widget.SeekBar(context).apply {
        max = 1000
        progressTintList = android.content.res.ColorStateList.valueOf(white)
        thumbTintList = android.content.res.ColorStateList.valueOf(white)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x66FFFFFF)
        setPadding(dp(8), 0, dp(8), 0)
    }
    // 拖动时进度条上方的画面预览
    private val pvImg = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = dp(8).toFloat(); setStroke(dp(2), 0xE6FFFFFF.toInt()) }
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(8).toFloat()) }
        }
    }
    private val pvTime = label("", 13f).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }
    private val pvBox = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = GONE
        addView(pvImg, LinearLayout.LayoutParams(dp(208), dp(117)))
        addView(pvTime, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
    }
    private var pvT = 0.0
    private var pvBusy = false
    private var pvLastImg: String? = null
    private var pvShownT = -1.0
    private val pvRefresh = object : Runnable {
        override fun run() { if (pvBox.visibility == VISIBLE) { requestThumb(); postDelayed(this, 120) } }
    }
    private var locked = false
    private var userSeeking = false
    private var looping = false
    private var fitIndex = 0
    private val prefs = activity.getSharedPreferences("video", android.content.Context.MODE_PRIVATE)
    private var seekLevel = prefs.getInt("seekLevel", 0).coerceIn(0, SEEK_LEVELS.size - 1)
    private val hideCtrl = Runnable { hideControls() }
    private val poller = object : Runnable {
        override fun run() { poll(); postDelayed(this, 500) }
    }

    init {
        buildControls()
        addView(controls, LayoutParams(MATCH, MATCH))
        addView(lockBtn, LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(40) })
        addView(hint, LayoutParams(WRAP, WRAP, Gravity.CENTER))
        addView(pvBox, LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START))
        js("1")
        post(poller)
        showControls()
    }

    private fun buildControls() {
        // 上下渐变
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99000000.toInt(), 0))
            setPadding(dp(28), dp(10), dp(28), dp(24))
        }
        top.addView(iconBtn(R.drawable.ic_v_back) { cb.onExit() })
        top.addView(label(title, 15f).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4), 0, dp(12), 0)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(clock)
        top.addView(iconBtn(R.drawable.ic_v_more) { showMoreMenu(it) })
        controls.addView(top, LayoutParams(MATCH, WRAP, Gravity.TOP))

        // 右侧中间：下载
        controls.addView(iconBtn(R.drawable.ic_v_download) { download() },
            LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(40) })

        // 底部：时间 + 进度条，下面一排按钮
        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xAA000000.toInt(), 0))
            setPadding(dp(28), dp(30), dp(28), dp(6))
        }
        val bar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(curText)
        bar.addView(seek, LinearLayout.LayoutParams(0, dp(30), 1f))
        bar.addView(durText)
        bottom.addView(bar)
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(playBtn)
        row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(speedText)
        row.addView(iconBtn(R.drawable.ic_v_fit) { cycleFit() })
        row.addView(muteBtn)
        row.addView(iconBtn(R.drawable.ic_v_rotate) { rotate() })
        bottom.addView(row)
        controls.addView(bottom, LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser && duration > 0) {
                    val t = duration * p / 1000.0
                    curText.text = fmt(t)
                    showPreview(t)
                }
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) { userSeeking = true; removeCallbacks(hideCtrl) }
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {
                userSeeking = false
                hidePreview()
                if (duration > 0) js("window.__gb3.seek(${duration * sb.progress / 1000.0})")
                hint.postDelayed(hideHint, 300)
                scheduleHide()
            }
        })
    }

    /** 在进度条上方、对准当前拖动位置显示 t 秒的画面 */
    private fun showPreview(t: Double) {
        if (duration <= 0) return
        pvT = t
        pvTime.text = "${fmt(t)} / ${fmt(duration)}"
        val first = pvBox.visibility != VISIBLE
        if (first) { pvImg.setImageDrawable(null); pvLastImg = null; pvShownT = -1.0 }   // 不沿用上次拖动的旧画面
        pvBox.visibility = VISIBLE
        hint.visibility = GONE
        // 拖动时进度条也显示出来，并跟着走
        if (!locked && controls.visibility != VISIBLE) { controls.visibility = VISIBLE; lockBtn.visibility = VISIBLE }
        removeCallbacks(hideCtrl)
        if (duration > 0) { seek.progress = (t / duration * 1000).toInt(); curText.text = fmt(t) }
        val loc = IntArray(2); val me = IntArray(2)
        seek.getLocationInWindow(loc); getLocationInWindow(me)
        val usable = seek.width - seek.paddingLeft - seek.paddingRight
        val x = loc[0] - me[0] + seek.paddingLeft + usable * (t / duration) - dp(104)
        val barTop = loc[1] - me[1]
        pvBox.translationX = x.toFloat().coerceIn(dp(8).toFloat(), (width - dp(216)).toFloat())
        pvBox.translationY = (barTop - dp(150)).toFloat()
        if (first) post { if (pvBox.visibility == VISIBLE) showPreviewPos() }
        requestThumb()
        if (first) { removeCallbacks(pvRefresh); postDelayed(pvRefresh, 120) }
    }

    private fun showPreviewPos() {
        val loc = IntArray(2); val me = IntArray(2)
        seek.getLocationInWindow(loc); getLocationInWindow(me)
        val usable = seek.width - seek.paddingLeft - seek.paddingRight
        val x = loc[0] - me[0] + seek.paddingLeft + usable * (pvT / duration) - dp(104)
        pvBox.translationX = x.toFloat().coerceIn(dp(8).toFloat(), (width - dp(216)).toFloat())
        pvBox.translationY = (loc[1] - me[1] - dp(150)).toFloat()
    }

    private fun hidePreview() {
        pvBox.visibility = GONE
        removeCallbacks(pvRefresh)
        scheduleHide()
    }

    private fun requestThumb() {
        if (pvBusy) return
        pvBusy = true
        val askT = pvT
        js("window.__gb3.thumb($askT)") { r ->
            pvBusy = false
            val s = r?.trim('"')
            if (s == null || !s.startsWith("data:image")) {
                // 附近没有画面：离上次显示的画面时间太远就清掉，不显示错误时间点的画面
                if (pvShownT >= 0 && abs(pvShownT - pvT) > max(3.0, duration / 300)) { pvImg.setImageDrawable(null); pvLastImg = null; pvShownT = -1.0 }
                return@js
            }
            pvShownT = askT
            if (!s.startsWith("data:image") || s == pvLastImg) return@js
            pvLastImg = s
            runCatching {
                val b = android.util.Base64.decode(s.substringAfter(","), android.util.Base64.DEFAULT)
                pvImg.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size))
            }
        }
    }

    private fun iconBtn(res: Int, action: (View) -> Unit) = ImageView(context).apply {
        setImageResource(res)
        setColorFilter(Color.WHITE)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        background = ta.getDrawable(0); ta.recycle()
        setOnClickListener { action(it); scheduleHide() }
    }

    private fun label(t: String, size: Float) = TextView(context).apply {
        text = t; textSize = size; setTextColor(Color.WHITE)
        setShadowLayer(3f, 0f, 1f, 0x88000000.toInt())
    }

    private fun poll() {
        js("window.__gb3.info()") { r ->
            if (r == null || r == "null") return@js
            runCatching {
                val o = JSONObject(r)
                val d = o.optDouble("d", 0.0)
                val t = o.optDouble("t", 0.0)
                duration = d
                if (!userSeeking && mode != Mode.SEEK) {
                    curText.text = fmt(t)
                    if (d > 0) seek.progress = (t / d * 1000).toInt()
                }
                durText.text = fmt(d)
                playBtn.setImageResource(if (o.optBoolean("p", true)) R.drawable.ic_v_play else R.drawable.ic_v_pause)
                muteBtn.setImageResource(if (o.optBoolean("m")) R.drawable.ic_v_mute else R.drawable.ic_v_vol)
                looping = o.optBoolean("l")
                if (mode != Mode.SPEED) speedText.text = "${o.optDouble("r", 1.0).let { if (it % 1.0 == 0.0) "%.1f".format(it) else it.toString() }}x"
            }
        }
        clock.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date()) + "  " + battery()
    }

    private fun battery(): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        return "电量 " + bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) + "%"
    }

    private fun showControls() {
        if (locked) { lockBtn.visibility = VISIBLE; controls.visibility = GONE }
        else { controls.visibility = VISIBLE; lockBtn.visibility = VISIBLE }
        scheduleHide()
    }

    private fun hideControls() {
        removeCallbacks(hideCtrl)
        if (userSeeking) return
        controls.visibility = GONE
        lockBtn.visibility = GONE
    }

    private fun scheduleHide() {
        removeCallbacks(hideCtrl)
        postDelayed(hideCtrl, 4000)
    }

    private fun toggleLock() {
        locked = !locked
        lockBtn.setImageResource(if (locked) R.drawable.ic_v_lock else R.drawable.ic_v_unlock)
        flash(if (locked) "已锁定，点锁解开" else "已解锁")
        showControls()
    }

    private fun showSpeedMenu(anchor: View) {
        val rates = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0)
        popupList(anchor, rates.map { "${it}x" }) { i ->
            savedRate = rates[i]
            js("window.__gb3.rate(${rates[i]})") { poll() }
            flash("${rates[i]}x 倍速")
        }
    }

    private fun showMoreMenu(anchor: View) {
        val items = listOf(
            if (looping) "关闭循环播放" else "循环播放",
            "画面：" + FITS[fitIndex].second + "（点切换）",
            "滑动快进幅度：" + SEEK_LEVELS[seekLevel].second,
            "下载视频"
        )
        popupList(anchor, items) { i ->
            when (i) {
                0 -> js("window.__gb3.loop()") { r -> flash(if (r == "true") "循环播放已开" else "循环播放已关"); poll() }
                1 -> cycleFit()
                2 -> showSeekLevelMenu(anchor)
                3 -> download()
            }
        }
    }

    private fun showSeekLevelMenu(anchor: View) {
        val items = SEEK_LEVELS.mapIndexed { i, (sec, name) ->
            (if (i == seekLevel) "✓ " else "    ") + "$name　滑满一屏约 ${sec / 60} 分钟"
        }
        popupList(anchor, items) { i ->
            seekLevel = i
            prefs.edit().putInt("seekLevel", i).apply()
            flash("滑动快进幅度：" + SEEK_LEVELS[i].second)
        }
    }

    private fun popupList(anchor: View, items: List<String>, onPick: (Int) -> Unit) {
        removeCallbacks(hideCtrl)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xEE222222.toInt()); cornerRadius = dp(12).toFloat() }
            setPadding(0, dp(6), 0, dp(6))
        }
        val pw = android.widget.PopupWindow(box, WRAP, WRAP, true).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            setOnDismissListener { scheduleHide() }
        }
        items.forEachIndexed { i, t ->
            box.addView(label(t, 15f).apply {
                setPadding(dp(20), dp(12), dp(28), dp(12))
                setOnClickListener { pw.dismiss(); onPick(i) }
            })
        }
        box.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        pw.showAsDropDown(anchor, 0, -(anchor.height + box.measuredHeight))
    }

    private fun cycleFit() {
        fitIndex = (fitIndex + 1) % FITS.size
        js("window.__gb3.fit('${FITS[fitIndex].first}')")
        flash("画面：" + FITS[fitIndex].second)
    }

    private fun rotate() {
        val land = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        activity.requestedOrientation = if (land) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun download() {
        js("window.__gb3.src()") { r ->
            val o = runCatching { org.json.JSONObject(r ?: "") }.getOrNull()
            if (o != null && cb.onDownload(o)) flash("已开始高速下载，进度在 下载 里")
            else flash("没找到能下载的视频地址（可能是加密视频或直播）")
        }
    }

    /** 进入/退出小窗时调用：小窗里不显示任何控件 */
    fun setPipMode(pip: Boolean) {
        if (pip) { hideControls(); hint.visibility = GONE }
        visibility = if (pip) INVISIBLE else VISIBLE
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        detector.onTouchEvent(e)
        if (locked) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(e)
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onUp()
        }
        return true
    }

    private fun onDown(e: MotionEvent) {
        downX = e.x
        downY = e.y
        mode = Mode.NONE
        infoReady = false
        startBrightness = currentBrightness()
        startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        js("window.__gb3.info()") { r ->
            if (r == null || r == "null") return@js
            runCatching {
                val o = JSONObject(r)
                duration = o.optDouble("d", 0.0)
                startTime = o.optDouble("t", 0.0)
                savedRate = o.optDouble("r", 1.0).let { if (it == 2.0) 1.0 else it }
                infoReady = true
            }
        }
    }

    private fun onMove(e: MotionEvent) {
        val dx = e.x - downX
        val dy = e.y - downY
        if (mode == Mode.NONE) {
            val slop = touchSlop * 2
            if (abs(dx) > slop && abs(dx) > abs(dy)) {
                if (infoReady && duration > 0) { mode = Mode.SEEK; targetTime = startTime }
            } else if (abs(dy) > slop && abs(dy) > abs(dx)) {
                mode = if (downX < width / 2f) Mode.BRIGHTNESS else Mode.VOLUME
            }
        }
        when (mode) {
            Mode.SEEK -> { updateSeek(dx); if (duration > 0) seek.progress = (targetTime / duration * 1000).toInt(); showPreview(targetTime) }
            Mode.BRIGHTNESS -> updateBrightness(dy)
            Mode.VOLUME -> updateVolume(dy)
            else -> {}
        }
    }

    private fun onUp() {
        when (mode) {
            Mode.SEEK -> {
                hidePreview()
                js("window.__gb3.seek($targetTime)")
                hint.postDelayed(hideHint, 500)
            }
            Mode.SPEED -> {
                js("window.__gb3.rate($savedRate)")
                hint.visibility = GONE
            }
            Mode.BRIGHTNESS, Mode.VOLUME -> hint.postDelayed(hideHint, 600)
            else -> {}
        }
        mode = Mode.NONE
    }

    private fun updateSeek(dx: Float) {
        // 滑满整屏宽 = 档位秒数（标准 120 秒，短视频按全长），档位在 更多 菜单里调
        val range = max(10.0, min(duration, SEEK_LEVELS[seekLevel].first.toDouble()))
        targetTime = (startTime + dx / width * range).coerceIn(0.0, duration)
        val delta = (targetTime - startTime).toInt()
        showHint("${fmt(targetTime)} / ${fmt(duration)}\n" + if (delta >= 0) "快进 $delta 秒" else "后退 ${-delta} 秒")
    }

    private fun skip(sec: Int) {
        js("window.__gb3.skip($sec)") { r ->
            val text = if (sec > 0) "⏩ 快进 $sec 秒" else "⏪ 后退 ${-sec} 秒"
            val arr = runCatching { org.json.JSONArray(r) }.getOrNull()
            if (arr != null && arr.length() == 2) flash("$text\n${fmt(arr.getDouble(0))} / ${fmt(arr.getDouble(1))}")
            else flash(text)
        }
    }

    private fun updateBrightness(dy: Float) {
        val value = (startBrightness - dy / height).coerceIn(0.01f, 1f)
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = value }
        showHint("亮度 ${(value * 100).toInt()}%")
    }

    private fun updateVolume(dy: Float) {
        val value = (startVolume - dy / height * maxVolume * 1.2f).toInt().coerceIn(0, maxVolume)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
        showHint("音量 ${value * 100 / max(1, maxVolume)}%")
    }

    private fun currentBrightness(): Float {
        val b = activity.window.attributes.screenBrightness
        if (b >= 0) return b
        return runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f)
    }

    private fun passTapToPlayer(x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0)
        target.dispatchTouchEvent(down)
        target.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    private fun js(code: String, callback: ((String?) -> Unit)? = null) {
        webView.evaluateJavascript("$helperJs\n$code") { callback?.invoke(it) }
    }

    private fun showHint(text: String) {
        hint.removeCallbacks(hideHint)
        hint.text = text
        hint.visibility = VISIBLE
    }

    private fun flash(text: String) {
        showHint(text)
        hint.postDelayed(hideHint, 800)
    }

    private fun fmt(sec: Double): String {
        val s = sec.toInt()
        val h = s / 3600
        val m = s % 3600 / 60
        val ss = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%02d:%02d".format(m, ss)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun release() {
        hint.removeCallbacks(hideHint)
        removeCallbacks(poller)
        removeCallbacks(pvRefresh)
        removeCallbacks(hideCtrl)
    }

    companion object {
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        /** 滑满一屏跨越的秒数 to 档位名；第一档就是原来的幅度 */
        private val SEEK_LEVELS = listOf(120 to "标准", 240 to "较大", 480 to "大", 900 to "超大", 1800 to "极大")
        private val FITS = listOf("contain" to "适应", "cover" to "填充裁剪", "fill" to "拉伸铺满")
    }
}
