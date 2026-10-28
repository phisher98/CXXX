package com.archivebate

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.jsoup.nodes.Element

/**
 * archivebate.com - archive of recorded cam shows (Chaturbate/Stripchat/...). WordPress-free,
 * Laravel + Livewire. Listing cards are <section.video_item>; the /watch/<id> player is injected
 * by Livewire as a MixDrop embed, so links are taken from a hidden WebView (ArchivebateBrowser).
 */
class ArchivebateProvider : MainAPI() {
    override var mainUrl = "https://archivebate.com"
    override var name = "Archivebate"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val ua =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "" to "Recent",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = if (request.data.isBlank()) mainUrl else "$mainUrl/${request.data}"
        val url = if (page <= 1) "$base/" else "$base/?page=$page"
        val items = app.get(url.replace("//?", "/?"), referer = "$mainUrl/").document
            .select("section.video_item").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(
            list = HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = items.isNotEmpty()
        )
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SearchItem(val username: String? = null, val platform: String? = null)

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        // the site's search api returns matching performer usernames; their videos live on /profile/<name>
        val names = runCatching {
            app.get("$mainUrl/api/v1/search?query=$q", referer = "$mainUrl/")
                .parsedSafe<List<SearchItem>>()
        }.getOrNull().orEmpty().mapNotNull { it.username?.trim()?.takeIf { n -> n.isNotBlank() } }.distinct().take(4)

        val out = LinkedHashMap<String, SearchResponse>()
        for (nm in names) {
            runCatching {
                app.get("$mainUrl/profile/$nm", referer = "$mainUrl/").document
                    .select("section.video_item").mapNotNull { it.toSearchResult() }
                    .forEach { out.putIfAbsent(it.url, it) }
            }
        }
        return out.values.toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, referer = "$mainUrl/").document
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.ifBlank { null }
            ?: doc.title().substringBefore(" - ").trim().ifBlank { "Video" }
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val recs = doc.select("section.video_item").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }.filter { it.url != url }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
            this.recommendations = recs
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val hits = ArchivebateBrowser.sniff(data, "$mainUrl/", ua, 25_000L)
        var found = false

        // a direct media url (MixDrop's signed mp4 / an hls master) is the exact stream
        hits.filter { it.kind == ArchivebateBrowser.Kind.MEDIA }.distinctBy { it.url }.forEach { hit ->
            val type = if (hit.url.lowercase().contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback.invoke(
                newExtractorLink(name, name, hit.url, type) {
                    this.referer = hit.referer ?: "https://mxdrop.click/"
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }

        // otherwise hand the MixDrop embed to Cloudstream's built-in extractor (mxdrop.click -> mixdrop.ag)
        if (!found) {
            hits.filter { it.kind == ArchivebateBrowser.Kind.EMBED }.map {
                it.url.replace("mxdrop.click", "mixdrop.ag")
            }.distinct().forEach { embed ->
                runCatching {
                    if (loadExtractor(embed, "$mainUrl/", subtitleCallback, callback)) found = true
                }
            }
        }

        return found
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(selectFirst("a[href*=/watch/]")?.attr("href")) ?: return null
        val model = selectFirst("div.info a[href*=/profile/]")?.text()?.trim()
        val video = selectFirst("video")
        val poster = fixUrlNull(
            video?.attr("poster")?.takeIf { it.isNotBlank() }
                ?: video?.selectFirst("source")?.attr("src")?.takeIf { it.isNotBlank() && !it.endsWith(".mp4") }
                ?: selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        )
        val title = model?.takeIf { it.isNotBlank() } ?: "Video ${href.substringAfterLast("/")}"
        return newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
    }
}
