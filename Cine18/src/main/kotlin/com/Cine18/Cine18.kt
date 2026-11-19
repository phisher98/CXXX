package com.Cine18

import android.net.Uri
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class Cine18 : MainAPI() {
    override var mainUrl = "https://server.cinewebby.com"
    override var name = "Cine18"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW)

    private val apiUrl = "https://server.cinewebby.com/api/user"
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "home" to "Home",
        "latest" to "Latest Uploads",
    )

    data class Video(
        @JsonProperty("uid") val uid: String,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("video_link") val video_link: String? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
    )

    data class Card(
        @JsonProperty("uid") val uid: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("portrait_img") val portrait_img: String? = null,
        @JsonProperty("banner_img") val banner_img: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("version") val version: String? = null,
        @JsonProperty("tags") val tags: String? = null,
        @JsonProperty("cast") val cast: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("preview_text") val preview_text: String? = null,
        @JsonProperty("rating") val rating: Double? = null,
        @JsonProperty("image_type") val image_type: String? = null,
        @JsonProperty("total_views") val total_views: Long? = null,
        @JsonProperty("videos") val videos: List<Video> = emptyList(),
        @JsonProperty("related") val related: List<Card> = emptyList(),
    )

    data class Mapping(
        @JsonProperty("video_card") val video_card: Card? = null,
    )

    data class Section(
        @JsonProperty("uid") val uid: String? = null,
        @JsonProperty("name") val name: String,
        @JsonProperty("section_type") val section_type: String? = null,
        @JsonProperty("mappings") val mappings: List<Mapping> = emptyList(),
    )

    private fun Card.toSearchResult(landscape: Boolean? = null): SearchResponse {
        val preferLandscape = landscape ?: (image_type == "landscape")
        val poster = when {
            preferLandscape -> banner_img ?: portrait_img
            else -> portrait_img ?: banner_img
        }
        return newMovieSearchResponse(title.trim(), uid, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    private fun List<Card>.isHorizontalRow(): Boolean {
        val typed = mapNotNull { it.image_type }
        if (typed.isEmpty()) return true
        return typed.count { it == "landscape" } >= typed.size / 2.0
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (request.data == "home") {
            val sections = runCatching {
                AppUtils.parseJson<List<Section>>(app.get("$apiUrl/section").text)
            }.getOrNull() ?: emptyList()
            val lists = sections.take(15).mapNotNull { section ->
                val cards = section.mappings.mapNotNull { it.video_card }
                if (cards.isEmpty()) return@mapNotNull null
                HomePageList(
                    name = section.name.trim(),
                    list = cards.map { it.toSearchResult() },
                    isHorizontalImages = cards.isHorizontalRow()
                )
            }
            if (lists.isNotEmpty()) {
                return newHomePageResponse(lists, hasNext = false)
            }
        }

        val cards = runCatching {
            AppUtils.parseJson<List<Card>>(app.post(
                url = "$apiUrl/filter-videos",
                data = mapOf("page" to page.toString()),
                headers = mapOf("User-Agent" to userAgent)
            ).text)
        }.getOrNull() ?: emptyList()
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = cards.map { it.toSearchResult(landscape = true) },
                isHorizontalImages = true
            ),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val attempts = mutableListOf(query.trim())
        if (query.contains(' ')) {
            attempts += query.trim().split(' ').sortedByDescending { it.length }
        }
        for (term in attempts) {
            if (term.isEmpty()) continue
            val results = runCatching {
                AppUtils.parseJson<List<Card>>(app.get(
                    "$apiUrl/search-videos?search=${Uri.encode(term)}",
                    headers = mapOf("User-Agent" to userAgent)
                ).text)
            }.getOrNull() ?: emptyList()
            if (results.isNotEmpty()) {
                return results.map { it.toSearchResult(landscape = false) }
            }
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val cardUid = url.substringAfterLast('/')
        val card = runCatching { AppUtils.parseJson<Card>(app.get(
            "$apiUrl/video-card/$cardUid",
            headers = mapOf("User-Agent" to userAgent)
        ).text) }.getOrNull() ?: throw ErrorLoadingException("Unable to load video card")

        val title = card.title.trim()
        val tags = card.tags?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val poster = if (card.image_type == "landscape") {
            card.banner_img ?: card.portrait_img
        } else {
            card.portrait_img ?: card.banner_img
        }
        val recommendations = card.related
            .filter { it.uid != card.uid && it.title.isNotBlank() }
            .map { it.toSearchResult() }

        return if (card.videos.size > 1) {
            val episodes = card.videos.mapIndexed { index, video ->
                val epName = video.name?.trim().takeIf { !it.isNullOrEmpty() } ?: "Episode ${index + 1}"
                val epNum = Regex("""(?i)ep(?:isode)?\s*(\d+)""").find(epName)
                    ?.groupValues?.get(1)?.toIntOrNull()
                newEpisode("${card.uid}|${video.uid}") {
                    this.name = epName
                    this.posterUrl = video.thumbnail
                    this.episode = epNum ?: index + 1
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
                this.posterUrl = poster
                this.plot = card.description?.trim()
                this.tags = tags
                this.score = Score.from10(card.rating)
                this.recommendations = recommendations
            }
        } else {
            val video = card.videos.firstOrNull()
            newMovieLoadResponse(title, url, TvType.NSFW, "${card.uid}|${video?.uid ?: ""}") {
                this.posterUrl = poster
                this.plot = card.description?.trim()
                this.tags = tags
                this.score = Score.from10(card.rating)
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|", limit = 2)
        val cardUid = parts.getOrNull(0)?.substringAfterLast('/').orEmpty()
        val videoUid = parts.getOrNull(1)?.substringAfterLast('/').orEmpty()
        val card = runCatching { AppUtils.parseJson<Card>(app.get(
            "$apiUrl/video-card/$cardUid",
            headers = mapOf("User-Agent" to userAgent)
        ).text) }.getOrNull() ?: return false

        val videos = card.videos.filter { !it.video_link.isNullOrBlank() }
        val sources = videos.filter { it.uid == videoUid }.ifEmpty { videos }
        sources.forEach { video ->
            val link = video.video_link ?: return@forEach
            val label = if (sources.size > 1) {
                video.name?.trim().takeIf { !it.isNullOrEmpty() } ?: video.uid
            } else {
                name
            }
            val quality = Regex("""(2160|1440|1080|720|480)[pP]""").find(link)
                ?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value

            callback.invoke(
                newExtractorLink(name, label, Uri.encode(link, "/:?&="), ExtractorLinkType.VIDEO) {
                    this.referer = "$mainUrl/"
                    this.quality = quality
                    this.headers = mapOf("User-Agent" to userAgent)
                }
            )
        }
        return sources.isNotEmpty()
    }
}
