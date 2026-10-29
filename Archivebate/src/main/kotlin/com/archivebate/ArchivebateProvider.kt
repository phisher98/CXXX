package com.archivebate

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * archivebate.com - archive of recorded cam shows. Laravel + Livewire: the video grids (home,
 * profile) are injected by a Livewire call (loadVideos / load_profile_videos), so a plain fetch
 * returns empty containers. We replay that Livewire message to get the cards, and the /watch/<id>
 * player (a Livewire-injected MixDrop embed) is sniffed from a hidden WebView (ArchivebateBrowser).
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
    private val cf = CloudflareKiller()

    override val mainPage = mainPageOf(
        "" to "Recent",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) "$mainUrl/" else "$mainUrl/?page=$page"
        val items = gridFrom(pageUrl)
        return newHomePageResponse(
            list = HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = items.isNotEmpty()
        )
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SearchItem(val username: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SearchApi(val data: List<SearchItem>? = null)

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        // the search api returns {"data":[{username,...}]} and needs the ajax headers or Laravel
        // serves html; the matching performers' videos then live on /profile/<name>
        val names = runCatching {
            app.get(
                "$mainUrl/api/v1/search?query=$q",
                referer = "$mainUrl/",
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "User-Agent" to ua,
                ),
                interceptor = cf
            ).parsedSafe<SearchApi>()?.data
        }.getOrNull().orEmpty().mapNotNull { it.username?.trim()?.takeIf { n -> n.isNotBlank() } }.distinct().take(3)

        val out = LinkedHashMap<String, SearchResponse>()
        for (nm in names) gridFrom("$mainUrl/profile/$nm").forEach { out.putIfAbsent(it.url, it) }
        return out.values.toList()
    }

    /**
     * Video cards for a listing page. The grid is injected by Livewire behind an age gate, so we
     * render the page in a WebView (which auto-accepts the gate and runs Livewire), and fall back
     * to replaying the Livewire call directly if the render came back empty.
     */
    private suspend fun gridFrom(pageUrl: String): List<SearchResponse> {
        val html = ArchivebateBrowser.renderHtml(pageUrl, "$mainUrl/", ua, "section.video_item", 25_000L)
        val rendered = html?.let { Jsoup.parse(it) }
            ?.select("section.video_item")?.mapNotNull { it.toSearchResult() }?.distinctBy { it.url }.orEmpty()
        if (rendered.isNotEmpty()) return rendered
        return livewireGrid(pageUrl)
    }

    /** Pull `{...}` for [key] out of a json string, keeping it byte-exact (Livewire checksum cares). */
    private fun jsonObjectField(src: String, key: String): String? {
        val at = src.indexOf("\"$key\":")
        if (at < 0) return null
        val open = src.indexOf('{', at)
        if (open < 0) return null
        var depth = 0
        var i = open
        var inStr = false
        var esc = false
        while (i < src.length) {
            val c = src[i]
            if (inStr) {
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return src.substring(open, i + 1) }
            }
            i++
        }
        return null
    }

    private suspend fun livewireGrid(pageUrl: String): List<SearchResponse> = runCatching {
        val res = app.get(pageUrl, referer = "$mainUrl/", interceptor = cf, headers = mapOf("User-Agent" to ua))
        val doc = res.document
        val csrf = doc.selectFirst("meta[name=csrf-token]")?.attr("content").orEmpty()
        val comp = doc.getElementsByAttribute("wire:initial-data").firstOrNull {
            val d = it.attr("wire:initial-data")
            d.contains("\"home-videos\"") || d.contains("\"profile.model-videos\"")
        } ?: return emptyList()

        val initial = comp.attr("wire:initial-data") // Jsoup already HTML-unescapes the attribute
        val name = Regex(""""name":"([^"]+)"""").find(initial)?.groupValues?.get(1) ?: return emptyList()
        val method = comp.attr("wire:init").ifBlank {
            if (name.contains("profile")) "load_profile_videos" else "loadVideos"
        }
        val fingerprint = jsonObjectField(initial, "fingerprint") ?: return emptyList()
        val serverMemo = jsonObjectField(initial, "serverMemo") ?: return emptyList()
        val rnd = (1..4).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
        val body = """{"fingerprint":$fingerprint,"serverMemo":$serverMemo,""" +
            """"updates":[{"type":"callMethod","payload":{"id":"$rnd","method":"$method","params":[]}}]}"""

        val post = app.post(
            "$mainUrl/livewire/message/$name",
            headers = mapOf(
                "X-CSRF-TOKEN" to csrf,
                "X-Livewire" to "true",
                "Accept" to "text/html, application/xhtml+xml",
                "Origin" to mainUrl,
                "Referer" to pageUrl,
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to ua,
            ),
            requestBody = body.toRequestBody("application/json".toMediaType()),
            cookies = res.cookies,
            interceptor = cf
        ).text

        val effectHtml = JSONObject(post).optJSONObject("effects")?.optString("html").orEmpty()
        Jsoup.parse(effectHtml).select("section.video_item").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }.getOrNull().orEmpty()

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, referer = "$mainUrl/", interceptor = cf).document
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.ifBlank { null }
            ?: doc.title().substringBefore(" - ").trim().ifBlank { "Video" }
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
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

        if (!found) {
            hits.filter { it.kind == ArchivebateBrowser.Kind.EMBED }.map {
                it.url.replace("mxdrop.click", "mixdrop.ag")
            }.distinct().forEach { embed ->
                runCatching { if (loadExtractor(embed, "$mainUrl/", subtitleCallback, callback)) found = true }
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
                ?: selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        )
        val title = model?.takeIf { it.isNotBlank() } ?: "Video ${href.substringAfterLast("/")}"
        return newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
    }
}
