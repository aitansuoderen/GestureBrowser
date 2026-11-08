package com.example.gesturebrowser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 网站图标加载：内存 + 磁盘缓存，后台下载。
 * 先试 DuckDuckGo 图标服务，再试站点自己的 /favicon.ico；都失败就画一个"彩色方块 + 首字母"的图标（同 Firefox）。
 */
class IconLoader(context: Context) {

    private val dir = File(context.cacheDir, "icons").apply { mkdirs() }
    private val mem = LruCache<String, Bitmap>(128)
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun load(url: String, label: String, into: ImageView) {
        val host = Uri.parse(url).host ?: url
        into.tag = host
        mem.get(host)?.let { into.setImageBitmap(it); return }
        val lk = "L:$host:$label"
        into.setImageBitmap(mem.get(lk) ?: letterIcon(host, label).also { mem.put(lk, it) })
        pool.execute {
            val bmp = fromDisk(host) ?: download(host)?.also { save(host, it) }
            if (bmp != null) {
                mem.put(host, bmp)
                main.post { if (into.tag == host) into.setImageBitmap(bmp) }
            }
        }
    }

    private fun fromDisk(host: String): Bitmap? {
        val f = File(dir, "$host.png")
        return if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    }

    private fun save(host: String, bmp: Bitmap) = runCatching {
        File(dir, "$host.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun download(host: String): Bitmap? {
        val candidates = listOf(
            "https://icons.duckduckgo.com/ip3/$host.ico",
            "https://$host/apple-touch-icon.png",
            "https://$host/favicon.ico"
        )
        for (c in candidates) {
            val bmp = runCatching {
                val conn = URL(c).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Mobile")
                if (conn.responseCode != 200) null
                else conn.inputStream.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (bmp != null && bmp.width >= 16) return bmp
        }
        return null
    }

    private val palette = intArrayOf(
        0xFF2E7D32.toInt(), 0xFF1565C0.toInt(), 0xFF6A1B9A.toInt(), 0xFFAD1457.toInt(),
        0xFFEF6C00.toInt(), 0xFF00838F.toInt(), 0xFF37355A.toInt(), 0xFF4E342E.toInt()
    )

    fun letterIcon(host: String, label: String): Bitmap {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = palette[(host.hashCode() and 0x7fffffff) % palette.size]
        c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 14f, 14f, p)
        p.color = Color.WHITE
        p.textSize = 60f
        p.textAlign = Paint.Align.CENTER
        val ch = (label.trim().firstOrNull() ?: host.removePrefix("www.").firstOrNull() ?: '?')
            .uppercaseChar().toString()
        val y = size / 2f - (p.descent() + p.ascent()) / 2
        c.drawText(ch, size / 2f, y, p)
        return bmp
    }
}
