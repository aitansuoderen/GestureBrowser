package com.example.gesturebrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 高速视频下载器（不走系统 DownloadManager）：
 * - 普通视频：多连接分块（Range）并发下载；
 * - m3u8（HLS）：分片 8 线程并发，支持 AES-128 解密、fMP4/TS、字节范围，下完合成 MP4；
 * - B 站这类 DASH：视频、音频分别多连接下载，再合成一个 MP4。
 * 结果保存到 下载/GestureBrowser。
 */
object StreamDownloads {
    const val THREADS = 10
    private const val CHUNK = 2L * 1024 * 1024

    class Task(val id: Int, var title: String) {
        @Volatile var status = "准备中"
        @Volatile var running = true
        @Volatile var cancelled = false
        @Volatile var failed = false
        val doneA = AtomicInteger(0)    // 已完成的分片/分块
        val totalA = AtomicInteger(0)
        var done: Int get() = doneA.get(); set(v) { doneA.set(v) }
        var total: Int get() = totalA.get(); set(v) { totalA.set(v) }
        @Volatile var abort = false     // 某个分块彻底失败时，让其它线程尽快停下
        @Volatile var paused = false
        val stopped: Boolean get() = cancelled || paused
        var job: ((Task) -> Unit)? = null
        var work: File? = null          // 临时目录：暂停时保留，用于续传；取消/完成时删掉
        val bytes = AtomicLong(0)
        @Volatile var speed = 0L        // 字节/秒
        @Volatile var uri: Uri? = null
        @Volatile var size = 0L
        @Volatile var mime = "video/mp4"
        var lastBytes = 0L
        var lastTime = 0L
        val pct: Int get() = if (total > 0) (done * 100 / total) else 0
    }

    data class Req(val ua: String, val referer: String) {
        fun headers(url: String): Map<String, String> {
            val m = linkedMapOf("User-Agent" to ua, "Accept" to "*/*", "Accept-Encoding" to "identity")
            if (referer.startsWith("http")) {
                m["Referer"] = referer
                runCatching { val u = URL(referer); m["Origin"] = "${u.protocol}://${u.host}" + (if (u.port > 0) ":${u.port}" else "") }
            }
            CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let { m["Cookie"] = it }
            return m
        }
    }

    private val ids = AtomicInteger(1)
    val tasks = mutableListOf<Task>()        // 只在主线程改
    private val main = Handler(Looper.getMainLooper())
    private val pool: ExecutorService = Executors.newFixedThreadPool(2)
    private lateinit var app: Context

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        // HttpURLConnection 默认每个站点只保留 5 条长连接，多线程下载时多出来的会被关掉重连，调大以复用连接
        System.setProperty("http.keepAlive", "true")
        System.setProperty("http.maxConnections", "${THREADS * 2}")
        // 上次进程留下的临时分片（任务不跨进程保存）清掉，免得占空间
        Thread { runCatching { File(app.cacheDir, "stream").deleteRecursively() } }.start()
        // 恢复已完成的记录
        val arr = runCatching { JSONArray(prefs().getString("done", "[]")) }.getOrNull() ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            tasks += Task(ids.getAndIncrement(), o.optString("t")).apply {
                running = false; status = "已完成"; uri = Uri.parse(o.optString("u")); size = o.optLong("s")
                mime = o.optString("m", "video/mp4"); done = 1; total = 1
            }
        }
    }

    private fun prefs() = app.getSharedPreferences("stream_downloads", Context.MODE_PRIVATE)

    private fun persist() {
        val arr = JSONArray()
        tasks.filter { !it.running && !it.failed && !it.cancelled && it.uri != null }.forEach {
            arr.put(JSONObject().put("t", it.title).put("u", it.uri.toString()).put("s", it.size).put("m", it.mime))
        }
        prefs().edit().putString("done", arr.toString()).apply()
    }

    fun remove(t: Task, deleteFile: Boolean) {
        t.cancelled = true
        tasks.remove(t)
        if (!t.running) cleanWork(t)      // 正在下的，等线程停下后在 run() 里清
        if (deleteFile) t.uri?.let { u -> runCatching { app.contentResolver.delete(u, null, null) } }
        persist()
    }

    fun anyRunning() = tasks.any { it.running }

    // ---------------------------------------------------------------- 入口

    /** info：网页里 __gb3.src() 返回的 JSON；返回 false 表示没找到可下载的视频源 */
    fun start(ctx: Context, info: JSONObject, ua: String): Boolean {
        init(ctx)
        val page = info.optString("page")
        val title = safeName(info.optString("title").ifBlank { "视频" })
        val req = Req(ua, page)
        val src = info.optString("src")
        val hls = info.optJSONArray("hls")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        val dash = info.optJSONObject("dash")
        val job: (Task) -> Unit = when {
            src.startsWith("http") && !src.contains(".m3u8", true) -> { t -> direct(t, src, req) }
            src.startsWith("http") -> { t -> hls(t, listOf(src), req) }
            dash != null && dash.optString("v").startsWith("http") -> { t -> dash(t, dash.optString("v"), dash.optString("a"), req) }
            hls.isNotEmpty() -> { t -> hls(t, hls, req) }
            else -> return false
        }
        val t = Task(ids.getAndIncrement(), title)
        t.job = job
        tasks.add(0, t)
        DownloadService.ensure(ctx)
        pool.execute { run(t) }
        return true
    }

    private fun run(t: Task) {
        t.doneA.set(0); t.totalA.set(0)   // 续传时已下的分片会重新计入
        try {
            t.job!!(t)
        } catch (e: Throwable) {
            when {
                t.cancelled -> {}
                t.paused -> t.status = "已暂停"
                else -> { t.failed = true; t.status = "下载失败：" + (e.message ?: e.javaClass.simpleName).take(60) }
            }
        } finally {
            t.running = false
            if (t.cancelled) cleanWork(t)
            main.post { persist() }
        }
    }

    private fun cleanWork(t: Task) { val w = t.work ?: return; t.work = null; Thread { runCatching { w.deleteRecursively() } }.start() }

    fun pause(t: Task) { if (!t.running) return; t.paused = true; t.status = "暂停中…" }

    /** 暂停或失败后继续：已下好的分片 / 分块会跳过 */
    fun resume(ctx: Context, t: Task) {
        if (t.running || t.cancelled || t.job == null || t.uri != null) return
        t.paused = false; t.abort = false; t.failed = false; t.running = true; t.status = "准备中"
        DownloadService.ensure(ctx)
        pool.execute { run(t) }
    }

    /** 重新下载：清掉已下的分片从头来 */
    fun restart(ctx: Context, t: Task) {
        if (t.job == null || t.cancelled) return
        if (t.running) { pause(t); main.postDelayed({ restart(ctx, t) }, 500); return }
        cleanWork(t)
        t.uri = null; t.bytes.set(0); t.speed = 0; t.lastTime = 0
        t.paused = false; t.abort = false; t.failed = false; t.running = true; t.status = "准备中"
        persist()
        DownloadService.ensure(ctx)
        pool.execute { run(t) }
    }

    fun canResume(t: Task) = !t.running && !t.cancelled && t.job != null && t.uri == null && (t.paused || t.failed)

    /** 直接给一个普通视频地址（网页下载按钮等） */
    fun startDirect(ctx: Context, url: String, ua: String, referer: String, title: String) {
        val o = JSONObject().put("src", url).put("page", referer).put("title", title)
        start(ctx, o, ua)
    }

    // ---------------------------------------------------------------- 普通文件：多连接分块

    private fun direct(t: Task, url: String, req: Req) {
        val work = workDir(t)
        val ext = url.substringBefore('?').substringAfterLast('.', "mp4").lowercase().takeIf { it.length in 2..4 } ?: "mp4"
        val f = File(work, "video.$ext")
        t.status = "下载中"
        parallelFile(t, url, req, f, countParts = true)
        check(t)
        val mime = when (ext) { "webm" -> "video/webm"; "mkv" -> "video/x-matroska"; "ts" -> "video/mp2t"; else -> "video/mp4" }
        publishFiles(t, listOf(f), "${t.title}.$ext", mime)
        work.deleteRecursively()
    }

    /** 先探测是否支持 Range，支持就切块并发，否则单连接 */
    private fun parallelFile(t: Task, url: String, req: Req, out: File, countParts: Boolean) {
        // 用动态分段引擎：工作窃取、自动加连接、卡住重连、断网等待
        val p = SegEngine.probe(url) { u -> req.headers(u) }
        val stFile = File(out.path + ".st")
        val old = if (stFile.exists() && out.exists()) runCatching { SegEngine.State.fromJson(JSONObject(stFile.readText())) }.getOrNull() else null
        val same = old != null && old.total == p.total && old.ranged && p.ranged &&
            (old.etag == null || p.etag == null || old.etag == p.etag) && (old.lastMod == null || p.lastMod == null || old.lastMod == p.lastMod)
        val st = if (same) old!!.also { it.url = p.url } else {
            out.delete()
            SegEngine.State().apply { this.url = p.url; total = p.total; ranged = p.ranged; etag = p.etag; lastMod = p.lastMod }
        }
        val units = 1000
        if (countParts) t.totalA.addAndGet(units)
        var shown = 0
        val got = java.util.concurrent.atomic.AtomicLong(if (same) st.doneBytes() else 0)
        if (countParts && p.total > 0 && got.get() > 0) { shown = (got.get() * units / p.total).toInt().coerceAtMost(units - 1); t.doneA.addAndGet(shown) }
        RandomAccessFile(out, "rw").use { raf ->
            val eng = SegEngine({ u -> req.headers(u) }, raf.channel, st, { t.stopped || t.abort },
                { n ->
                    t.bytes.addAndGet(n)
                    val g = got.addAndGet(n)
                    if (countParts && p.total > 0) synchronized(st) {
                        val u = (g * units / p.total).toInt().coerceAtMost(units - 1)
                        if (u > shown) { t.doneA.addAndGet(u - shown); shown = u }
                    }
                }, ::online, { runCatching { stFile.writeText(st.toJson().toString()) } })
            eng.run()
        }
        check(t)
        if (!st.finished()) throw IOException("下载未完成")
        stFile.delete()
        if (countParts) t.doneA.addAndGet(units - shown)
    }

    @Suppress("unused")
    private fun parallelFileOld(t: Task, url: String, req: Req, out: File, countParts: Boolean) {
        val (len, ranged, finalUrl) = probe(url, req)
        if (!ranged || len <= CHUNK) {
            if (countParts) t.totalA.incrementAndGet()
            out.outputStream().use { os -> stream(t, finalUrl, req, null) { b, n -> os.write(b, 0, n) } }
            if (countParts) t.doneA.incrementAndGet()
            return
        }
        RandomAccessFile(out, "rw").use { it.setLength(len) }
        val parts = ((len + CHUNK - 1) / CHUNK).toInt()
        if (countParts) t.totalA.addAndGet(parts)
        val exec = Executors.newFixedThreadPool(THREADS)
        try {
            val fs = (0 until parts).map { i ->
                exec.submit {
                    val from = i * CHUNK
                    val to = minOf(len, from + CHUNK) - 1
                    var pos = from               // 断了就从断点继续，不重下整块
                    retry(t) {
                        if (pos <= to) RandomAccessFile(out, "rw").use { raf ->
                            raf.seek(pos)
                            stream(t, finalUrl, req, "bytes=$pos-$to") { b, n -> raf.write(b, 0, n); pos += n }
                        }
                        if (pos != to + 1) throw IOException("分块不完整")
                    }
                    if (countParts) t.doneA.incrementAndGet()
                }
            }
            waitAll(t, fs)
        } finally { exec.shutdownNow() }
    }

    private data class Probe(val len: Long, val ranged: Boolean, val url: String)

    private fun probe(url: String, req: Req): Probe {
        val c = open(url, req, "bytes=0-0")
        try {
            val code = c.responseCode
            val finalUrl = c.url.toString()
            if (code == 206) {
                val total = c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1
                return Probe(total, total > 0, finalUrl)
            }
            return Probe(c.contentLengthLong, false, finalUrl)
        } finally { c.disconnect() }
    }

    // ---------------------------------------------------------------- DASH（B 站等）

    private fun dash(t: Task, v: String, a: String, req: Req) {
        val work = workDir(t)
        val vf = File(work, "v.m4s"); val af = File(work, "a.m4s")
        t.status = "下载中"
        val exec = Executors.newFixedThreadPool(2)
        try {
            val fs = mutableListOf<Future<*>>(exec.submit { parallelFile(t, v, req, vf, countParts = true) })
            if (a.startsWith("http")) fs += exec.submit { parallelFile(t, a, req, af, countParts = true) }
            waitAll(t, fs)
        } finally { exec.shutdownNow() }
        check(t)
        val inputs = if (af.exists() && af.length() > 0) listOf(listOf(vf), listOf(af)) else listOf(listOf(vf))
        if (!muxToStore(t, inputs, "${t.title}.mp4")) publishFiles(t, listOf(vf), "${t.title}.mp4", "video/mp4")
        work.deleteRecursively()
    }

    // ---------------------------------------------------------------- HLS

    private class Seg(val url: String, val key: Key?, val seq: Long, val range: LongRange?)
    private class Key(val method: String, val uri: String?, val iv: ByteArray?)
    private class Media(val segs: List<Seg>, val init: Seg?, val live: Boolean)

    private fun hls(t: Task, candidates: List<String>, req: Req) {
        t.status = "解析中"
        // 找主播放列表（含多个清晰度）；找不到就用最后一个分片列表
        var media: Pair<String, String>? = null
        var audioUrl: String? = null
        for (u in candidates.take(8)) {
            val text = runCatching { fetchText(u, req) }.getOrNull() ?: continue
            if (!text.trimStart().startsWith("#EXTM3U")) continue
            if (text.contains("#EXT-X-STREAM-INF")) {
                val pick = bestVariant(u, text)
                media = pick.first to fetchText(pick.first, req)
                audioUrl = pick.second
                break
            }
            media = u to text
        }
        val (mUrl, mText) = media ?: throw IOException("没找到视频的播放列表")
        val video = parseMedia(mUrl, mText)
        if (video.live) throw IOException("这是直播流，无法下载")
        val audio = audioUrl?.let { au -> parseMedia(au, fetchText(au, req)) }
        listOfNotNull(video, audio).flatMap { it.segs }.forEach {
            val m = it.key?.method ?: return@forEach
            if (m != "NONE" && m != "AES-128") throw IOException("这个视频有版权加密（$m），无法下载")
        }
        val work = workDir(t)
        t.total = video.segs.size + (audio?.segs?.size ?: 0)
        t.status = "下载中"
        val vParts = downloadSegments(t, video, req, File(work, "v"))
        val aParts = audio?.let { downloadSegments(t, it, req, File(work, "a")) }
        check(t)
        when {
            // fMP4 分片且音视频在一起：init+分片本身就是能播的 MP4，直接写一遍，不用再合成
            video.init != null && aParts == null -> publishFiles(t, vParts, "${t.title}.mp4", "video/mp4")
            muxToStore(t, listOfNotNull(vParts, aParts), "${t.title}.mp4") -> {}
            video.init != null -> publishFiles(t, vParts, "${t.title}.mp4", "video/mp4")
            else -> publishFiles(t, vParts, "${t.title}.ts", "video/mp2t")
        }
        work.deleteRecursively()
    }

    /** 选码率最高的清晰度；返回（视频列表地址，独立音轨地址） */
    private fun bestVariant(base: String, text: String): Pair<String, String?> {
        val lines = text.lines().map { it.trim() }
        var best: String? = null; var bestBw = -1L; var bestAudio: String? = null
        val audioGroups = mutableMapOf<String, String>()
        for (l in lines) if (l.startsWith("#EXT-X-MEDIA:") && attr(l, "TYPE") == "AUDIO") {
            val g = attr(l, "GROUP-ID") ?: continue
            val uri = attr(l, "URI") ?: continue
            if (!audioGroups.containsKey(g) || attr(l, "DEFAULT") == "YES") audioGroups[g] = resolve(base, uri)
        }
        for (i in lines.indices) {
            val l = lines[i]
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue
            val next = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            val bw = attr(l, "BANDWIDTH")?.toLongOrNull() ?: 0
            val res = attr(l, "RESOLUTION")?.split('x')?.let { (it.getOrNull(0)?.toLongOrNull() ?: 0) * (it.getOrNull(1)?.toLongOrNull() ?: 0) } ?: 0
            val score = res * 1000 + bw / 1000
            if (score > bestBw) { bestBw = score; best = resolve(base, next); bestAudio = attr(l, "AUDIO")?.let { audioGroups[it] } }
        }
        return (best ?: throw IOException("播放列表里没有清晰度")) to bestAudio
    }

    private fun parseMedia(base: String, text: String): Media {
        val segs = mutableListOf<Seg>()
        var key: Key? = null
        var seq = 0L
        var init: Seg? = null
        var rangeSpec: String? = null
        val lastEnd = mutableMapOf<String, Long>()
        for (raw in text.lines()) {
            val l = raw.trim()
            when {
                l.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = l.substringAfter(':').trim().toLongOrNull() ?: 0
                l.startsWith("#EXT-X-KEY:") -> {
                    val m = attr(l, "METHOD") ?: "NONE"
                    key = if (m == "NONE") null else Key(m, attr(l, "URI")?.let { resolve(base, it) }, attr(l, "IV")?.let { hex(it) })
                }
                l.startsWith("#EXT-X-MAP:") -> {
                    val u = resolve(base, attr(l, "URI") ?: continue)
                    init = Seg(u, key, 0, attr(l, "BYTERANGE")?.let { range(it, u, lastEnd) })
                }
                l.startsWith("#EXT-X-BYTERANGE:") -> rangeSpec = l.substringAfter(':')
                l.isNotEmpty() && !l.startsWith("#") -> {
                    val u = resolve(base, l)
                    val r = rangeSpec?.let { range(it, u, lastEnd) }
                    rangeSpec = null
                    segs += Seg(u, key, seq++, r)
                }
            }
        }
        return Media(segs, init, !text.contains("#EXT-X-ENDLIST"))
    }

    private fun range(spec: String, url: String, lastEnd: MutableMap<String, Long>): LongRange {
        val s = spec.trim().trim('"')
        val len = s.substringBefore('@').toLong()
        val off = if (s.contains('@')) s.substringAfter('@').toLong() else (lastEnd[url] ?: 0L)
        lastEnd[url] = off + len
        return off until off + len
    }

    /** 下载所有分片到 dir，返回按顺序排列的文件（含 init） */
    private fun downloadSegments(t: Task, m: Media, req: Req, dir: File): List<File> {
        dir.mkdirs()
        val keys = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        fun keyBytes(k: Key): ByteArray = keys.getOrPut(k.uri ?: "") { fetchBytes(k.uri ?: throw IOException("缺少密钥"), req, null).copyOf(16) }
        fun fetchSeg(s: Seg): ByteArray {
            var b = fetchBytes(s.url, req, s.range?.let { "bytes=${it.first}-${it.last}" }, t)
            val k = s.key
            if (k != null && k.method == "AES-128") {
                val iv = k.iv ?: ByteArray(16).also { iv -> var x = s.seq; for (i in 15 downTo 8) { iv[i] = (x and 0xff).toByte(); x = x shr 8 } }
                val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes(k), "AES"), IvParameterSpec(iv))
                b = c.doFinal(b)
            }
            return b
        }
        val exec = Executors.newFixedThreadPool(THREADS)
        try {
            val fs = m.segs.mapIndexed { i, s ->
                exec.submit {
                    val f = File(dir, "%06d".format(i))
                    if (!f.exists()) {
                        val data = retry(t) { fetchSeg(s) }
                        val tmp = File(dir, "%06d.part".format(i)); tmp.writeBytes(data); tmp.renameTo(f)
                    }
                    t.doneA.incrementAndGet()
                }
            }
            waitAll(t, fs)
        } finally { exec.shutdownNow() }
        val files = mutableListOf<File>()
        m.init?.let { s0 -> val f = File(dir, "init"); f.writeBytes(retry(t) { fetchSeg(Seg(s0.url, null, 0, s0.range)) }); files += f }
        for (i in m.segs.indices) files += File(dir, "%06d".format(i))
        return files
    }

    // ---------------------------------------------------------------- 合成 MP4（直接写进下载目录，只写一遍）

    /** 把多个文件当作一个连续文件读（分片不用先拼接） */
    private class Concat(files: List<File>) : android.media.MediaDataSource() {
        private val fs = files.map { RandomAccessFile(it, "r") }
        private val starts = LongArray(fs.size)
        private val total: Long
        private val blk = ByteArray(512 * 1024)
        private var blkFile = -1; private var blkOff = 0L; private var blkLen = 0
        init { var o = 0L; fs.forEachIndexed { i, f -> starts[i] = o; o += f.length() }; total = o }
        override fun getSize() = total
        @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= total) return -1
            var pos = position; var off = offset; var left = size; var got = 0
            while (left > 0 && pos < total) {
                var i = java.util.Arrays.binarySearch(starts, pos); if (i < 0) i = -i - 2
                while (i + 1 < starts.size && starts[i + 1] <= pos) i++
                val local = pos - starts[i]
                if (blkFile != i || local < blkOff || local >= blkOff + blkLen) {
                    val f = fs[i]; f.seek(local)
                    val n = f.read(blk, 0, blk.size); if (n <= 0) break
                    blkFile = i; blkOff = local; blkLen = n
                }
                val inBlk = (local - blkOff).toInt()
                val n = minOf(left, blkLen - inBlk)
                System.arraycopy(blk, inBlk, buffer, off, n)
                pos += n; off += n; left -= n; got += n
            }
            return if (got == 0) -1 else got
        }
        override fun close() { fs.forEach { runCatching { it.close() } } }
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

    private fun finish(t: Task, uri: Uri, mime: String) {
        val cr = app.contentResolver
        cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        t.size = runCatching { cr.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: 0
        t.uri = uri; t.mime = mime
        t.total = t.total.coerceAtLeast(1); t.done = t.total
        t.status = "已完成"
    }

    /** 每个输入是一组按顺序的文件（ts 分片 / fmp4 init+分片 / m4s）；合成后直接写进下载目录。失败返回 false */
    private fun muxToStore(t: Task, inputs: List<List<File>>, name: String): Boolean {
        check(t)
        t.status = "合成中"
        val uri = runCatching { newEntry(name, "video/mp4") }.getOrNull() ?: return false
        val exs = mutableListOf<MediaExtractor>(); val dss = mutableListOf<Concat>()
        var muxer: MediaMuxer? = null
        var pfd: android.os.ParcelFileDescriptor? = null
        var ok = false
        try {
            pfd = app.contentResolver.openFileDescriptor(uri, "rw") ?: throw IOException("无法打开")
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            class Src(val ex: MediaExtractor, val map: Map<Int, Int>)
            val srcs = mutableListOf<Src>()
            var maxIn = 1 shl 20
            var hasVideo = false; var hasAudio = false; var dur = 0L
            for (files in inputs) {
                val ds = Concat(files); dss += ds
                val ex = MediaExtractor(); exs += ex
                ex.setDataSource(ds)
                val map = mutableMapOf<Int, Int>()
                for (i in 0 until ex.trackCount) {
                    val fmt = ex.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                    val isV = mime.startsWith("video/"); val isA = mime.startsWith("audio/")
                    if ((isV && hasVideo) || (isA && hasAudio) || (!isV && !isA)) continue
                    map[i] = muxer.addTrack(fmt)
                    ex.selectTrack(i)
                    if (isV) hasVideo = true else hasAudio = true
                    if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxIn = maxOf(maxIn, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    if (fmt.containsKey(MediaFormat.KEY_DURATION)) dur = maxOf(dur, fmt.getLong(MediaFormat.KEY_DURATION))
                }
                if (map.isNotEmpty()) srcs += Src(ex, map)
            }
            if (!hasVideo) throw IOException("没有视频轨")
            val base = srcs.minOf { it.ex.sampleTime.coerceAtLeast(0) }
            muxer.start()
            val buf = ByteBuffer.allocateDirect(maxOf(maxIn, 4 shl 20))
            val info = MediaCodec.BufferInfo()
            val next = LongArray(srcs.size) { srcs[it].ex.sampleTime }
            val lastPts = HashMap<Int, Long>()
            var count = 0
            while (true) {
                var k = -1
                for (i in next.indices) if (next[i] >= 0 && (k < 0 || next[i] < next[k])) k = i
                if (k < 0) break
                val s = srcs[k]; val ex = s.ex
                val dst = s.map[ex.sampleTrackIndex]
                if (dst != null) {
                    val n = ex.readSampleData(buf, 0)
                    if (n >= 0) {
                        var pts = (next[k] - base).coerceAtLeast(0)
                        val lp = lastPts[dst]
                        if (lp != null && pts < lp - 10_000_000) pts = lp + 1
                        lastPts[dst] = pts
                        info.set(0, n, pts, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(dst, buf, info)
                        if (++count and 255 == 0) {
                            if (t.stopped) throw IOException("已取消")
                            if (dur > 0) t.status = "合成中 ${(pts * 100 / dur).coerceIn(0, 99)}%"
                        }
                    }
                }
                ex.advance()
                next[k] = ex.sampleTime
            }
            muxer.stop()
            ok = true
        } catch (e: Throwable) {
            if (t.stopped) { runCatching { app.contentResolver.delete(uri, null, null) }; throw e }
        } finally {
            runCatching { muxer?.release() }
            exs.forEach { runCatching { it.release() } }
            dss.forEach { it.close() }
            runCatching { pfd?.close() }
        }
        if (!ok) { runCatching { app.contentResolver.delete(uri, null, null) }; return false }
        finish(t, uri, "video/mp4")
        return true
    }

    /** 不合成时：把文件按顺序直接写进下载目录（一次写入） */
    private fun publishFiles(t: Task, files: List<File>, name: String, mime: String) {
        check(t)
        t.status = "保存中"
        val uri = newEntry(name, mime)
        app.contentResolver.openOutputStream(uri)!!.use { os ->
            val b = ByteArray(1 shl 20)
            for (f in files) f.inputStream().use { ins -> while (true) { val n = ins.read(b); if (n < 0) break; os.write(b, 0, n) } }
        }
        finish(t, uri, mime)
    }

    // ---------------------------------------------------------------- 网络工具

    private fun open(url: String, req: Req, range: String?): HttpURLConnection {
        var u = url
        repeat(6) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 12000; c.readTimeout = 15000   // 卡住的连接 15 秒没数据就换一条重来
            c.instanceFollowRedirects = false
            req.headers(u).forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (range != null) c.setRequestProperty("Range", range)
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location"); c.disconnect()
                u = URL(URL(u), loc).toString(); return@repeat
            }
            return c
        }
        throw IOException("重定向次数过多")
    }

    private inline fun stream(t: Task, url: String, req: Req, range: String?, sink: (ByteArray, Int) -> Unit) {
        val c = open(url, req, range)
        try {
            val code = c.responseCode
            if (code >= 400) throw IOException("HTTP $code")
            if (range != null && code != 206) throw IOException("服务器不支持分块")
            c.inputStream.use { ins ->
                val b = ByteArray(64 * 1024)
                while (true) {
                    if (t.stopped || t.abort) throw IOException("已取消")
                    val n = ins.read(b); if (n < 0) break
                    sink(b, n); t.bytes.addAndGet(n.toLong())
                }
            }
        } catch (e: IOException) { c.disconnect(); throw e }   // 正常读完不 disconnect，连接放回池里复用（省掉每块一次握手）
    }

    private fun fetchBytes(url: String, req: Req, range: String?, t: Task? = null): ByteArray {
        val c = open(url, req, range)
        try {
            val code = c.responseCode
            if (code >= 400) { c.errorStream?.close(); throw IOException("HTTP $code") }
            val bytes = c.inputStream.use { ins ->
                val bo = java.io.ByteArrayOutputStream(maxOf(64 * 1024, c.contentLength))
                val b = ByteArray(64 * 1024)
                while (true) {
                    if (t != null && (t.stopped || t.abort)) throw IOException("已取消")
                    val n = ins.read(b); if (n < 0) break
                    bo.write(b, 0, n); t?.bytes?.addAndGet(n.toLong())   // m3u8 分片也计入网速
                }
                bo.toByteArray()
            }
            return if (range != null && code == 200) {
                val r = range.removePrefix("bytes=").split('-'); val a = r[0].toInt(); val b = r[1].toInt()
                bytes.copyOfRange(a, minOf(bytes.size, b + 1))
            } else bytes
        } catch (e: IOException) { c.disconnect(); throw e }
    }

    private fun fetchText(url: String, req: Req) = String(fetchBytes(url, req, null), Charsets.UTF_8)

    private fun <T> retry(t: Task, block: () -> T): T {
        var last: Throwable? = null
        var n = 0
        while (n < 8) {
            if (t.stopped || t.abort) throw IOException("已取消")
            try { return block() } catch (e: Throwable) {
                last = e
                if (t.stopped || t.abort) throw IOException("已取消")
                if (!online()) { waitOnline(t); continue }    // 断网不算失败次数，等网络回来接着下
                val code = Regex("HTTP (\\d+)").find(e.message ?: "")?.groupValues?.get(1)?.toIntOrNull()
                if (code == 404 || code == 410) break           // 地址失效，重试也没用
                Thread.sleep(minOf(8000L, 500L shl n))          // 0.5、1、2、4、8 秒退避
                n++
            }
        }
        throw IOException(last?.message ?: "网络错误")
    }

    private fun online(): Boolean = runCatching {
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

    /** 断网时最多等 3 分钟，期间状态显示“等待网络” */
    private fun waitOnline(t: Task) {
        val old = t.status
        if (old == "下载中") t.status = "等待网络…"
        val end = System.currentTimeMillis() + 180_000
        while (!online() && System.currentTimeMillis() < end) {
            if (t.stopped || t.abort) throw IOException("已取消")
            Thread.sleep(2000)
        }
        if (t.status == "等待网络…") t.status = old
        if (!online()) throw IOException("网络已断开")
    }

    private fun waitAll(t: Task, fs: List<Future<*>>) {
        try { fs.forEach { it.get() } }
        catch (e: java.util.concurrent.ExecutionException) { t.abort = true; throw e.cause ?: e }
    }

    private fun check(t: Task) { if (t.stopped) throw IOException("已取消") }

    private fun workDir(t: Task): File {
        val w = t.work ?: File(app.cacheDir, "stream/${t.id}-${System.currentTimeMillis()}").also { t.work = it }
        return w.apply { mkdirs() }
    }

    private fun attr(line: String, name: String): String? {
        val m = Regex("(?:^|[:,])$name=(\"[^\"]*\"|[^,]*)").find(line) ?: return null
        return m.groupValues[1].trim('"')
    }
    private fun resolve(base: String, rel: String) = URL(URL(base), rel.trim()).toString()
    private fun hex(s: String): ByteArray {
        val h = s.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        return ByteArray(16) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    private fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").trim().take(80).ifBlank { "视频" }

    fun fmtSize(b: Long): String = when {
        b >= 1L shl 30 -> "%.2f GB".format(b / 1073741824.0)
        b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
        b >= 1024 -> "%d KB".format(b / 1024)
        else -> "$b B"
    }

    fun subtitle(t: Task): String = when {
        t.paused && !t.running -> "已暂停 ${t.pct}% · ${fmtSize(t.bytes.get())} · 点按继续"
        t.failed -> t.status + " · 点按重试"
        t.cancelled -> t.status
        !t.running -> "已完成 · ${fmtSize(t.size)}"
        t.status == "下载中" -> { sample(t); "下载中 ${t.pct}% · ${fmtSize(t.bytes.get())} · ${fmtSize(t.speed)}/s" }
        t.status.startsWith("解析") || t.status.startsWith("准备") -> { sample(t); if (t.bytes.get() > 0) "${t.status} · ${fmtSize(t.speed)}/s" else t.status }
        else -> t.status
    }

    /** 按真实经过时间算网速（平滑），下载页和通知栏谁先调用都行，不依赖后台服务 */
    @Synchronized fun sample(t: Task) {
        val now = android.os.SystemClock.elapsedRealtime()
        val b = t.bytes.get()
        if (t.lastTime == 0L) { t.lastTime = now; t.lastBytes = b; return }
        val dt = now - t.lastTime
        if (dt < 700) return
        val inst = (b - t.lastBytes) * 1000 / dt
        t.speed = if (t.speed == 0L) inst else (t.speed * 0.5 + inst * 0.5).toLong()
        t.lastTime = now; t.lastBytes = b
    }

    fun tick() { tasks.forEach { if (it.running) sample(it) } }
}

/** 前台服务：下载时保持进程存活，并在通知栏显示进度 */
class DownloadService : Service() {
    companion object {
        private const val CH = "downloads"
        private const val NID = 4401
        fun ensure(ctx: Context) {
            val i = Intent(ctx, DownloadService::class.java)
            runCatching { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) }
        }
    }
    private val h = Handler(Looper.getMainLooper())
    private val loop = object : Runnable {
        override fun run() {
            StreamDownloads.tick(); FileDownloads.tick()
            val nm = getSystemService(NotificationManager::class.java)
            if (!StreamDownloads.anyRunning() && !FileDownloads.anyRunning()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                val f = FileDownloads.tasks.firstOrNull()
                val last = StreamDownloads.tasks.firstOrNull()
                if (f != null && f.s == FileDownloads.S.DONE) nm.notify(NID + 1, build(f.name, FileDownloads.subtitle(f), -1))
                else if (last != null) nm.notify(NID + 1, build(last.title, StreamDownloads.subtitle(last), -1))
                stopSelf(); return
            }
            val t = StreamDownloads.tasks.firstOrNull { it.running }
            if (t != null) nm.notify(NID, build(t.title, StreamDownloads.subtitle(t), t.pct))
            else FileDownloads.tasks.firstOrNull { it.running }?.let { f ->
                val pct = with(FileDownloads) { f.pct }
                nm.notify(NID, build(f.name, FileDownloads.subtitle(f), pct))
            }
            h.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "下载", NotificationManager.IMPORTANCE_LOW))
        val n = build("正在下载视频", "准备中", 0)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NID, n)
        h.removeCallbacks(loop); h.post(loop)
        return START_NOT_STICKY
    }

    override fun onDestroy() { h.removeCallbacks(loop); super.onDestroy() }

    private fun build(title: String, text: String, pct: Int): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("open_downloads", true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title).setContentText(text)
            .setOnlyAlertOnce(true).setContentIntent(pi)
            .apply { if (pct >= 0) setProgress(100, pct, pct == 0).setOngoing(true) else setSmallIcon(android.R.drawable.stat_sys_download_done).setAutoCancel(true) }
            .build()
    }
}
