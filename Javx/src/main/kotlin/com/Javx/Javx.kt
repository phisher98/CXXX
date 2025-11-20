package com.Javx

import android.util.Log
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Javx : MainAPI() {
    override var name = "Javx"
    override var mainUrl = "https://javx.org"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasDownloadSupport = true
    override val hasMainPage = true
    override val hasQuickSearch = false

    override val mainPage = mainPageOf(
        "?filter=latest" to "Latest",
        "videos" to "Popular",
        "english-subtitles" to "English Subtitles",
        "category/milf" to "Milf",
        "category/cheating-wife" to "Cheating Wife",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/page/$page/${request.data}").document
        val responseList  = document.select("article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList(request.name, responseList, isHorizontalImages = true), hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select("a").attr("title")
        val href = this.select("a").attr("href")
        val posterUrl = this.selectFirst("a img")?.attr("data-src")
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            posterHeaders = mapOf("referer" to mainUrl)
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val document = app.get("$mainUrl/page/$page/?s=$query").document
        val results = document.select("article").mapNotNull { it.toSearchResult() }
        val hasNext = results.isNotEmpty()
        return newSearchResponseList(results, hasNext)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title       = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().toString()
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val poster = document.select("meta[property=og:image]").attr("content")
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            posterHeaders = mapOf("referer" to mainUrl)
            this.plot = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = app.get(data).document
        doc.select("#sourcetabs a").mapNotNull {
            val href = it.attr("href")
            Log.d("Phisher",href)
            loadExtractor(href,"",subtitleCallback,callback)
        }

        return true
    }

}

class StreamwishHG : StreamWishExtractor() {
    override val mainUrl = "https://hglink.to"
}