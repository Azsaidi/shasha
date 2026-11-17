package com.azsaidi.shasha.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.File

/**
 * Keeps each account's channel / movie / series lists on disk so the app opens instantly
 * instead of downloading everything again. Uses streaming JSON so big catalogs stay cheap.
 */
object CatalogCache {
    private lateinit var dir: File

    fun init(context: Context) {
        dir = File(context.filesDir, "catalog").apply { mkdirs() }
    }

    private fun file(accountId: String, type: ContentType) = File(dir, "${accountId}_${type.name}.json")

    /** Returns the cached library and when it was saved (millis), or null. */
    fun read(accountId: String, type: ContentType): Pair<Library, Long>? {
        val f = file(accountId, type)
        if (!f.exists()) return null
        return runCatching {
            JsonReader(f.bufferedReader()).use { r ->
                var savedAt = 0L
                val categories = ArrayList<Category>()
                val items = ArrayList<StreamItem>()
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "savedAt" -> savedAt = r.nextLong()
                        "categories" -> {
                            r.beginArray()
                            while (r.hasNext()) {
                                r.beginArray()
                                val id = r.nextString()
                                val name = r.nextString()
                                r.endArray()
                                categories.add(Category(id, name))
                            }
                            r.endArray()
                        }
                        "items" -> {
                            r.beginArray()
                            while (r.hasNext()) {
                                r.beginArray()
                                val id = r.nextString()
                                val name = r.nextString()
                                val icon = r.nullableString()
                                val category = r.nullableString()
                                val ext = r.nullableString()
                                val rating = r.nullableString()
                                val added = r.nextLong()
                                // Year was added later; older cache files simply don't have it.
                                val year = if (r.hasNext()) r.nextInt() else 0
                                while (r.hasNext()) r.skipValue()
                                r.endArray()
                                val knownYear = year.takeIf { it > 0 } ?: if (type != ContentType.LIVE) yearFromTitle(name) else null
                                items.add(StreamItem(id, name, icon, category, type, ext, rating, added.takeIf { it > 0 }, knownYear))
                            }
                            r.endArray()
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                (categories to items) to savedAt
            }
        }.getOrNull()
    }

    fun write(accountId: String, type: ContentType, library: Library) {
        val f = file(accountId, type)
        val tmp = File(f.parentFile, f.name + ".tmp")
        runCatching {
            JsonWriter(tmp.bufferedWriter()).use { w ->
                w.beginObject()
                w.name("savedAt").value(System.currentTimeMillis())
                w.name("categories").beginArray()
                for (c in library.first) w.beginArray().value(c.id).value(c.name).endArray()
                w.endArray()
                w.name("items").beginArray()
                for (i in library.second) {
                    w.beginArray()
                        .value(i.id)
                        .value(i.name)
                        .value(i.icon)
                        .value(i.categoryId)
                        .value(i.extension)
                        .value(i.rating)
                        .value(i.added ?: 0L)
                        .value((i.year ?: 0).toLong())
                        .endArray()
                }
                w.endArray()
                w.endObject()
            }
            tmp.renameTo(f)
        }.onFailure { tmp.delete() }
    }

    fun clear(accountId: String) {
        dir.listFiles()?.filter { it.name.startsWith("${accountId}_") }?.forEach { it.delete() }
    }

    private fun JsonReader.nullableString(): String? =
        if (peek() == JsonToken.NULL) { nextNull(); null } else nextString()
}
