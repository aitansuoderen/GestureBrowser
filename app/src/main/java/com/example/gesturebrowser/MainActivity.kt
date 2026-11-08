package com.example.gesturebrowser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    companion object {
        const val HOME = "about:home" // 原生主页

        /** 监听登录表单提交，把用户名/密码交给 App（GBPwd.save） */
        val PWD_CAPTURE_JS = """
            (function(){
              if (window.__gbpw) return; window.__gbpw = 1;
              function grab(root){
                root = root || document;
                var pws = root.querySelectorAll('input[type=password]'), p = null;
                for (var i = 0; i < pws.length; i++) if (pws[i].value) { p = pws[i]; break; }
                if (!p) return;
                var ins = document.querySelectorAll('input'), u = null;
                for (var j = 0; j < ins.length; j++) {
                  if (ins[j] === p) break;
                  var t = (ins[j].type || 'text').toLowerCase();
                  if ((t === 'text' || t === 'email' || t === 'tel') && ins[j].value) u = ins[j];
                }
                try { GBPwd.save(u ? u.value : '', p.value); } catch (e) {}
              }
              document.addEventListener('submit', function(e){ grab(e.target); }, true);
              document.addEventListener('click', function(e){
                var b = e.target.closest && e.target.closest('button,input[type=submit],[role=button],a');
                if (b) setTimeout(function(){ grab(b.form || document); }, 0);
              }, true);
              document.addEventListener('keydown', function(e){
                if (e.key === 'Enter' && e.target && e.target.tagName === 'INPUT') grab(e.target.form || document);
              }, true);
            })();
        """.trimIndent()

        /** 自动填写已保存的用户名/密码（兼容 React/Vue 等框架的输入框） */
        val PWD_FILL_JS = """
            (function(U, P){
              var pw = document.querySelector('input[type=password]');
              if (!pw || pw.value) return;
              function set(el, v){
                var s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
                s.call(el, v);
                el.dispatchEvent(new Event('input', {bubbles: true}));
                el.dispatchEvent(new Event('change', {bubbles: true}));
              }
              var ins = document.querySelectorAll('input'), prev = null;
              for (var i = 0; i < ins.length; i++) {
                if (ins[i] === pw) break;
                var t = (ins[i].type || 'text').toLowerCase();
                if (t === 'text' || t === 'email' || t === 'tel') prev = ins[i];
              }
              if (prev && U && !prev.value) set(prev, U);
              set(pw, P);
            })(__U__, __P__);
        """.trimIndent()
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        /** 搜索引擎：名称、主页、搜索地址 */
        val ENGINES = linkedMapOf(
            "bing" to Triple("必应", "https://www.bing.com", "https://www.bing.com/search?q="),
            "google" to Triple("谷歌", "https://www.google.com", "https://www.google.com/search?q=")
        )
    }

    private lateinit var webContainer: FrameLayout
    private lateinit var urlInput: EditText
    private lateinit var progress: ProgressBar
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var btnTabs: TextView
    private lateinit var engineIcon: ImageView
    private lateinit var homeView: HomeView
    private lateinit var tabTray: TabTrayView
    private val thumbs = mutableMapOf<WebView, Bitmap>() // 每个标签页的缩略图

    private val tabs = mutableListOf<WebView>()
    private val homeTabs = mutableSetOf<WebView>() // 当前显示原生主页的标签页
    private var current = 0
    private val webView: WebView get() = tabs[current]
    private val onHome: Boolean get() = webView in homeTabs

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var gestureOverlay: VideoGestureOverlay? = null

    private var defaultUA = ""
    private var desktopMode = false
    private lateinit var bookmarks: BookmarkStore
    private lateinit var shortcuts: ShortcutStore
    private lateinit var icons: IconLoader
    private val prefs by lazy { getSharedPreferences("browser", MODE_PRIVATE) }
    private val snapshotFile by lazy { File(filesDir, "last_page.jpg") }

    private var engine: String
        get() = prefs.getString("engine", "bing") ?: "bing"
        set(v) { prefs.edit().putString("engine", v).apply(); updateEngineIcon() }

    private lateinit var history: HistoryStore
    private lateinit var pullRefresh: PullRefresh
    private lateinit var searchOverlay: SearchOverlay
    private val playerJs by lazy { assets.open("player.js").bufferedReader().use { it.readText() } }
    @Volatile private var lastPlayerTap = 0L
    private fun recentPlayerTap() = android.os.SystemClock.uptimeMillis() - lastPlayerTap < 2000

    private val hlsLib by lazy {
        "(function(define,module,exports){" +
            assets.open("hls.light.min.js").bufferedReader().use { it.readText() } +
            "\n}).call(window);"
    }
    private val frameExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var mmr: android.media.MediaMetadataRetriever? = null
    private var mmrUrl: String? = null

    inner class PlayerBridge(private val wv: WebView) {
        @android.webkit.JavascriptInterface
        fun tap() { lastPlayerTap = android.os.SystemClock.uptimeMillis() }

        /** 预览用的 hls.js（网站自己没有时才加载） */
        @android.webkit.JavascriptInterface
        fun hlsLib(): String = this@MainActivity.hlsLib

        /** 直链视频画面跨域时，由原生解码指定时间的画面 */
        @android.webkit.JavascriptInterface
        fun frame(id: Int, url: String, t: Double) {
            val ua = runCatching { wv.settings.userAgentString }.getOrNull()
            val ref = pageUrlForFrame
            frameExec.execute {
                var img: String? = null
                try {
                    if (mmrUrl != url) {
                        mmr?.release()
                        mmr = android.media.MediaMetadataRetriever().apply {
                            val h = HashMap<String, String>()
                            CookieManager.getInstance().getCookie(url)?.let { h["Cookie"] = it }
                            if (ua != null) h["User-Agent"] = ua
                            if (ref != null) h["Referer"] = ref
                            setDataSource(url, h)
                        }
                        mmrUrl = url
                    }
                    val bmp = mmr!!.getScaledFrameAtTime((t * 1_000_000).toLong(),
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST, 360, 202)
                    if (bmp != null) {
                        val bos = java.io.ByteArrayOutputStream()
                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, bos)
                        img = "data:image/jpeg;base64," + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
                    }
                } catch (e: Exception) {
                    mmr?.release(); mmr = null; mmrUrl = null
                }
                val arg = if (img == null) "null" else "'$img'"
                wv.post { wv.evaluateJavascript("window.__gbtCb&&__gbtCb($id,$arg)", null) }
            }
        }
    }

    @Volatile private var pageUrlForFrame: String? = null
    private lateinit var passwords: PasswordStore
    private val downloadIds: MutableList<Long>
        get() = prefs.getString("download_ids", "")!!.split(",").mapNotNull { it.toLongOrNull() }.toMutableList()

    private val unlockLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) openPasswordScreen()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileDownloads.init(this)   // 上次没下完的普通下载自动续传
        setContentView(R.layout.activity_main)
        setupSystemBars()

        webContainer = findViewById(R.id.webContainer)
        urlInput = findViewById(R.id.urlInput)
        progress = findViewById(R.id.progress)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        btnTabs = findViewById(R.id.btnTabs)
        engineIcon = findViewById(R.id.engineIcon)
        bookmarks = BookmarkStore(this)
        shortcuts = ShortcutStore(this, "common")
        icons = IconLoader(this)
        history = HistoryStore(this)
        passwords = PasswordStore(this)

        homeView = HomeView(this, icons, shortcuts, shortcuts,
            onDownloads = { showDownloads() },
            onHistory = { showHistory() },
            onBookmarks = { showBookmarks() }
        ) { openUrl(it) }
        webContainer.addView(homeView, FrameLayout.LayoutParams(MATCH, MATCH))
        val refreshIndicator = android.widget.ImageView(this)
        webContainer.addView(refreshIndicator, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        pullRefresh = PullRefresh(refreshIndicator) { it.reload() }
        setupTabTray()
        // 透明玻璃：在主页时实时模糊背后的主页内容；在网页时只透明
        val bg = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_app)
        listOf(R.id.bottomBar, R.id.searchPill).forEach { id ->
            findViewById<GlassLayout>(id).apply {
                backdrop = bg
                source = { if (onHome && homeView.visibility == View.VISIBLE) homeView else null }
                live = { homeView.editing }
            }
        }
        val glasses = listOf<GlassLayout>(findViewById(R.id.bottomBar), findViewById(R.id.searchPill))
        homeView.onDrawn = { glasses.forEach { it.postInvalidateOnAnimation() } }
        searchOverlay = SearchOverlay(this, SearchHistoryStore(this), history,
            engineIconRes = { engineIconRes(engine) },
            onEngineClick = { showEngineMenu(it) }
        ) { openUrl(normalizeInput(it)) }
        (findViewById<View>(R.id.browserRoot).parent as FrameLayout)
            .addView(searchOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        searchOverlay.onShowChange = { Motion.blur(findViewById(R.id.browserRoot), it) }
        // 底部搜索栏只当按钮用：点一下打开搜索页
        urlInput.isFocusable = false
        urlInput.isFocusableInTouchMode = false
        urlInput.isCursorVisible = false
        urlInput.setOnClickListener { openSearch() }

        CookieManager.getInstance().setAcceptCookie(true)
        setupControls()
        setupBack()
        updateEngineIcon()

        val saved = savedInstanceState?.getStringArrayList("tabs")
        if (!saved.isNullOrEmpty()) {
            saved.forEach { newTab(it, switchTo = false) }
            switchTab(savedInstanceState.getInt("current", 0).coerceIn(0, tabs.size - 1))
        } else {
            newTab(intent?.dataString ?: HOME)
        }
    }

    /**
     * 不让网页盖住状态栏和导航栏：
     * 统一走 edge-to-edge（Android 15 强制如此），再按系统栏高度给浏览器界面加内边距；
     * 键盘弹出时底栏跟着上移。全屏看视频时 browserRoot 隐藏，视频照样铺满整屏。
     */
    private fun setupSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        val root = findViewById<View>(R.id.browserRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            insets
        }
    }

    // ---------------- 标签页 ----------------

    /** 新建标签页；url = HOME 显示原生主页，null 不加载（给 window.open 用） */
    private fun newTab(url: String?, switchTo: Boolean = true): WebView {
        val wv = createWebView()
        tabs.add(wv)
        webContainer.addView(wv, webContainer.indexOfChild(homeView), FrameLayout.LayoutParams(MATCH, MATCH))
        wv.visibility = View.GONE
        when (url) {
            HOME -> homeTabs.add(wv)
            null -> {}
            else -> wv.loadUrl(url)
        }
        if (switchTo) switchTab(tabs.size - 1) else updateTabButton()
        return wv
    }

    private fun switchTab(index: Int) {
        if (current in tabs.indices && !tabTray.isShown) captureThumb()
        current = index
        tabs.forEachIndexed { i, wv ->
            // 只有当前页预先栅格化（滚动更顺），后台页暂停脚本和渲染
            wv.settings.offscreenPreRaster = i == index
            if (i == index) wv.onResume() else wv.onPause()
        }
        progress.visibility = View.GONE
        refreshVisibility()
        updateTabButton()
    }

    private fun closeTab(index: Int) {
        val wv = tabs.removeAt(index)
        homeTabs.remove(wv)
        thumbs.remove(wv)
        webContainer.removeView(wv)
        wv.destroy()
        if (tabs.isEmpty()) {
            newTab(HOME)
            return
        }
        switchTab(if (index <= current) (current - 1).coerceAtLeast(0) else current)
    }

    /** 根据当前标签页决定显示网页还是主页 */
    private var lastShown: View? = null

    private fun refreshVisibility() {
        val shown: View? = if (onHome) homeView else tabs.getOrNull(current)
        val changed = shown !== lastShown
        if (shown != null && changed && lastShown != null) Motion.enter(shown)
        lastShown = shown
        tabs.forEachIndexed { i, wv ->
            wv.visibility = if (i == current && !onHome) View.VISIBLE else View.GONE
        }
        if (onHome) {
            homeView.visibility = View.VISIBLE
            homeView.refresh(lastPage(), animate = changed)
            if (changed) listOf<GlassLayout>(findViewById(R.id.bottomBar), findViewById(R.id.searchPill)).forEach { it.pump(700) }
            homeView.scrollTo(0, 0)
            urlInput.setText("")
        } else {
            homeView.visibility = View.GONE
            urlInput.setText(webView.url ?: "")
        }
        updateNavButtons()
    }

    private fun openSearch() {
        searchOverlay.open(if (onHome) "" else webView.url ?: "")
    }

    private fun goForward() {
        when {
            onHome && webView.url != null -> { homeTabs.remove(webView); refreshVisibility() }
            !onHome && webView.canGoForward() -> webView.goForward()
        }
    }

    /** 底栏后退/前进按钮的可用状态 */
    private fun updateNavButtons() {
        if (tabs.isEmpty()) return
        val back = !onHome
        val fwd = (onHome && webView.url != null) || (!onHome && webView.canGoForward())
        findViewById<View>(R.id.btnBack).alpha = if (back) 1f else 0.35f
        findViewById<View>(R.id.btnForward).alpha = if (fwd) 1f else 0.35f
        findViewById<View>(R.id.btnRefreshBar).visibility = if (onHome) View.GONE else View.VISIBLE
    }

    private fun goHome() {
        if (!onHome) captureSnapshot(webView)
        homeTabs.add(webView)
        refreshVisibility()
    }

    private fun openUrl(url: String) {
        homeTabs.remove(webView)
        webView.loadUrl(url)
        refreshVisibility()
        urlInput.setText(url)
    }

    private fun updateTabButton() {
        val t = tabs.size.toString()
        if (btnTabs.text.toString() != t && btnTabs.isAttachedToWindow) Motion.bump(btnTabs)
        btnTabs.text = t
    }

    private fun setupTabTray() {
        tabTray = TabTrayView(this, icons, object : TabTrayView.Callbacks {
            override fun onSelect(index: Int) { hideTabs(); switchTab(index) }
            override fun onClose(index: Int) {
                closeTab(index)
                tabTray.show(trayItems(), current)
            }
            override fun onNewTab() { hideTabs(); newTab(HOME) }
            override fun onSearch() {
                hideTabs(); newTab(HOME)
                openSearch()
            }
            override fun onCloseAll() {
                hideTabs()
                while (tabs.size > 1) closeTab(tabs.size - 1)
                closeTab(0) // 最后一个关掉后会自动开一个主页
            }
        })
        tabTray.visibility = View.GONE
        (findViewById<View>(R.id.browserRoot).parent as FrameLayout)
            .addView(tabTray, 1, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun trayItems() = tabs.map { wv ->
        val home = wv in homeTabs
        TabTrayView.Item(
            title = if (home) "主页" else (wv.title?.takeIf { it.isNotBlank() } ?: wv.url ?: "新标签页"),
            url = if (home) null else wv.url,
            isHome = home,
            favicon = if (home) null else wv.favicon,
            thumb = thumbs[wv]
        )
    }

    /** 搜索栏和底栏浮在网页上：在网页末尾垫一段等高的空白，最后的内容才能滚到玻璃上方 */
    private fun padPage(view: WebView) {
        val h = (findViewById<View>(R.id.chrome).height / resources.displayMetrics.density).toInt()
        if (h <= 0) return
        view.evaluateJavascript("""(function(){try{var d=document.getElementById('__gbpad');
            if(!d){d=document.createElement('div');d.id='__gbpad';d.setAttribute('aria-hidden','true');
            d.style.cssText='display:block;width:1px;pointer-events:none;flex:none';}
            d.style.height='${h}px';if(document.body&&d.parentNode!==document.body)document.body.appendChild(d);}catch(e){}})()""", null)
    }

    private fun showTabs() {
        hideKeyboard()
        captureThumb()
        tabTray.show(trayItems(), current)
        tabTray.visibility = View.VISIBLE
        Motion.enter(tabTray, 1.04f, 240)
        Motion.blur(findViewById(R.id.browserRoot), true)
    }

    private fun hideTabs() {
        if (tabTray.visibility != View.VISIBLE) return
        Motion.blur(findViewById(R.id.browserRoot), false)
        Motion.fadeOut(tabTray, 200, 1.06f) { tabTray.visibility = View.GONE }
    }

    /** 给当前标签页拍一张竖向缩略图（主页就拍主页） */
    private fun captureThumb() {
        if (tabs.isEmpty() || current !in tabs.indices) return
        val wv = webView
        val view: View = if (onHome) homeView else wv
        if (view.width == 0 || view.height == 0 || view.visibility != View.VISIBLE) return
        runCatching {
            val scale = 0.4f
            val w = (view.width * scale).toInt()
            val h = minOf((w * 1.05f).toInt(), (view.height * scale).toInt())
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            val c = Canvas(bmp)
            c.drawColor(Color.WHITE)
            c.scale(scale, scale)
            c.translate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
            view.draw(c)
            thumbs[wv] = bmp
        }
    }

    // ---------------- WebView ----------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val wv = WebView(this)
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            // 页面里的图片、字体等交给系统缓存；不再强制加载概览
            loadsImagesAutomatically = true
            blockNetworkImage = false
            safeBrowsingEnabled = false     // 每次打开网页不再先等安全检查，首屏更快
        }
        // 当前页渲染优先级高；切到后台的标签页自动降低，省电省内存
        wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        wv.isScrollbarFadingEnabled = true
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        if (defaultUA.isEmpty()) defaultUA = wv.settings.userAgentString
        if (desktopMode) wv.settings.userAgentString = DESKTOP_UA
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                // 刚点过内置播放器：拦下网站借点击跳去别的网站（跳转广告）
                if (request.isForMainFrame && recentPlayerTap() &&
                    request.url.host != Uri.parse(view.url ?: "").host) {
                    toast("已拦截跳转广告")
                    return true
                }
                if (url.startsWith("http://") || url.startsWith("https://")) return false
                return try {
                    val intent = if (url.startsWith("intent://"))
                        Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    else Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                    true
                } catch (e: Exception) {
                    true
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                view.setTag(R.id.webContainer, false)
                pageUrlForFrame = url
                if (isVisibleTab(view) && !urlInput.hasFocus()) urlInput.setText(url)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                if (view === webView) updateNavButtons()
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (view === webView) updateNavButtons()
                padPage(view)
                history.add(view.title ?: url, url)
                injectPasswordJs(view)
                injectPlayer(view)
                if (isVisibleTab(view) && !urlInput.hasFocus()) urlInput.setText(url)
                view.postDelayed({
                    if (isVisibleTab(view)) { captureSnapshot(view); if (!tabTray.isShown) captureThumb() }
                }, 1500)
            }
        }

        wv.addJavascriptInterface(PwdBridge(wv), "GBPwd")
        wv.addJavascriptInterface(PlayerBridge(wv), "GBPlayer")
        pullRefresh.attach(wv)

        wv.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                val u = view.url ?: return
                if (!title.isNullOrBlank()) history.updateTitle(u, title)
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (!isVisibleTab(view)) return
                if (newProgress >= 80 && view.getTag(R.id.webContainer) != true) { view.setTag(R.id.webContainer, true); injectPlayer(view); padPage(view) }
                if (newProgress < progress.progress) progress.progress = newProgress
                else android.animation.ObjectAnimator.ofInt(progress, "progress", newProgress).setDuration(240).start()
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                showFullscreenVideo(view, callback)
            }

            override fun onHideCustomView() {
                hideFullscreenVideo()
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                // 拦截弹窗广告：不是用户点的，或刚点过内置播放器
                if (!isUserGesture || recentPlayerTap()) {
                    toast("已拦截弹窗")
                    return false
                }
                val newView = newTab(null)
                (resultMsg.obj as WebView.WebViewTransport).webView = newView
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                val i = tabs.indexOf(window)
                if (i >= 0) closeTab(i)
            }
        }

        // 长按链接
        wv.setOnLongClickListener {
            val hit = wv.hitTestResult
            val link = hit.extra
            if ((hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                    hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) && link != null
            ) {
                pullRefresh.pendingLongPress = { AlertDialog.Builder(this)
                    .setTitle(link)
                    .setItems(arrayOf("在新标签页打开", "后台打开新标签页", "添加到主页快捷方式", "复制链接")) { _, which ->
                        when (which) {
                            0 -> newTab(link)
                            1 -> { newTab(link, switchTo = false); toast("已在后台打开") }
                            2 -> { shortcuts.add(Uri.parse(link).host ?: link, link); toast("已添加到主页") }
                            3 -> {
                                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("link", link))
                                toast("已复制")
                            }
                        }
                    }
                    .show() }
                true
            } else false
        }

        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }
        return wv
    }

    private fun startDownload(url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        run {
            if (!url.startsWith("http")) {
                toast("这是网页临时生成的文件，暂不支持下载")
                return@run
            }
            // 视频走自己的多连接高速下载
            if (mimeType?.startsWith("video/") == true || Regex("\\.(mp4|m4v|webm|mkv|mov|flv)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
                askNotifyPermission()
                val name = URLUtil.guessFileName(url, contentDisposition, mimeType).substringBeforeLast('.')
                StreamDownloads.startDirect(this, url, userAgent, runCatching { webView.url }.getOrNull() ?: "", name)
                toast("开始高速下载：$name")
                return@run
            }
            // 普通文件：自己的多连接分段下载（动态分段、断点续传、自动重试）
            try {
                askNotifyPermission()
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                FileDownloads.start(this, url, userAgent, runCatching { webView.url }.getOrNull() ?: "", fileName, mimeType)
                toast("开始下载：$fileName")
            } catch (e: Exception) {
                toast("下载失败")
            }
        }
    }

    private fun isVisibleTab(view: WebView) = tabs.isNotEmpty() && view === webView && !onHome

    // ---------------- "继续"卡片：记住最近看过的网页 ----------------

    private fun captureSnapshot(view: WebView) {
        val url = view.url ?: return
        if (view.width == 0 || view.height == 0 || view.visibility != View.VISIBLE) return
        prefs.edit()
            .putString("last_title", view.title ?: url)
            .putString("last_url", url)
            .apply()
    }

    /** 内置播放器脚本约 30KB：先问一下页面里是否已经有了，没有才注入，避免每次进度变化都重复传输 */
    private fun injectPlayer(view: WebView) {
        view.evaluateJavascript("window.__gbp?1:0") { r -> if (r != "1") view.evaluateJavascript(playerJs, null) }
    }

    private fun lastPage(): HomeView.LastPage? {
        val url = prefs.getString("last_url", null) ?: return null
        return HomeView.LastPage(prefs.getString("last_title", url) ?: url, url, snapshotFile)
    }

    // ---------------- 底部栏 ----------------

    private fun setupControls() {
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                openUrl(normalizeInput(urlInput.text.toString()))
                urlInput.clearFocus()
                hideKeyboard()
                true
            } else false
        }
        findViewById<View>(R.id.btnEngine).setOnClickListener { showEngineMenu(it) }
        findViewById<View>(R.id.btnTabsWrap).setOnClickListener { showTabs() }
        btnTabs.setOnClickListener { showTabs() }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenu(it) }
        listOf(R.id.btnBack, R.id.btnForward, R.id.btnMenu, R.id.btnTabsWrap, R.id.btnHome, R.id.btnEngine, R.id.btnRefreshBar)
            .forEach { Motion.press(findViewById(it), 0.84f) }
        findViewById<View>(R.id.btnBack).setOnClickListener { if (!onHome) goBack() }
        findViewById<View>(R.id.btnForward).setOnClickListener { goForward() }
        findViewById<View>(R.id.btnHome).setOnClickListener { if (!onHome) goHome() }
        findViewById<View>(R.id.btnRefreshBar).setOnClickListener { if (!onHome) webView.reload() }
    }

    private fun engineIconRes(key: String) =
        if (key == "google") R.drawable.ic_engine_google else R.drawable.ic_engine_bing

    /** 左下角引擎切换：白色圆角卡片，每行 图标 + 名称 + 勾选 */
    private fun showEngineMenu(anchor: View) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_popup)
            elevation = dp(8).toFloat()
            setPadding(0, dp(8), 0, dp(8))
        }
        val popup = android.widget.PopupWindow(card, dp(200), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            elevation = dp(8).toFloat()
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            animationStyle = R.style.PopupAnim
        }
        card.addView(TextView(this).apply {
            text = "搜索引擎"
            textSize = 14f
            setTextColor(getColor(R.color.text_sub))
            setPadding(dp(18), dp(4), dp(18), dp(8))
        })
        ENGINES.forEach { (key, e) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(12), dp(14), dp(12))
                val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                background = ta.getDrawable(0)
                ta.recycle()
                setOnClickListener {
                    engine = key
                    popup.dismiss()
                }
            }
            row.addView(ImageView(this).apply { setImageResource(engineIconRes(key)) },
                LinearLayout.LayoutParams(dp(24), dp(24)))
            row.addView(TextView(this).apply {
                text = e.first
                textSize = 16f
                setTextColor(getColor(R.color.text_main))
                setPadding(dp(14), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_check)
                visibility = if (key == engine) View.VISIBLE else View.INVISIBLE
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
            card.addView(row)
        }
        // 弹在按钮正上方
        card.measure(
            View.MeasureSpec.makeMeasureSpec(dp(200), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        popup.showAsDropDown(anchor, 0, -(anchor.height + card.measuredHeight + dp(10)))
    }

    private fun updateEngineIcon() {
        engineIcon.setImageResource(engineIconRes(engine))
        if (::searchOverlay.isInitialized) searchOverlay.refreshEngine()
        urlInput.hint = "搜索或输入网址"
    }

    /** 右下角 ⋮：底部菜单（仿 Firefox），含 历史 / 书签 / 下载 / 密码 */
    private fun showMenu(@Suppress("UNUSED_PARAMETER") anchor: View) {
        val url = if (onHome) null else webView.url
        val isBookmarked = url != null && bookmarks.all().any { it.second == url }
        Ui.bottomSheet(
            this,
            header = url?.let { Ui.SheetHeader(webView.title?.ifBlank { null } ?: it, it) },
            icons = icons,
            navRow = listOf(
                Ui.SheetItem(R.drawable.ic_back, "后退", !onHome) { goBack() },
                Ui.SheetItem(R.drawable.ic_forward, "前进", webView.canGoForward() || onHome && webView.url != null) {
                    if (onHome) { homeTabs.remove(webView); refreshVisibility() } else webView.goForward()
                },
                Ui.SheetItem(R.drawable.ic_refresh, "刷新", !onHome) { webView.reload() },
                Ui.SheetItem(R.drawable.ic_home, "主页") { goHome() },
                Ui.SheetItem(R.drawable.ic_share, "分享", url != null) {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    }, "分享网页"))
                }
            ),
            tiles = listOf(
                Ui.SheetItem(R.drawable.ic_history, "历史") { showHistory() },
                Ui.SheetItem(R.drawable.ic_star, "书签") { showBookmarks() },
                Ui.SheetItem(R.drawable.ic_download, "下载") { showDownloads() },
                Ui.SheetItem(R.drawable.ic_key, "密码") { showPasswords() }
            ),
            rows = listOf(
                Ui.SheetItem(R.drawable.ic_add, "新建标签页") { newTab(HOME) },
                Ui.SheetItem(R.drawable.ic_star, if (isBookmarked) "移除书签" else "添加书签", url != null) {
                    if (isBookmarked) { bookmarks.remove(url!!); toast("已移除书签") }
                    else { bookmarks.add(webView.title ?: url!!, url!!); toast("已添加书签") }
                },
                Ui.SheetItem(R.drawable.ic_home, "添加到主页快捷方式", url != null) {
                    shortcuts.add(webView.title ?: url!!, url!!); toast("已添加到主页")
                },
                Ui.SheetItem(R.drawable.ic_desktop, if (desktopMode) "切换为手机版网站" else "切换为电脑版网站") {
                    desktopMode = !desktopMode
                    tabs.forEach { t -> t.settings.userAgentString = if (desktopMode) DESKTOP_UA else defaultUA }
                    if (!onHome) webView.reload()
                }
            )
        )
    }

    private fun copy(text: String, msg: String = "已复制") {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("text", text))
        toast(msg)
    }

    // ---------------- 历史 ----------------

    private fun showHistory() {
        lateinit var screen: Ui.ListScreen
        screen = Ui.ListScreen(this, "历史", icons, searchable = true, actionLabel = "清除") {
            AlertDialog.Builder(this)
                .setTitle("清除全部浏览历史？")
                .setPositiveButton("清除") { _, _ -> history.clear(); fillHistory(screen) }
                .setNegativeButton("取消", null)
                .show()
        }
        screen.emptyText = "还没有浏览历史"
        screen.onFilter = { fillHistory(screen) }
        fillHistory(screen)
        screen.show()
    }

    private fun fillHistory(screen: Ui.ListScreen) {
        val q = screen.query
        val list = history.all().filter { q.isEmpty() || it.title.contains(q, true) || it.url.contains(q, true) }
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val today = cal.timeInMillis
        val yesterday = today - 86_400_000L
        val week = today - 6 * 86_400_000L
        val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
        val dateFmt = java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA)
        val rows = mutableListOf<Ui.Row>()
        var lastGroup = ""
        list.forEach { e ->
            val group = when {
                e.time >= today -> "今天"
                e.time >= yesterday -> "昨天"
                e.time >= week -> "最近 7 天"
                else -> "更早"
            }
            if (group != lastGroup) { rows.add(Ui.Row(header = group)); lastGroup = group }
            val host = Uri.parse(e.url).host ?: e.url
            val t = if (e.time >= yesterday) timeFmt.format(e.time) else dateFmt.format(e.time)
            rows.add(Ui.Row(
                title = e.title, subtitle = "$t · $host", iconUrl = e.url,
                onClick = { screen.dismiss(); openUrl(e.url) },
                onLongClick = { v ->
                    Ui.popup(v, listOf(
                        "在新标签页打开" to { screen.dismiss(); newTab(e.url) },
                        "复制链接" to { copy(e.url) },
                        "删除" to { history.remove(e.url); fillHistory(screen) }
                    ))
                }
            ))
        }
        screen.setRows(rows)
    }

    // ---------------- 书签 ----------------

    private fun showBookmarks() {
        val url = if (onHome) null else webView.url
        lateinit var screen: Ui.ListScreen
        screen = Ui.ListScreen(this, "书签", icons, searchable = true,
            actionLabel = if (url != null) "添加当前页" else null) {
            bookmarks.add(webView.title ?: url!!, url!!)
            toast("已添加书签")
            fillBookmarks(screen)
        }
        screen.emptyText = "还没有书签\n在网页上点 ⋮ → 添加书签"
        screen.onFilter = { fillBookmarks(screen) }
        fillBookmarks(screen)
        screen.show()
    }

    private fun fillBookmarks(screen: Ui.ListScreen) {
        val q = screen.query
        val rows = bookmarks.all().reversed()
            .filter { q.isEmpty() || it.first.contains(q, true) || it.second.contains(q, true) }
            .map { (title, url) ->
                Ui.Row(
                    title = title, subtitle = url, iconUrl = url,
                    onClick = { screen.dismiss(); openUrl(url) },
                    onLongClick = { v ->
                        Ui.popup(v, listOf(
                            "在新标签页打开" to { screen.dismiss(); newTab(url) },
                            "重命名" to { renameBookmark(title, url) { fillBookmarks(screen) } },
                            "复制链接" to { copy(url) },
                            "删除" to { bookmarks.remove(url); fillBookmarks(screen) }
                        ))
                    }
                )
            }
        screen.setRows(rows)
    }

    private fun renameBookmark(title: String, url: String, done: () -> Unit) {
        val input = EditText(this).apply { setText(title); setSelection(title.length) }
        AlertDialog.Builder(this)
            .setTitle("重命名书签")
            .setView(FrameLayout(this).apply {
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(input)
            })
            .setPositiveButton("保存") { _, _ ->
                val all = bookmarks.all()
                all.forEach { bookmarks.remove(it.second) }
                all.forEach { (t, u) -> bookmarks.add(if (u == url) input.text.toString().ifBlank { t } else t, u) }
                done()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 下载 ----------------

    private fun showDownloads() {
        lateinit var page: DownloadsPage
        page = DownloadsPage(this, { downloadItems { page.refresh() } }) {
            try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (e: Exception) { toast("没有可用的文件管理器") }
        }
        page.show()
    }

    /** 普通文件下载的网速：id -> (已下字节, 时间, 速度) */
    private val dmSpeed = HashMap<Long, LongArray>()
    private fun dmSpeedOf(id: Long, got: Long): Long {
        val now = android.os.SystemClock.elapsedRealtime()
        val s = dmSpeed[id] ?: return 0L.also { dmSpeed[id] = longArrayOf(got, now, 0) }
        val dt = now - s[1]
        if (dt >= 700) {
            val inst = ((got - s[0]) * 1000 / dt).coerceAtLeast(0)
            s[2] = if (s[2] == 0L) inst else (s[2] + inst) / 2
            s[0] = got; s[1] = now
        }
        return s[2]
    }

    private fun confirmDelete(name: String, finished: Boolean, onDelete: (Boolean) -> Unit) {
        val box = android.widget.CheckBox(this).apply { text = "同时删除原始文件"; textSize = 15f }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(4), dp(22), 0)
            if (finished) addView(box) else addView(TextView(this@MainActivity).apply {
                text = "未下完的部分会一起删除"; textSize = 14f; setTextColor(getColor(R.color.text_sub))
            })
        }
        AlertDialog.Builder(this)
            .setTitle("删除提示")
            .setMessage("删除任务「$name」？")
            .setView(wrap)
            .setPositiveButton("删除") { _, _ -> onDelete(!finished || box.isChecked) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDetails(lines: List<Pair<String, String>>) {
        val tv = TextView(this).apply {
            setTextIsSelectable(true); textSize = 14f; setTextColor(getColor(R.color.text_main))
            setPadding(dp(22), dp(8), dp(22), 0)
            text = lines.filter { it.second.isNotBlank() }.joinToString("\n\n") { "${it.first}\n${it.second}" }
        }
        AlertDialog.Builder(this).setTitle("任务详情").setView(android.widget.ScrollView(this).apply { addView(tv) })
            .setPositiveButton("好", null).show()
    }

    private fun askNewUrl(old: String, onOk: (String) -> Unit) {
        val et = android.widget.EditText(this).apply { setText(old); textSize = 14f; setSelectAllOnFocus(true) }
        val wrap = android.widget.FrameLayout(this).apply { setPadding(dp(20), dp(4), dp(20), 0); addView(et) }
        AlertDialog.Builder(this).setTitle("更新链接")
            .setMessage("原链接过期时，把新的下载地址粘贴进来，文件大小一致就从断点接着下")
            .setView(wrap)
            .setPositiveButton("确定") { _, _ -> val u = et.text.toString().trim(); if (u.startsWith("http")) onOk(u) else toast("链接不对") }
            .setNegativeButton("取消", null).show()
    }

    /** 下载页的数据：自己的普通下载 + 视频下载 + 旧版系统下载记录 */
    private fun downloadItems(refresh: () -> Unit): List<DownloadsPage.Item> {
        val out = mutableListOf<DownloadsPage.Item>()
        StreamDownloads.init(this)
        FileDownloads.init(this)
        val D = DownloadsPage.St.DONE
        FileDownloads.tasks.toList().forEach { t ->
            FileDownloads.tick()
            val st = when (t.s) {
                FileDownloads.S.RUN -> DownloadsPage.St.RUN
                FileDownloads.S.WAIT -> DownloadsPage.St.WAIT
                FileDownloads.S.PAUSE -> DownloadsPage.St.PAUSE
                FileDownloads.S.FAIL -> DownloadsPage.St.FAIL
                FileDownloads.S.DONE -> D
            }
            val total = t.state.total
            val conns = t.engine?.connections ?: 0
            val status = when (t.s) {
                FileDownloads.S.PAUSE -> "已暂停"
                FileDownloads.S.FAIL -> t.msg.ifBlank { "下载失败" }
                FileDownloads.S.WAIT -> t.msg.ifBlank { "等待中" }
                FileDownloads.S.DONE -> "已完成"
                else -> t.msg
            }
            val note = when {
                t.s == FileDownloads.S.RUN && t.msg.isNotBlank() -> t.msg
                t.s == FileDownloads.S.RUN && total > 0 && t.speed > 0 -> "剩余 " + leftText((total - t.bytes.get()) / t.speed) + " · $conns 线程"
                total > 0 && !t.state.ranged && t.s != FileDownloads.S.DONE -> "该链接不支持断点续传，暂停后会从头下"
                else -> null
            }
            val toggle = {
                when (t.s) {
                    FileDownloads.S.PAUSE, FileDownloads.S.FAIL -> FileDownloads.resume(this, t)
                    FileDownloads.S.DONE -> {}
                    else -> FileDownloads.pause(t)
                }
                refresh()
            }
            val menu = mutableListOf<Pair<String, () -> Unit>>()
            if (t.s == FileDownloads.S.DONE) menu += "打开" to { t.uri?.let { openUri(it, t.mime) } ?: Unit }
            menu += "重新下载" to { FileDownloads.restart(this, t); refresh() }
            menu += "更新链接" to { askNewUrl(t.url) { u -> FileDownloads.updateUrl(this, t, u); refresh() } }
            menu += "任务详情" to {
                showDetails(listOf(
                    "文件名" to t.name,
                    "大小" to (if (total > 0) size(total) else "未知"),
                    "已下载" to size(t.bytes.get()),
                    "状态" to status.ifBlank { "下载中" },
                    "断点续传" to (if (t.state.ranged) "支持（多线程分段）" else "不支持（单线程）"),
                    "保存位置" to "下载/GestureBrowser",
                    "来源网页" to t.referer,
                    "下载链接" to t.url))
            }
            menu += "删除任务" to {
                confirmDelete(t.name, t.s == FileDownloads.S.DONE) { del -> FileDownloads.remove(t, del); refresh() }
            }
            out += DownloadsPage.Item(
                key = "f${t.id}", name = t.name, total = total, got = t.bytes.get(), speed = t.speed, st = st,
                status = status, note = note,
                onTap = { if (t.s == FileDownloads.S.DONE) t.uri?.let { openUri(it, t.mime) } else toggle() },
                onButton = toggle, menu = menu)
        }
        StreamDownloads.tasks.toList().forEach { t ->
            StreamDownloads.sample(t)
            val done = !t.running && t.uri != null
            val st = when {
                done -> D
                t.running && t.status == "暂停中…" -> DownloadsPage.St.PAUSE
                t.running && (t.status == "下载中" || t.bytes.get() > 0) -> DownloadsPage.St.RUN
                t.running -> DownloadsPage.St.WAIT
                t.failed -> DownloadsPage.St.FAIL
                else -> DownloadsPage.St.PAUSE
            }
            val toggle = {
                when {
                    StreamDownloads.canResume(t) -> StreamDownloads.resume(this, t)
                    t.running -> StreamDownloads.pause(t)
                }
                refresh()
            }
            val ext = if (t.mime == "video/mp2t") ".ts" else ".mp4"
            val menu = mutableListOf<Pair<String, () -> Unit>>()
            if (done) menu += "打开" to { t.uri?.let { openUri(it, t.mime) } ?: Unit }
            if (t.job != null) menu += "重新下载" to { StreamDownloads.restart(this, t); refresh() }
            menu += "任务详情" to {
                showDetails(listOf(
                    "文件名" to t.title + ext,
                    "大小" to (if (done) size(t.size) else "下载中，合成后才知道"),
                    "已下载" to size(if (done) t.size else t.bytes.get()),
                    "进度" to (if (done) "已完成" else "${t.pct}%（按分片计）"),
                    "状态" to t.status,
                    "保存位置" to "下载/GestureBrowser"))
            }
            menu += "删除任务" to {
                confirmDelete(t.title, done) { del -> StreamDownloads.remove(t, del); refresh() }
            }
            out += DownloadsPage.Item(
                key = "s${t.id}", name = t.title + ext, total = if (done) t.size else 0L,
                got = if (done) t.size else t.bytes.get(), speed = t.speed, st = st,
                status = when (st) { DownloadsPage.St.PAUSE -> if (t.running) "暂停中…" else "已暂停"; DownloadsPage.St.WAIT -> t.status; else -> t.status },
                note = if (!done && t.total > 0) "进度 ${t.pct}%" + (if (st == DownloadsPage.St.RUN) " · ${t.status}" else "") else null,
                pct = if (done) 100 else t.pct,
                onTap = { if (done) t.uri?.let { openUri(it, t.mime) } else toggle() },
                onButton = toggle, menu = menu)
        }
        // 旧版本用系统下载器下的记录
        val ids = downloadIds
        if (ids.isNotEmpty()) {
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.query(DownloadManager.Query().setFilterById(*ids.toLongArray()))?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                    val title = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: "文件"
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val st = when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> D
                        DownloadManager.STATUS_FAILED -> DownloadsPage.St.FAIL
                        DownloadManager.STATUS_PAUSED -> DownloadsPage.St.PAUSE
                        DownloadManager.STATUS_PENDING -> DownloadsPage.St.WAIT
                        else -> DownloadsPage.St.RUN
                    }
                    val remove = {
                        dm.remove(id)
                        prefs.edit().putString("download_ids", downloadIds.filter { it != id }.joinToString(",")).apply()
                        refresh()
                    }
                    out += DownloadsPage.Item(
                        key = "d$id", name = title, total = total, got = got,
                        speed = if (st == DownloadsPage.St.RUN) dmSpeedOf(id, got) else 0L, st = st,
                        status = when (st) { D -> "已完成"; DownloadsPage.St.FAIL -> "下载失败"; DownloadsPage.St.PAUSE -> "已暂停"; else -> "等待中" },
                        onTap = { if (st == D) openDownload(id) },
                        menu = listOf("打开" to { openDownload(id) }, "删除任务" to { confirmDelete(title, true) { _ -> remove() } }))
                }
            }
        }
        return out
    }

    private fun leftText(sec: Long): String = when {
        sec >= 3600 -> "${sec / 3600} 小时 ${sec % 3600 / 60} 分"
        sec >= 60 -> "${sec / 60} 分 ${sec % 60} 秒"
        else -> "$sec 秒"
    }

    private fun openUri(uri: Uri, mime: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: ActivityNotFoundException) { toast("没有能打开这个文件的应用") }
    }

    private fun askNotifyPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            && !prefs.getBoolean("asked_notify", false)) {
            prefs.edit().putBoolean("asked_notify", true).apply()
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 77)
        }
    }

    private fun openDownload(id: Long) {
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val uri = dm.getUriForDownloadedFile(id) ?: return toast("文件不存在")
        val mime = dm.getMimeTypeForDownloadedFile(id) ?: "*/*"
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: ActivityNotFoundException) {
            toast("没有能打开这个文件的应用")
        }
    }

    private fun size(b: Long): String = when {
        b <= 0 -> "0 B"
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
        else -> "%.2f GB".format(b / 1073741824.0)
    }

    // ---------------- 密码 ----------------

    /** 查看密码前先验证手机锁屏（指纹/密码），没设锁屏就直接进 */
    private fun showPasswords() {
        val km = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
        @Suppress("DEPRECATION")
        val intent = if (km.isDeviceSecure) km.createConfirmDeviceCredentialIntent("已保存的密码", "验证身份后查看") else null
        if (intent != null) unlockLauncher.launch(intent) else openPasswordScreen()
    }

    private fun openPasswordScreen() {
        lateinit var screen: Ui.ListScreen
        screen = Ui.ListScreen(this, "密码", icons, searchable = true,
            actionLabel = if (passwords.enabled) "已开启保存" else "已关闭保存") {
            passwords.enabled = !passwords.enabled
            toast(if (passwords.enabled) "已开启：登录时询问是否保存密码" else "已关闭：不再询问保存密码")
            screen.dismiss(); openPasswordScreen()
        }
        screen.emptyText = "还没有保存的密码\n在网站登录时会询问是否保存"
        screen.onFilter = { fillPasswords(screen) }
        fillPasswords(screen)
        screen.show()
    }

    private fun fillPasswords(screen: Ui.ListScreen) {
        val q = screen.query
        val rows = passwords.all()
            .filter { q.isEmpty() || it.host.contains(q, true) || it.user.contains(q, true) }
            .map { l ->
                Ui.Row(
                    title = l.host, subtitle = l.user.ifEmpty { "（无用户名）" }, iconUrl = "https://${l.host}",
                    onClick = { showLogin(l) { fillPasswords(screen) } },
                    onLongClick = { v ->
                        Ui.popup(v, listOf(
                            "复制用户名" to { copy(l.user, "已复制用户名") },
                            "复制密码" to { copy(l.password, "已复制密码") },
                            "删除" to { passwords.remove(l.host, l.user); fillPasswords(screen) }
                        ))
                    }
                )
            }
        screen.setRows(rows)
    }

    private fun showLogin(l: PasswordStore.Login, changed: () -> Unit) {
        val pwView = TextView(this).apply {
            text = "•".repeat(l.password.length.coerceAtMost(16))
            textSize = 18f
            setTextColor(getColor(R.color.text_main))
            setPadding(0, dp(4), 0, 0)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(TextView(this@MainActivity).apply { text = "用户名"; setTextColor(getColor(R.color.text_sub)) })
            addView(TextView(this@MainActivity).apply {
                text = l.user.ifEmpty { "（无）" }; textSize = 18f; setTextColor(getColor(R.color.text_main))
                setPadding(0, dp(4), 0, dp(14))
            })
            addView(TextView(this@MainActivity).apply { text = "密码（点一下显示）"; setTextColor(getColor(R.color.text_sub)) })
            addView(pwView)
        }
        var shown = false
        pwView.setOnClickListener {
            shown = !shown
            pwView.text = if (shown) l.password else "•".repeat(l.password.length.coerceAtMost(16))
        }
        AlertDialog.Builder(this)
            .setTitle(l.host)
            .setView(box)
            .setPositiveButton("复制密码") { _, _ -> copy(l.password, "已复制密码") }
            .setNeutralButton("删除") { _, _ -> passwords.remove(l.host, l.user); changed() }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 网页里的登录表单：提交时把用户名和密码交给 App；打开已保存过的网站时自动填好 */
    private fun injectPasswordJs(view: WebView) {
        if (!passwords.enabled) return
        val host = Uri.parse(view.url ?: return).host ?: return
        view.evaluateJavascript(PWD_CAPTURE_JS, null)
        val saved = passwords.forHost(host).firstOrNull() ?: return
        val fill = PWD_FILL_JS.replace("__U__", org.json.JSONObject.quote(saved.user))
            .replace("__P__", org.json.JSONObject.quote(saved.password))
        view.evaluateJavascript(fill, null)
        // 很多登录框是页面加载完后才出现的，再补两次
        view.postDelayed({ if (Uri.parse(view.url ?: "").host == host) view.evaluateJavascript(fill, null) }, 1500)
        view.postDelayed({ if (Uri.parse(view.url ?: "").host == host) view.evaluateJavascript(fill, null) }, 4000)
    }

    private var lastPwdPrompt = ""

    inner class PwdBridge(private val wv: WebView) {
        @android.webkit.JavascriptInterface
        fun save(user: String?, pass: String?) {
            if (pass.isNullOrEmpty()) return
            runOnUiThread {
                // 网站以 WebView 当前地址为准，不信任网页自己传的
                val host = Uri.parse(wv.url ?: return@runOnUiThread).host ?: return@runOnUiThread
                val u = user.orEmpty()
                if (!passwords.enabled || passwords.isNever(host) || passwords.isSaved(host, u, pass)) return@runOnUiThread
                val key = "$host|$u|${pass.hashCode()}"
                if (key == lastPwdPrompt) return@runOnUiThread
                lastPwdPrompt = key
                val update = passwords.forHost(host).any { it.user == u }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(if (update) "更新 $host 的密码？" else "保存 $host 的密码？")
                    .setMessage(if (u.isEmpty()) "下次登录时自动填写" else "用户名：$u\n下次登录时自动填写")
                    .setPositiveButton(if (update) "更新" else "保存") { _, _ ->
                        passwords.save(host, u, pass); toast("密码已保存")
                    }
                    .setNeutralButton("此网站永不保存") { _, _ -> passwords.setNever(host) }
                    .setNegativeButton("暂不", null)
                    .show()
            }
        }
    }

    // ---------------- 返回键 ----------------

    private fun goBack() {
        when {
            webView.canGoBack() -> webView.goBack()
            webView.url != null && !onHome -> goHome() // 第一页再返回 → 回到主页
            tabs.size > 1 -> closeTab(current)
        }
    }

    private fun setupBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    onHome && homeView.editing -> homeView.exitEdit()
                    searchOverlay.visibility == View.VISIBLE -> searchOverlay.close()
                    tabTray.visibility == View.VISIBLE -> hideTabs()
                    customView != null -> {
                        customViewCallback?.onCustomViewHidden()
                        hideFullscreenVideo()
                    }
                    !onHome && (webView.canGoBack() || webView.url != null) -> goBack()
                    tabs.size > 1 -> closeTab(current)
                    else -> finish()
                }
            }
        })
    }

    // ---------------- 视频全屏 + 手势 ----------------

    private fun showFullscreenVideo(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback

        fullscreenContainer.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        val overlay = VideoGestureOverlay(this, webView, view, webView.title ?: "",
            object : VideoGestureOverlay.Callbacks {
                override fun onExit() {
                    customViewCallback?.onCustomViewHidden()
                    hideFullscreenVideo()
                }
                override fun onDownload(info: org.json.JSONObject): Boolean {
                    askNotifyPermission()
                    return StreamDownloads.start(this@MainActivity, info, webView.settings.userAgentString)
                }
            })
        fullscreenContainer.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        gestureOverlay = overlay

        fullscreenContainer.visibility = View.VISIBLE
        findViewById<View>(R.id.browserRoot).visibility = View.GONE

        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun hideFullscreenVideo() {
        if (customView == null) return
        gestureOverlay?.release()
        gestureOverlay = null
        fullscreenContainer.removeAllViews()
        fullscreenContainer.visibility = View.GONE
        findViewById<View>(R.id.browserRoot).visibility = View.VISIBLE
        customView = null
        customViewCallback = null

        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        ViewCompat.requestApplyInsets(findViewById(R.id.browserRoot))
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        window.attributes = window.attributes.apply { screenBrightness = -1f }
    }

    // ---------------- 工具 ----------------

    private fun normalizeInput(raw: String): String {
        val text = raw.trim()
        val e = ENGINES[engine] ?: ENGINES.values.first()
        if (text.isEmpty()) return e.second
        if (text.startsWith("http://") || text.startsWith("https://") ||
            text.startsWith("file://") || text.startsWith("about:")
        ) return text
        val looksLikeUrl = !text.contains(" ") && text.contains(".")
        return if (looksLikeUrl) "https://$text" else e.third + Uri.encode(text)
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(urlInput.windowToken, 0)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("open_downloads", false)) { showDownloads(); return }
        intent.dataString?.let { newTab(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList("tabs", ArrayList(tabs.map { if (it in homeTabs) HOME else it.url ?: HOME }))
        outState.putInt("current", current)
    }

    override fun onPause() {
        super.onPause()
        if (tabs.isNotEmpty()) {
            if (!onHome) captureSnapshot(webView)
            webView.onPause()
        }
    }

    override fun onResume() {
        super.onResume()
        if (tabs.isNotEmpty()) webView.onResume()
    }

    override fun onDestroy() {
        gestureOverlay?.release()
        tabs.forEach { it.destroy() }
        super.onDestroy()
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
}
