package com.aniliphy.core

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import com.aniliphy.source.Site
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import java.io.IOException

/**
 * What a Mihon or Aniyomi source can do beyond the app's own source interfaces: its settings screen, and browsing
 * its catalogue with the filters it offers.
 */
internal interface TachiSite : Site {
    /** An anime source, or else a manga one. */
    val isAnime: Boolean

    /** Whether it has a list of the latest updates. */
    val supportsLatest: Boolean

    /** Whether it has settings of its own. */
    val configurable: Boolean

    /** Where its settings are kept, or null when it has none. Reading it loads a file, so not on the main thread. */
    fun preferences(): SharedPreferences?

    /** Lets the source add its settings to [screen]. */
    fun setupPreferences(screen: PreferenceScreen)

    /** Its filters, as they start. Each browse keeps its own copy, since the source changes them in place. */
    fun filters(): SiteFilters

    @Throws(IOException::class)
    fun browse(listing: Listing, page: Int, query: String, filters: SiteFilters?): BrowsePage

    /** A cover, fetched with the source's own client and headers, as Mihon and Aniyomi fetch them. */
    @Throws(IOException::class)
    fun cover(url: String): Pair<ByteArray, String?>
}

internal enum class Listing { Popular, Latest, Search }

/** One title in a browse list. [url] is the source's address for it, as saved with progress. */
internal data class BrowseItem(val title: String, val url: String, val cover: String?)

internal data class BrowsePage(val items: List<BrowseItem>, val hasNext: Boolean)

/**
 * A source's filters, drawn the same way for manga and anime. Each one reads and writes the source's own filter
 * object, which the source reads back when searching.
 */
internal sealed class SiteFilter(val name: String) {
    class Header(name: String) : SiteFilter(name)

    class Separator(name: String) : SiteFilter(name)

    class Text(name: String, val get: () -> String, val set: (String) -> Unit) : SiteFilter(name)

    class Check(name: String, val get: () -> Boolean, val set: (Boolean) -> Unit) : SiteFilter(name)

    /** 0 ignores it, 1 includes it, 2 excludes it, as Mihon counts. */
    class Tri(name: String, val get: () -> Int, val set: (Int) -> Unit) : SiteFilter(name)

    class Select(name: String, val values: List<String>, val get: () -> Int, val set: (Int) -> Unit) : SiteFilter(name)

    /** The chosen value and whether it goes up, or null for none. */
    class Sort(name: String, val values: List<String>, val get: () -> Pair<Int, Boolean>?, val set: (Pair<Int, Boolean>?) -> Unit) :
        SiteFilter(name)

    class Group(name: String, val items: List<SiteFilter>) : SiteFilter(name)
}

/** [raw] is the source's own list, handed back to it when searching. */
internal class SiteFilters(val items: List<SiteFilter>, val raw: Any)

internal fun siteFilters(list: FilterList): SiteFilters = SiteFilters(list.mapNotNull(::mangaFilter), list)

internal fun siteFilters(list: AnimeFilterList): SiteFilters = SiteFilters(list.mapNotNull(::animeFilter), list)

private fun mangaFilter(filter: Filter<*>): SiteFilter? = when (filter) {
    is Filter.Header -> SiteFilter.Header(filter.name)
    is Filter.Separator -> SiteFilter.Separator(filter.name)
    is Filter.Text -> SiteFilter.Text(filter.name, { filter.state }, { filter.state = it })
    is Filter.CheckBox -> SiteFilter.Check(filter.name, { filter.state }, { filter.state = it })
    is Filter.TriState -> SiteFilter.Tri(filter.name, { filter.state }, { filter.state = it })
    is Filter.Select<*> -> SiteFilter.Select(filter.name, filter.values.map { it.toString() }, { filter.state }, { filter.state = it })
    is Filter.Sort -> SiteFilter.Sort(
        filter.name,
        filter.values.toList(),
        { filter.state?.let { it.index to it.ascending } },
        { filter.state = it?.let { (index, ascending) -> Filter.Sort.Selection(index, ascending) } },
    )
    is Filter.Group<*> -> SiteFilter.Group(filter.name, filter.state.mapNotNull { (it as? Filter<*>)?.let(::mangaFilter) })
}

private fun animeFilter(filter: AnimeFilter<*>): SiteFilter? = when (filter) {
    is AnimeFilter.Header -> SiteFilter.Header(filter.name)
    is AnimeFilter.Separator -> SiteFilter.Separator(filter.name)
    is AnimeFilter.Text -> SiteFilter.Text(filter.name, { filter.state }, { filter.state = it })
    is AnimeFilter.CheckBox -> SiteFilter.Check(filter.name, { filter.state }, { filter.state = it })
    is AnimeFilter.TriState -> SiteFilter.Tri(filter.name, { filter.state }, { filter.state = it })
    is AnimeFilter.Select<*> -> SiteFilter.Select(filter.name, filter.values.map { it.toString() }, { filter.state }, { filter.state = it })
    is AnimeFilter.Sort -> SiteFilter.Sort(
        filter.name,
        filter.values.toList(),
        { filter.state?.let { it.index to it.ascending } },
        { filter.state = it?.let { (index, ascending) -> AnimeFilter.Sort.Selection(index, ascending) } },
    )
    is AnimeFilter.Group<*> -> SiteFilter.Group(filter.name, filter.state.mapNotNull { (it as? AnimeFilter<*>)?.let(::animeFilter) })
}
