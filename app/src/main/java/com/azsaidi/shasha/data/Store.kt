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
        prefs.edit().remove("fav:$id").apply()
        if (lastAccountId == id) lastAccountId = null
    }

    var lastAccountId: String?
        get() = prefs.getString("last_account", null)
        set(value) = prefs.edit().putString("last_account", value).apply()

    /** "auto", "ts" or "m3u8". */
    var liveFormat: String
        get() = prefs.getString("live_format", null) ?: "auto"
        set(value) = prefs.edit().putString("live_format", value).apply()

    fun favorites(accountId: String): List<StreamItem> = runCatching {
        val arr = JSONArray(prefs.getString("fav:$accountId", null) ?: "[]")
        (0 until arr.length()).map { StreamItem.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun saveFavorites(accountId: String, items: List<StreamItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("fav:$accountId", arr.toString()).apply()
    }

    fun position(key: String): Long = prefs.getLong("pos:$key", 0L)

    fun savePosition(key: String, positionMs: Long) {
        if (positionMs <= 0) prefs.edit().remove("pos:$key").apply()
        else prefs.edit().putLong("pos:$key", positionMs).apply()
    }
}
