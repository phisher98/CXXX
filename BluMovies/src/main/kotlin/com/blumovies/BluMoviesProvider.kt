package com.blumovies

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.net.URLEncoder
import org.jsoup.nodes.Element

/**
 * Blu Movies (Android app, AF 6.2) - rebuilt from the decompiled APK.
 *
 * The app is a front-end for xxxparodyhd.net (strings were XOR/PRNG-obfuscated, decoded offline):
 *  - tabs   : New Movies  /page/{n}/
 *             Popular     /page/{n}/?filter=most-viewed
 *             Our Choice  /release-year/2026/page/{n}/
 *  - search : /page/{n}/?s={query}
 *  - cards  : article.card -> a[href], div.card__b>h2 (title), a>img (data-lazy-src|data-src|src), a>span.card__dur
 *  - detail : div.desc>p, span.st--duration, chips rows (Release Year / Studio / Genres / Pornstars)
 *  - servers: section.hlm[data-servers] = JSON [{"u": embed url, "l": label}]
 *             (streamtape, lulustream, doodstream/doply/playmogo, voe, vinovo, mixdrop, playmate, vidara)
 */
class BluMoviesProvider : MainAPI() {
    override var mainUrl = "https://xxxparodyhd.net"
    override var name = "Blu Movies"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val cf = CloudflareKiller()
    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "page/%d/" to "New Movies",
        "page/%d/?filter=most-viewed" to "Popular Movies",
        "release-year/2026/page/%d/" to "Our Choice",
    )

    private suspend fun doc(url: String) =
        app.get(url, referer = "$mainUrl/", headers = mapOf("User-Agent" to ua), interceptor = cf).document

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = doc("$mainUrl/${request.data.format(page)}")
            .select("article.card").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val out = LinkedHashMap<String, SearchResponse>()
        for (p in 1..3) {
            val url = if (p == 1) "$mainUrl/?s=$q" else "$mainUrl/page/$p/?s=$q"
            val found = runCatching { doc(url).select("article.card").mapNotNull { it.toSearchResult() } }.getOrNull().orEmpty()
            val fresh = found.filter { out[it.url] == null }
            if (fresh.isEmpty()) break
            fresh.forEach { out[it.url] = it }
        }
        return out.values.toList()
    }

    private fun Element.img(): String? {
        val i = selectFirst("a img") ?: selectFirst("img") ?: return null
        val src = listOf("data-lazy-src", "data-src", "src").map { i.attr(it) }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
        return fixUrlNull(src)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(selectFirst("a[href]")?.attr("href")) ?: return null
        val title = selectFirst("div.card__b h2")?.text()?.trim()?.ifBlank { null }
            ?: selectFirst("a img")?.attr("alt")?.trim()?.ifBlank { null }
            ?: return null
        val poster = img()
        return newMovieSearchResponse(title, href, TvType.NSFW) { posterUrl = poster }
    }

    private fun Element.chips(label: String) =
        select("div:contains($label)>div.row__v>div.chips>a").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

    override suspend fun load(url: String): LoadResponse {
        val d = doc(url)
        val title = d.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.ifBlank { null }
            ?: d.selectFirst("h1")?.text()?.trim() ?: d.title()
        val poster = d.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
        val plot = d.selectFirst("div.desc>p")?.text()
            ?.replace("Premium PPM allows us to bring you the best Adult content from the hottest studios.", "")
            ?.substringBefore("for every second watched.")?.trim()?.ifBlank { null }
        val body = d.body()
        val year = body.chips("Release Year").firstOrNull()?.toIntOrNull()
        val tags = body.chips("Genres") + body.chips("Studio")
        val actors = body.chips("Pornstars").map { ActorData(Actor(it)) }
        val recs = d.select("article.card").mapNotNull { it.toSearchResult() }.filter { it.url != url }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.actors = actors
            recommendations = recs
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Server(val u: String? = null, val l: String? = null)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val raw = doc(data).selectFirst("section.hlm[data-servers]")?.attr("data-servers") ?: return false
        val servers = runCatching { parseJson<List<Server>>(raw) }.getOrNull().orEmpty()
            .mapNotNull { s -> s.u?.replace("\\/", "/")?.trim()?.takeIf { it.startsWith("http") }?.let { it to s.l } }
            .distinctBy { it.first }

        var found = false
        for ((url, label) in servers) {
            val ok = runCatching { loadExtractor(url, "$mainUrl/", subtitleCallback, callback) }.getOrDefault(false)
            if (ok) { found = true; continue }
            // hosts Cloudstream has no extractor for: let a WebView play the embed and catch the stream
            val media = runCatching {
                app.get(
                    url, referer = "$mainUrl/",
                    interceptor = WebViewResolver(Regex("""\.m3u8|\.mp4(\?|$)|/stream/""")),
                    timeout = 30_000L
                ).url
            }.getOrNull()
            if (media != null && media != url && (media.contains(".m3u8") || media.contains(".mp4") || media.contains("/stream/"))) {
                val host = label?.ifBlank { null } ?: url.substringAfter("://").substringBefore("/")
                callback(
                    newExtractorLink(name, "$name - $host", media,
                        if (media.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        referer = url.substringBefore("://") + "://" + url.substringAfter("://").substringBefore("/") + "/"
                        quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }
        return found
    }
}
