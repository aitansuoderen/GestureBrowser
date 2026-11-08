package com.example.gesturebrowser

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 普通文件下载（替代系统下载管理器）：用 SegEngine 多连接动态分段，支持暂停 / 继续 / 断点续传、
 * 应用被杀后自动接着下、失败自动重试；直接写进 下载/GestureBrowser，不用二次复制。
 */
object FileDownloads {
    enum class S { WAIT, RUN, PAUSE, DONE, FAIL }

    class Task(val id: Int) {
        var name = ""; var url = ""; var ua = ""; var referer = ""; var mime = "application/octet-stream"
        @Volatile var s = S.WAIT
        @Volatile var msg = ""
        @Volatile var uri: Uri? = null
        var state = SegEngine.State()
        @Volatile var engine: SegEngine? = null
        @Volatile var stop = false
        @Volatile var gen = 0          // 每次启动 +1，旧线程发现代数变了就自己停
        var autoRetries = 0
        val bytes = AtomicLong(0)
        @Volatile var speed = 0L
        var lastBytes = 0L; var lastTime = 0L
        val running get() = s == S.RUN || s == S.WAIT
    }

    val tasks = java.util.concurrent.CopyOnWriteArrayList<Task>()
    private val ids = AtomicInteger(1)
    private val pool = Executors.newFixedThreadPool(3)       // 同时下 3 个
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context

    private fun prefs() = app.getSharedPreferences("file_downloads", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        System.setProperty("http.keepAlive", "true")
        System.setProperty("http.maxConnections", "${SegEngine.MAX_CONNS}")
        val arr = runCatching { JSONArray(prefs().getString("tasks", "[]")) }.getOrNull() ?: JSONArray()
        var resume = false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val t = Task(ids.getAndIncrement()).apply {
                name = o.optString("name"); url = o.optString("url"); ua = o.optString("ua"); referer = o.optString("ref")
                mime = o.optString("mime", mime); uri = o.optString("uri").ifBlank { null }?.let { Uri.parse(it) }
                state = runCatching { SegEngine.State.fromJson(o.getJSONObject("st")) }.getOrDefault(SegEngine.State())
                s = runCatching { S.valueOf(o.optString("s")) }.getOrDefault(S.PAUSE)
                msg = o.optString("msg")
                bytes.set(state.doneBytes())
            }
            if (t.s == S.RUN || t.s == S.WAIT) { t.s = S.WAIT; resume = true }   // 上次被杀时还在下：自动接着下
            tasks += t
        }
        tasks.filter { it.s == S.WAIT }.forEach { launch(it) }
        if (resume) DownloadService.ensure(app)
    }

    @Synchronized private fun persist() {
        if (!::app.isInitialized) return
        val arr = JSONArray()
        tasks.forEach { t ->
            arr.put(JSONObject().put("name", t.name).put("url", t.url).put("ua", t.ua).put("ref", t.referer).put("mime", t.mime)
                .put("uri", t.uri?.toString() ?: "").put("s", t.s.name).put("msg", t.msg).put("st", t.state.toJson()))
        }
        prefs().edit().putString("tasks", arr.toString()).apply()
    }

    fun anyRunning() = tasks.any { it.running }

    fun start(ctx: Context, url: String, ua: String, referer: String, fileName: String, mime: String?) {
        init(ctx)
        val t = Task(ids.getAndIncrement()).apply {
            name = fileName; this.url = url; this.ua = ua; this.referer = referer
            if (!mime.isNullOrBlank()) this.mime = mime
            state.url = url
        }
        tasks.add(0, t)
        persist()
        launch(t)
        DownloadService.ensure(ctx)
    }

    fun pause(t: Task) { if (!t.running) return; t.stop = true; t.s = S.PAUSE; t.msg = "已暂停"; persist() }

    fun resume(ctx: Context, t: Task) {
        if (t.running || t.s == S.DONE) return
        t.autoRetries = 0
        t.s = S.WAIT; t.msg = ""; persist()
        launch(t); DownloadService.ensure(ctx)
    }

    fun remove(t: Task, deleteFile: Boolean) {
        t.stop = true
        tasks.remove(t)
        if (deleteFile || t.s != S.DONE) t.uri?.let { u -> runCatching { app.contentResolver.delete(u, null, null) } }
        persist()
    }

    private fun headers(t: Task): (String) -> Map<String, String> = { u ->
        val m = linkedMapOf("User-Agent" to t.ua.ifBlank { "Mozilla/5.0 (Linux; Android) GestureBrowser" }, "Accept" to "*/*",
            "Accept-Encoding" to "identity")
        if (t.referer.startsWith("http")) m["Referer"] = t.referer
        CookieManager.getInstance().getCookie(u)?.takeIf { it.isNotBlank() }?.let { m["Cookie"] = it }
        m
    }

    private fun online(): Boolean = runCatching {
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

    private fun launch(t: Task) {
        t.stop = false
        val g = ++t.gen
        t.s = S.WAIT; t.msg = "等待中"
        pool.execute { runTask(t, g) }
    }

    /** 重新下载：丢掉已下部分从头来 */
    fun restart(ctx: Context, t: Task) {
        t.stop = true; t.gen++
        t.uri?.let { u -> runCatching { app.contentResolver.delete(u, null, null) } }
        t.uri = null
        t.state = SegEngine.State().apply { url = t.url }
        t.bytes.set(0); t.speed = 0; t.lastTime = 0; t.autoRetries = 0
        persist()
        launch(t); DownloadService.ensure(ctx)
    }

    /** 更新链接（原链接过期时）：同一个文件大小一致就从断点接着下 */
    fun updateUrl(ctx: Context, t: Task, url: String) {
        t.stop = true; t.gen++
        t.url = url
        t.state.url = url; t.state.etag = null; t.state.lastMod = null   // 换了 CDN，ETag 可能不同，只比大小
        t.autoRetries = 0
        persist()
        if (t.s != S.DONE) { launch(t); DownloadService.ensure(ctx) }
    }

    private fun runTask(t: Task, g: Int) {
        val stopped = { t.stop || t.gen != g }
        if (stopped() || !tasks.contains(t)) return
        t.s = S.RUN; t.msg = "连接中"
        var pfd: ParcelFileDescriptor? = null
        try {
            val h = headers(t)
            val p = SegEngine.probe(t.state.url.ifBlank { t.url }, h)
            val st = t.state
            val changed = st.segs.isNotEmpty() && (p.total != st.total ||
                (st.etag != null && p.etag != null && st.etag != p.etag) ||
                (st.lastMod != null && p.lastMod != null && st.lastMod != p.lastMod))
            if (st.segs.isEmpty() || changed || t.uri == null) {
                // 新任务，或者服务器上的文件变了：从头下
                st.segs.clear()
                st.total = p.total; st.ranged = p.ranged; st.etag = p.etag; st.lastMod = p.lastMod
                if (t.mime == "application/octet-stream" && !p.mime.isNullOrBlank()) t.mime = p.mime
                t.bytes.set(0)
            }
            st.url = p.url
            if (t.uri == null) t.uri = newEntry(t.name, t.mime)
            persist()
            pfd = app.contentResolver.openFileDescriptor(t.uri!!, "rw") ?: throw IOException("无法写入下载目录")
            val ch = FileOutputStream(pfd.fileDescriptor).channel
            if (changed) runCatching { ch.truncate(0) }
            t.msg = ""
            val eng = SegEngine(h, ch, st, stopped, { n -> t.bytes.addAndGet(n) }, ::online, { persist() })
            t.engine = eng
            eng.run()
            t.engine = null
            if (stopped()) return
            if (!st.finished()) throw IOException("下载未完成")
            runCatching { ch.force(true) }
            pfd.close(); pfd = null
            app.contentResolver.update(t.uri!!, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            t.bytes.set(st.doneBytes())
            t.s = S.DONE; t.msg = "已完成"
            persist()
        } catch (e: Throwable) {
            t.engine = null
            if (stopped()) return
            val m = (e.message ?: e.javaClass.simpleName).take(60)
            if (t.autoRetries < 3 && !m.contains("失效")) {
                // 整个任务自动重试：5、15、30 秒后从断点继续
                val delay = longArrayOf(5000, 15000, 30000)[t.autoRetries++]
                t.s = S.WAIT; t.msg = "出错，${delay / 1000} 秒后自动重试：$m"
                persist()
                main.postDelayed({ if (!stopped() && tasks.contains(t) && t.s == S.WAIT) launch(t) }, delay)
            } else {
                t.s = S.FAIL; t.msg = "下载失败：$m"
                persist()
            }
        } finally {
            runCatching { pfd?.close() }
        }
    }

    private fun newEntry(name: String, mime: String): Uri {
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GestureBrowser")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw IOException("无法写入下载目录")
    }

    @Synchronized private fun sample(t: Task) {
        val now = SystemClock.elapsedRealtime(); val b = t.bytes.get()
        if (t.lastTime == 0L) { t.lastTime = now; t.lastBytes = b; return }
        val dt = now - t.lastTime
        if (dt < 700) return
        val inst = ((b - t.lastBytes) * 1000 / dt).coerceAtLeast(0)
        t.speed = if (t.speed == 0L) inst else (t.speed + inst) / 2
        t.lastTime = now; t.lastBytes = b
    }

    fun tick() = tasks.forEach { if (it.s == S.RUN) sample(it) }

    val Task.pct: Int get() = if (state.total > 0) (bytes.get() * 100 / state.total).toInt().coerceIn(0, 100) else 0

    fun subtitle(t: Task): String {
        val sz = StreamDownloads::fmtSize
        return when (t.s) {
            S.DONE -> "已完成 · ${sz(t.state.total.coerceAtLeast(t.bytes.get()))}"
            S.PAUSE -> if (t.state.total > 0) "已暂停 ${t.pct}% · ${sz(t.bytes.get())} / ${sz(t.state.total)} · 点按继续" else "已暂停 · 点按继续"
            S.FAIL -> t.msg + " · 点按重试"
            S.WAIT -> t.msg.ifBlank { "等待中" }
            S.RUN -> {
                sample(t)
                if (t.msg.isNotBlank()) return t.msg
                val total = t.state.total; val got = t.bytes.get()
                val conns = t.engine?.connections ?: 0
                val left = if (total > 0 && t.speed > 0) " · 剩余 " + leftText((total - got) / t.speed) else ""
                val head = if (total > 0) "${t.pct}% · ${sz(got)} / ${sz(total)}" else sz(got)
                "$head · ${sz(t.speed)}/s$left · $conns 线程"
            }
        }
    }

    fun leftText(sec: Long): String = when {
        sec >= 3600 -> "${sec / 3600} 小时 ${sec % 3600 / 60} 分"
        sec >= 60 -> "${sec / 60} 分 ${sec % 60} 秒"
        else -> "$sec 秒"
    }
}
