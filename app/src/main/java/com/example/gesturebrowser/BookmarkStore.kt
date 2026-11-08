package com.example.gesturebrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 用 SharedPreferences 存书签：每条是 (标题, 网址)。 */
class BookmarkStore(context: Context) {
    private val prefs = context.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)

    fun all(): List<Pair<String, String>> {
        val arr = JSONArray(prefs.getString("list", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            o.getString("title") to o.getString("url")
        }
    }

    fun add(title: String, url: String) {
        val list = all().filterNot { it.second == url } + (title to url)
        save(list)
    }

    fun remove(url: String) = save(all().filterNot { it.second == url })

    private fun save(list: List<Pair<String, String>>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("title", it.first).put("url", it.second)) }
        prefs.edit().putString("list", arr.toString()).apply()
    }
}
