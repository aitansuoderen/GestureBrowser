package com.example.gesturebrowser

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 分段下载引擎。思路参考 FluxDown 和 Ghost Downloader 公开文档里的设计，按 Android 重新实现（未复制它们的代码）：
 *  - 探测：先请求 bytes=1-1（区分"支持分块"和"忽略 Range 返回整个文件"），不行再试 bytes=0-0；
 *  - 动态分段：开头只切几段，哪条连接先干完，就把剩余最多的那段从中间劈开接手后半段（工作窃取），不会卡在 99%；
 *  - 自动加速：速度平稳 5 秒后加 4 条连接，若速度提升够大就继续加，否则判定到顶、不再加（上限 32）；
 *  - 稳定：连接 6 秒无数据视为卡住，从断点重连（指数退避）；断网等网络回来；服务器拒绝多连接（403/429）就减连接；
 *    中途服务器不再支持分块（返回 200）自动降级为单连接重下；
 *  - 断点续传：每段的进度可存成 JSON，暂停、杀进程后接着下，续传前用 ETag / Last-Modified 校验文件没变。
 */
class SegEngine(
    private val headers: (String) -> Map<String, String>,
    private val ch: FileChannel,
    val st: State,
    private val stopped: () -> Boolean,
    private val onBytes: (Long) -> Unit,
    private val online: () -> Boolean,
    private val onSave: () -> Unit = {}
) {
    class Seg(@Volatile var pos: Long, @Volatile var end: Long) {
        @Volatile var owned = false
        fun left(): Long = if (end < 0) Long.MAX_VALUE else end - pos + 1
        /** 还有没下完的数据（end = -1 表示大小未知、未结束；-2 表示大小未知、已下完） */
        fun pending(): Boolean = end != -2L && (end < 0 || left() > 0)
    }

    class State {
        var url = ""
        var total = -1L
        var ranged = false
        var etag: String? = null
        var lastMod: String? = null
        val segs = mutableListOf<Seg>()

        @Synchronized fun doneBytes(): Long {
            if (total < 0) return segs.firstOrNull()?.pos ?: 0
            return total - segs.sumOf { if (it.end < 0) 0 else maxOf(0, it.left()) }
        }
        @Synchronized fun finished(): Boolean =
            if (total >= 0) segs.none { it.pending() } && doneBytes() >= total
            else segs.isNotEmpty() && segs.none { it.pending() }
        @Synchronized fun toJson(): JSONObject = JSONObject().put("url", url).put("total", total).put("ranged", ranged)
            .put("etag", etag ?: "").put("lm", lastMod ?: "")
            .put("segs", JSONArray().apply { segs.filter { it.pending() }.forEach { put(JSONArray().put(it.pos).put(it.end)) } })

        companion object {
            fun fromJson(o: JSONObject) = State().apply {
                url = o.optString("url"); total = o.optLong("total", -1); ranged = o.optBoolean("ranged")
                etag = o.optString("etag").ifBlank { null }; lastMod = o.optString("lm").ifBlank { null }
                val a = o.optJSONArray("segs") ?: JSONArray()
                for (i in 0 until a.length()) { val s = a.getJSONArray(i); segs += Seg(s.getLong(0), s.getLong(1)) }
                if (total > 0 && segs.isEmpty()) segs += Seg(total, total - 1)   // 已下完
            }
        }
    }

    data class Probe(val url: String, val total: Long, val ranged: Boolean, val etag: String?, val lastMod: String?,
                     val disposition: String?, val mime: String?)

    private class Throttled : IOException("服务器拒绝更多连接")
    private class Degrade : IOException("服务器不再支持分块")

    companion object {
        const val MAX_CONNS = 32
        private const val MIN_SPLIT = 128L * 1024
        /** 拒绝过多连接的站点：24 小时内只用 2 条连接 */
        private val throttledHosts = ConcurrentHashMap<String, Long>()
        fun hostThrottled(url: String): Boolean {
            val h = runCatching { URL(url).host }.getOrNull() ?: return false
            val t = throttledHosts[h] ?: return false
            return System.currentTimeMillis() - t < 24 * 3600_000L
        }
        private fun markThrottled(url: String) { runCatching { throttledHosts[URL(url).host] = System.currentTimeMillis() } }

        fun open(url: String, headers: (String) -> Map<String, String>, range: String?, readTimeout: Int = 6000): HttpURLConnection {
            var u = url
            repeat(8) {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = readTimeout
                c.instanceFollowRedirects = false
                headers(u).forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (range != null) c.setRequestProperty("Range", range)
                val code = c.responseCode
                if (code in 300..399 && code != 304) {
                    val loc = c.getHeaderField("Location"); c.disconnect()
                    if (loc.isNullOrBlank()) throw IOException("HTTP $code")
                    u = URL(URL(u), loc).toString(); return@repeat
                }
                return c
            }
            throw IOException("重定向次数过多")
        }

        private fun totalFromRange(cr: String?): Long = cr?.substringAfter('/', "")?.trim()?.toLongOrNull() ?: -1

        /** 探测文件大小和是否支持分块，失败最多重试 3 次（1、2 秒退避） */
        fun probe(url: String, headers: (String) -> Map<String, String>): Probe {
            var last: Exception? = null
            for (i in 0 until 3) {
                try { return probeOnce(url, headers) } catch (e: Exception) { last = e; Thread.sleep(1000L shl i) }
            }
            throw IOException(last?.message ?: "无法连接服务器")
        }

        private fun probeOnce(url: String, headers: (String) -> Map<String, String>): Probe {
            fun info(c: HttpURLConnection, total: Long, ranged: Boolean) = Probe(c.url.toString(), total, ranged,
                c.getHeaderField("ETag"), c.getHeaderField("Last-Modified"), c.getHeaderField("Content-Disposition"),
                c.contentType?.substringBefore(';')?.trim())
            var c = open(url, headers, "bytes=1-1", 15000)
            try {
                val code = c.responseCode
                if (code == 206) { val t = totalFromRange(c.getHeaderField("Content-Range")); if (t > 0) return info(c, t, true) }
                if (code == 416) { val t = totalFromRange(c.getHeaderField("Content-Range")); if (t > 0) return info(c, t, false) }
                if (code >= 400 && code != 416) throw IOException("HTTP $code")
            } finally { c.disconnect() }
            c = open(url, headers, "bytes=0-0", 15000)
            try {
                val code = c.responseCode
                if (code >= 400) throw IOException("HTTP $code")
                if (code == 206) { val t = totalFromRange(c.getHeaderField("Content-Range")); if (t > 0) return info(c, t, true) }
                return info(c, c.contentLengthLong, false)
            } finally { c.disconnect() }
        }
    }

    @Volatile private var failure: Throwable? = null
    @Volatile private var degrade = false
    @Volatile private var maxConns = MAX_CONNS
    private val active = AtomicInteger(0)
    val connections: Int get() = active.get()

    /** 按探测结果切分；已有分段（续传）就不动 */
    private fun plan(initial: Int) = synchronized(st) {
        st.segs.clear()
        if (!st.ranged || st.total <= 2L * 1024 * 1024) {
            st.segs += Seg(0, if (st.total > 0) st.total - 1 else -1)
            return@synchronized
        }
        val n = initial.coerceAtLeast(1)
        val size = st.total / n
        for (i in 0 until n) st.segs += Seg(i * size, if (i == n - 1) st.total - 1 else (i + 1) * size - 1)
    }

    fun run() {
        while (true) {
            degrade = false; failure = null
            val throttled = hostThrottled(st.url)
            maxConns = if (!st.ranged) 1 else if (throttled) 2 else MAX_CONNS
            val initial = if (!st.ranged) 1 else if (throttled) 2 else (st.total / (1024 * 1024)).toInt().coerceIn(2, 8)
            if (st.segs.isEmpty()) plan(initial)
            runWorkers(initial)
            if (degrade && !stopped()) {
                // 降级：单连接从头再来
                synchronized(st) { st.ranged = false; st.segs.clear() }
                runCatching { ch.truncate(0) }
                continue
            }
            failure?.let { throw it }
            return
        }
    }

    private fun runWorkers(initial: Int) {
        val exec = Executors.newCachedThreadPool()
        val fs = ConcurrentLinkedQueue<Future<*>>()
        fun spawn() {
            if (active.get() >= maxConns) return
            active.incrementAndGet()
            fs += exec.submit { try { worker() } finally { active.decrementAndGet() } }
        }
        val pending = synchronized(st) { st.segs.count { it.pending() } }
        repeat(maxOf(1, minOf(maxConns, maxOf(initial, pending)))) { spawn() }

        // 控制循环：测速、自动加连接、定时保存进度
        val samples = ArrayDeque<Long>()
        var last = st.doneBytes(); var lastSave = System.currentTimeMillis()
        var accelDone = !st.ranged; var phase = 0; var baseSpeed = 0.0; var baseConns = 0; var waitUntil = 0L
        try {
            while (true) {
                Thread.sleep(1000)
                if (failure != null || degrade || stopped()) break
                if (st.finished()) break
                val now = System.currentTimeMillis()
                if (now - lastSave > 2000) { lastSave = now; runCatching { onSave() } }
                if (active.get() == 0) {
                    val left = synchronized(st) { st.segs.any { it.pending() } }
                    if (left) spawn() else break
                    continue
                }
                val b = st.doneBytes(); samples.addLast(b - last); last = b
                while (samples.size > 5) samples.removeFirst()
                if (accelDone || samples.size < 5 || now < waitUntil) continue
                val avg = samples.average()
                if (avg <= 0 || samples.any { kotlin.math.abs(it - avg) / avg > 0.15 }) continue   // 速度不稳，先不动
                if (phase == 0) {
                    val add = minOf(4, maxConns - active.get())
                    if (add <= 0) { accelDone = true; continue }
                    baseSpeed = avg; baseConns = active.get()
                    repeat(add) { spawn() }
                    phase = 1; waitUntil = now + 5000; samples.clear()
                } else {
                    val gainS = avg / baseSpeed - 1
                    val gainC = active.get().toDouble() / baseConns - 1
                    if (gainC > 0 && gainS >= 0.8 * gainC) { phase = 0 } else accelDone = true   // 提升不够：带宽到顶，不再加
                    samples.clear()
                }
            }
        } finally {
            exec.shutdown()
            runCatching { exec.awaitTermination(20, TimeUnit.SECONDS) }
            exec.shutdownNow()
            runCatching { ch.force(false) }
            runCatching { onSave() }
        }
    }

    /** 取一段活：先拿没人管的段，没有就把剩余最多的段从中间劈开 */
    private fun take(): Seg? = synchronized(st) {
        st.segs.removeAll { it.end >= 0 && it.left() <= 0 && !it.owned }
        st.segs.firstOrNull { !it.owned && it.pending() }?.let { it.owned = true; return it }
        if (!st.ranged) return null
        val big = st.segs.filter { it.owned && it.end >= 0 }.maxByOrNull { it.left() } ?: return null
        if (big.left() < 2 * MIN_SPLIT) return null
        val mid = big.pos + big.left() / 2
        val n = Seg(mid, big.end).apply { owned = true }
        big.end = mid - 1
        st.segs += n
        n
    }

    private fun worker() {
        while (!stopped() && failure == null && !degrade) {
            val seg = take() ?: return
            try {
                download(seg)
            } catch (e: Throttled) {
                synchronized(st) { seg.owned = false }
                markThrottled(st.url)
                maxConns = maxOf(1, active.get() - 1)
                return
            } catch (e: Degrade) {
                degrade = true; return
            } catch (e: Throwable) {
                synchronized(st) { seg.owned = false }
                if (!stopped()) failure = failure ?: e
                return
            }
            synchronized(st) { seg.owned = false }
        }
    }

    private fun download(seg: Seg) {
        var attempts = 0
        val buf = ByteArray(64 * 1024)
        while (!stopped() && failure == null && !degrade) {
            if (seg.end >= 0 && seg.left() <= 0) return
            if (seg.end == -2L) return
            if (!st.ranged && seg.pos > 0) {   // 不支持分块：只能从头重下
                synchronized(st) { seg.pos = 0 }; runCatching { ch.truncate(0) }
            }
            var c: HttpURLConnection? = null
            try {
                val range = if (st.ranged) "bytes=${seg.pos}-${seg.end}" else null
                c = open(st.url, headers, range)
                val code = c.responseCode
                if ((code == 403 || code == 429) && st.ranged && active.get() > 1) throw Throttled()
                if (code >= 400) throw IOException("HTTP $code")
                if (st.ranged && code == 200) throw Degrade()
                c.inputStream.use { ins ->
                    while (true) {
                        if (stopped() || failure != null || degrade) return
                        val n = ins.read(buf)
                        if (n < 0) {
                            if (seg.end < 0) { synchronized(st) { seg.end = -2L } ; if (st.total < 0) st.total = seg.pos }
                            else if (seg.left() > 0) throw IOException("连接提前结束")
                            return
                        }
                        var wpos = 0L; var allowed = 0
                        synchronized(st) {
                            wpos = seg.pos
                            allowed = if (seg.end < 0) n else minOf(n.toLong(), maxOf(0, seg.end - seg.pos + 1)).toInt()
                        }
                        if (allowed > 0) {
                            val bb = ByteBuffer.wrap(buf, 0, allowed)
                            var p = wpos
                            while (bb.hasRemaining()) p += ch.write(bb, p)
                            synchronized(st) { seg.pos += allowed }
                            onBytes(allowed.toLong())
                            attempts = 0
                        }
                        if (seg.end >= 0 && seg.left() <= 0) return   // 这段干完了（或后半段被别的连接接走了）
                    }
                }
            } catch (e: Throttled) { c?.disconnect(); throw e
            } catch (e: Degrade) { c?.disconnect(); throw e
            } catch (e: IOException) {
                c?.disconnect()
                if (stopped()) return
                if (!online()) {        // 断网不算失败次数，最多等 3 分钟
                    val end = System.currentTimeMillis() + 180_000
                    while (!online() && System.currentTimeMillis() < end && !stopped()) Thread.sleep(2000)
                    if (!online()) throw IOException("网络已断开")
                    continue
                }
                val code = Regex("HTTP (\\d+)").find(e.message ?: "")?.groupValues?.get(1)?.toIntOrNull()
                if (code == 404 || code == 410) throw IOException("下载地址已失效（HTTP $code）")
                if (++attempts > 6) throw e
                Thread.sleep(minOf(16000L, 1000L shl (attempts - 1)))
            }
        }
    }
}
