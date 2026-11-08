package com.example.gesturebrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 浏览历史：最新的在前，最多 2000 条；同一网址再次访问时移到最前。 */
class HistoryStore(context: Context) {
    data class Entry(val title: String, val url: String, val time: Long)

    private val prefs = context.getSharedPreferences("history", Context.MODE_PRIVATE)
    private var cache: MutableList<Entry>? = null

    fun all(): List<Entry> = load()

    fun add(title: String, url: String) {
        if (!url.startsWith("http")) return
        val list = load()
        list.removeAll { it.url == url }
        list.add(0, Entry(title.ifBlank { url }, url, System.currentTimeMillis()))
        while (list.size > 2000) list.removeAt(list.size - 1)
        save(list)
    }

    fun updateTitle(url: String, title: String) {
        val list = load()
        val i = list.indexOfFirst { it.url == url }
        if (i >= 0 && title.isNotBlank() && list[i].title != title) {
            list[i] = list[i].copy(title = title)
            save(list)
        }
    }

    fun remove(url: String) = save(load().apply { removeAll { it.url == url } })

    fun clear() = save(mutableListOf())

    private fun load(): MutableList<Entry> {
        cache?.let { return it }
        val arr = JSONArray(prefs.getString("list", "[]"))
        val list = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Entry(o.optString("title"), o.optString("url"), o.optLong("time"))
        }.toMutableList()
        cache = list
        return list
    }

    private fun save(list: MutableList<Entry>) {
        cache = list
        // 序列化放到后台线程，并合并 300ms 内的多次保存，避免打开网页时主线程卡顿
        val snap = ArrayList(list)
        pending = snap
        io.schedule({
            if (pending !== snap) return@schedule
            val arr = JSONArray()
            snap.forEach { arr.put(JSONObject().put("title", it.title).put("url", it.url).put("time", it.time)) }
            prefs.edit().putString("list", arr.toString()).apply()
        }, 300, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    @Volatile private var pending: List<Entry>? = null
    private val io = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
}
