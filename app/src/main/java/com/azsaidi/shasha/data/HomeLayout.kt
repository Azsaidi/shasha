package com.azsaidi.shasha.data

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.azsaidi.shasha.R
import java.text.Collator

/** The rows on the Home tab. The order is chosen in Settings. */
enum class HomeRow(@StringRes val title: Int) {
    CONTINUE(R.string.continue_watching),
    FAVOURITE_CHANNELS(R.string.favourite_channels),
    FAVOURITE_VOD(R.string.favourite_vod),
    RECENT_CHANNELS(R.string.recent_channels),
    NEW_MOVIES(R.string.new_movies),
    NEW_SERIES(R.string.new_series),
}

/** How favourites are listed (Favourites tab, the Home rows and the TV home screen). */
enum class FavouriteSort(@StringRes val label: Int) {
    NAME(R.string.sort_name),
    NAME_DESC(R.string.sort_name_desc),
    NEWEST(R.string.sort_fav_newest),
    OLDEST(R.string.sort_fav_oldest),
}

/** Home row order and favourites order, kept in [Store] and observed by the screens. */
object HomeLayout {
    /** Favourites straight after Continue watching. */
    val DEFAULT_ROWS = HomeRow.entries.toList()

    var rows by mutableStateOf(load())
        private set

    var favouriteSort by mutableStateOf(
        FavouriteSort.entries.firstOrNull { it.name == Store.favouriteSort } ?: FavouriteSort.NAME,
    )
        private set

    private fun load(): List<HomeRow> {
        val saved = Store.homeRows?.split(',')?.mapNotNull { name -> HomeRow.entries.firstOrNull { it.name == name } }.orEmpty()
        // Rows added in later versions go to the end of a saved order.
        return (saved + DEFAULT_ROWS).distinct()
    }

    /** Moves [row] up (-1) or down (+1). */
    fun move(row: HomeRow, delta: Int) {
        val list = rows.toMutableList()
        val from = list.indexOf(row)
        val to = from + delta
        if (from < 0 || to !in list.indices) return
        list.removeAt(from)
        list.add(to, row)
        save(list)
    }

    fun reset() = save(DEFAULT_ROWS)

    private fun save(list: List<HomeRow>) {
        rows = list
        Store.homeRows = list.joinToString(",") { it.name }
    }

    fun setFavouriteSort(sort: FavouriteSort) {
        favouriteSort = sort
        Store.favouriteSort = sort.name
        WatchNext.requestSync()
    }
}

/**
 * Favourites in the chosen order. [Session.favorites] keeps the newest first; names compare the
 * way people read them (accents and Arabic included), ignoring case.
 */
fun List<StreamItem>.sortedFavourites(sort: FavouriteSort = HomeLayout.favouriteSort): List<StreamItem> {
    val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
    return when (sort) {
        FavouriteSort.NAME -> sortedWith { a, b -> collator.compare(a.name.trim(), b.name.trim()) }
        FavouriteSort.NAME_DESC -> sortedWith { a, b -> collator.compare(b.name.trim(), a.name.trim()) }
        FavouriteSort.NEWEST -> this
        FavouriteSort.OLDEST -> reversed()
    }
}
