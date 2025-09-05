package com.Rowdycado

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SubtitleHelper
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element


class AnimeIdHentai : MainAPI() {
    override var mainUrl = "https://animeidhentai.com"
    override var name = "AnimeIdHentai"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    override val mainPage =
        mainPageOf(
            "trending" to "Trending Hentai",
            "genre/censored" to "Censored Hentai",
            "genre/hentai-uncensored" to "Uncensored Hentai",
            "genre/incest" to "Incest Hentai",
            "genre/hd" to "HD Hentai",
            "genre/maid" to "Maid Hentai",
            "genre/monster" to "Monster Hentai",
            "genre/female-student" to "Female Student Hentai",
            "genre/tentacle" to "Tentacle Hentai",
        )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ):
            HomePageResponse? {
        var list = mutableListOf<AnimeSearchResponse>()
        val res = app.get("$mainUrl/${request.data}/page/$page").document
        res.select("article.anime.poster.por").mapNotNull { article ->
            val name = article.selectFirst("header > div.ttl")?.text() ?: ""
            val poster = article.selectFirst("img")?.attr("src")
            val url = article.selectFirst("a.lnk-blk")?.attr("href") ?: ""
            list.add(newAnimeSearchResponse(name, url)
            {
                this.posterUrl = poster
            })
        }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = list,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url =
            "$mainUrl/?s=${query}"
        return app.get(
            url,
        ).document.select("article.anime.poster.por").mapNotNull { article ->
            val name = article.selectFirst("header > div.ttl")?.text() ?: ""
            val poster = article.selectFirst("img")?.attr("src")
            val url = article.selectFirst("a.lnk-blk")?.attr("href") ?: ""
            newAnimeSearchResponse(name, url) {
                posterUrl = poster

            }
        }

    }

    override suspend fun load(url: String): LoadResponse {
        val result = app.get(url).document
        val background =
            result.selectFirst("div.backdrop")?.attr("style")?.substringAfter("url('")
                ?.replace("')", "")
        val description = result.selectFirst("div.description > p")?.text()
        val name =
            result.selectFirst("header.anime-hd h1.ttl")?.text() ?: ""


        return newMovieLoadResponse(name, url, TvType.NSFW, url) {
            this.backgroundPosterUrl =
                if (background.isNullOrEmpty()) result.selectFirst("meta[property=og:image]")
                    ?.attr("content")?.trim() else background
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data).document
        val iframe = res.selectFirst("div.embed > iframe")?.attr("src") ?: ""
        val playerurl = extractplayer(iframe) ?: ""
        val sourceurl = extractsource(playerurl) ?:""
        val subtitle = extractsubtitles(playerurl) ?:""

        callback.invoke(
        ExtractorLink(
            source = this.name,
            name = this.name,
            url = sourceurl,
            referer = "",
            quality = Qualities.Unknown.value,
            isM3u8 = false
        )

        )
        subtitleCallback.invoke(
            SubtitleFile(
                "eng",
                subtitle
            )
        )
        return true
    }

    suspend fun extractplayer(url: String): String? {
        val iframes = app.get(url).document
        val iframeres = iframes.selectFirst("div.servers li")?.attr("data-id") ?: ""
        Log.d("HATE", iframeres)

        return iframeres

    }

    suspend fun extractsource(url: String): String? {
        val iframe = app.get("https://nhplayer.com/$url").document
        val iframeres = iframe.select("script:containsData(sources)").toString()
            .substringAfter("file: \"").substringBefore("\",")
        Log.d("Pain", iframeres)

        return iframeres

    }

    suspend fun extractsubtitles(url: String): String? {
        val iframe = app.get("https://nhplayer.com/$url").document
        val iframeres = iframe.select("script:containsData(sources)").toString()
        val pattern = "\"file\":.\"(.*)\",".toRegex()
        val matchResult = pattern.find(iframeres)
        val subtitle = matchResult?.groups?.get(1)?.value ?: ""

        return subtitle

    }
}