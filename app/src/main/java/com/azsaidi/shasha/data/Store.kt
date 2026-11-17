package com.azsaidi.shasha.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Small persistent store on top of SharedPreferences. */
object Store {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("shasha", Context.MODE_PRIVATE)
    }

    fun accounts(): List<Account> = runCatching {
        val arr = JSONArray(prefs.getString("accounts", null) ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Account(
                id = o.getString("id"),
                name = o.optString("name"),
                server = o.getString("server"),
                username = o.getString("username"),
                password = o.getString("password"),
            )
        }
    }.getOrDefault(emptyList())

    private fun saveAccounts(list: List<Account>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("name", a.name)
                    .put("server", a.server)
                    .put("username", a.username)
                    .put("password", a.password)
            )
        }
        prefs.edit().putString("accounts", arr.toString()).apply()
    }

    fun addAccount(account: Account) = saveAccounts(accounts().filter { it.id != account.id } + account)

    fun removeAccount(id: String) {
        saveAccounts(accounts().filter { it.id != id })
        prefs.edit().remove("fav:$id").remove("history:$id").apply()
        CatalogCache.clear(id)
        if (lastAccountId == id) lastAccountId = null
    }

    var lastAccountId: String?
        get() = prefs.getString("last_account", null)
        set(value) = prefs.edit().putString("last_account", value).apply()

    /** "auto", "ts" or "m3u8". */
    var liveFormat: String
        get() = prefs.getString("live_format", null) ?: "auto"
        set(value) = prefs.edit().putString("live_format", value).apply()

    var hideAdult: Boolean
        get() = prefs.getBoolean("hide_adult", false)
        set(value) = prefs.edit().putBoolean("hide_adult", value).apply()

    /** Go to picture-in-picture when leaving the app during playback. */
    var autoPip: Boolean
        get() = prefs.getBoolean("auto_pip", true)
        set(value) = prefs.edit().putBoolean("auto_pip", value).apply()

    fun favorites(accountId: String): List<StreamItem> = runCatching {
        val arr = JSONArray(prefs.getString("fav:$accountId", null) ?: "[]")
        (0 until arr.length()).map { StreamItem.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun saveFavorites(accountId: String, items: List<StreamItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("fav:$accountId", arr.toString()).apply()
    }

    fun history(accountId: String): List<WatchEntry> = runCatching {
        val arr = JSONArray(prefs.getString("history:$accountId", null) ?: "[]")
        (0 until arr.length()).mapNotNull { WatchEntry.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun saveHistory(accountId: String, entries: List<WatchEntry>) {
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("history:$accountId", arr.toString()).apply()
    }
}
