package com.azsaidi.shasha.data

private val episodeCode = Regex("(?i)\\bS\\s?\\d{1,2}\\s*[-.]?\\s*E\\s?\\d{1,4}\\b")
private val edgeJunk = Regex("^[\\s\\-–—:·|.,_]+|[\\s\\-–—:·|.,_]+$")

/**
 * Providers often name episodes like "Transplant S01 E02", which just repeats what we already
 * show. Removes the series name and S/E codes; returns null if nothing meaningful is left.
 */
fun tidyEpisodeTitle(title: String?, seriesName: String?): String? {
    if (title.isNullOrBlank()) return null
    var t = title
    if (!seriesName.isNullOrBlank()) t = t.replace(seriesName.trim(), "", ignoreCase = true)
    t = t.replace(episodeCode, " ")
    t = t.replace(Regex("\\s{2,}"), " ")
    t = t.replace(edgeJunk, "")
    if (t.isBlank() || t.length < 2) return null
    // A bare "Episode 2" adds nothing either.
    if (Regex("(?i)^(episode|ep|e)\\s*\\d+$").matches(t)) return null
    return t
}

/** Subtitle for history cards: "S1 E2" plus the episode name only when it's a real name. */
fun WatchEntry.displaySubtitle(): String? {
    val sub = subtitle ?: return null
    if (kind != WatchKind.EPISODE) return sub
    val parts = sub.split(" · ")
    val code = parts.first()
    val name = tidyEpisodeTitle(parts.drop(1).joinToString(" · "), seriesName ?: title)
    return listOfNotNull(code, name).joinToString(" · ")
}
