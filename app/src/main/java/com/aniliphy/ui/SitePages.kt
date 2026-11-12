package com.aniliphy.ui

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.aniliphy.core.TachiMangaSite
import com.aniliphy.source.MangaSite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer

/**
 * A page from a Mihon extension's site. Its image is fetched by the extension itself, since many of them need their own
 * client, headers or image tricks; other sites' pages stay plain addresses, fetched by Coil.
 */
internal data class SitePage(val site: TachiMangaSite, val url: String)

internal fun pageModel(site: MangaSite, url: String): Any = if (site is TachiMangaSite) SitePage(site, url) else url

internal class SitePageFetcher(private val page: SitePage, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val (bytes, type) = page.site.image(page.url)
        SourceFetchResult(
            source = ImageSource(Buffer().write(bytes), options.fileSystem),
            mimeType = type?.substringBefore(';')?.trim(),
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory : Fetcher.Factory<SitePage> {
        override fun create(data: SitePage, options: Options, imageLoader: ImageLoader): Fetcher = SitePageFetcher(data, options)
    }
}

/** A cover from a Mihon or Aniyomi source, fetched with the source's own client and headers. */
internal data class SiteCover(val site: com.aniliphy.core.TachiSite, val url: String)

internal class SiteCoverFetcher(private val cover: SiteCover, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val (bytes, type) = cover.site.cover(cover.url)
        SourceFetchResult(
            source = ImageSource(Buffer().write(bytes), options.fileSystem),
            mimeType = type?.substringBefore(';')?.trim(),
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory : Fetcher.Factory<SiteCover> {
        override fun create(data: SiteCover, options: Options, imageLoader: ImageLoader): Fetcher = SiteCoverFetcher(data, options)
    }
}
