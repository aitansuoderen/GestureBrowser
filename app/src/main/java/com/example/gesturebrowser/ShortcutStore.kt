package com.example.gesturebrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 主页网站列表（标题, 网址）。
 * key = "common"：常用网站，首次启动带一组默认网站；key = "custom"：自定义网站，用户自己添加。
 */
class ShortcutStore(context: Context, private val key: String = "custom") {
    private val prefs = context.getSharedPreferences("shortcuts", Context.MODE_PRIVATE)

    private val defaults: List<Pair<String, String>> = if (key == "common") DEFAULT_COMMON else emptyList()

    init {
        // 2.6 起只保留常用网站：把旧版快捷方式和自定义网站里的网站并入常用网站（只做一次）
        if (key == "common" && !prefs.getBoolean("merged26", false)) {
            val extra = mutableListOf<Pair<String, String>>()
            val defUrls = DEFAULT_COMMON.map { it.second }.toSet()
            if (prefs.contains("custom")) extra += parse(prefs.getString("custom", "[]")!!)
            else if (prefs.contains("list")) extra += parse(prefs.getString("list", "[]")!!).filterNot { it.second in defUrls }
            if (extra.isNotEmpty()) { val cur = all(); save(cur + extra.filterNot { e -> cur.any { it.second == e.second } }) }
            prefs.edit().putBoolean("merged26", true).remove("custom").apply()
        }
        // 旧版只有一个 "list"：里面默认网站以外的，迁移到自定义网站
        if (key == "custom" && !prefs.getBoolean("merged26", false) && !prefs.contains("custom") && prefs.contains("list")) {
            val old = parse(prefs.getString("list", "[]")!!)
            val defUrls = DEFAULT_COMMON.map { it.second }.toSet()
            save(old.filterNot { it.second in defUrls })
        }
    }

    fun all(): List<Pair<String, String>> {
        val raw = prefs.getString(key, null) ?: return defaults
        return parse(raw)
    }

    fun add(title: String, url: String) = save(all().filterNot { it.second == url } + (title to url))

    /** 编辑：保留原位置 */
    fun update(oldUrl: String, title: String, url: String) =
        save(all().filterNot { it.second == url && url != oldUrl }.map { if (it.second == oldUrl) title to url else it })

    fun remove(url: String) = save(all().filterNot { it.second == url })

    private fun parse(raw: String): List<Pair<String, String>> {
        val arr = JSONArray(raw)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            o.getString("title") to o.getString("url")
        }
    }

    private fun save(list: List<Pair<String, String>>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("title", it.first).put("url", it.second)) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    companion object {
        val DEFAULT_COMMON = listOf(
            "Google" to "https://www.google.com",
            "Wikipedia" to "https://zh.wikipedia.org",
            "必应" to "https://www.bing.com",
            "哔哩哔哩" to "https://m.bilibili.com",
            "YouTube" to "https://m.youtube.com",
            "GitHub" to "https://github.com",
            "知乎" to "https://www.zhihu.com",
            "百度" to "https://www.baidu.com"
        )
    }
}
