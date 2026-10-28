package com.familypornhd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.jsoup.nodes.Element

/**
 * familypornhd.com - WordPress ("bimber" theme). Listings are article cards; each post embeds a
 * "Fire HLS Player" (firevideoplayer.com) iframe such as https://<host>/video/<hash>. The player
 * exposes the stream with a POST to /player/index.php?data=<hash>&do=getVideo whose JSON carries
 * "securedLink" - the signed master.m3u8 - so no decryption is needed.
 */
class FamilyPornHD : MainAPI() {
    override var mainUrl = "https://familypornhd.com"
    override var name = "FamilyPornHD"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    // "" is the site root (latest). Each row pages with /<path>/page/N/.
    override val mainPage = mainPageOf(
        "" to "Latest",
        "category/family-therapy-porn2/" to "Family Therapy",
        "category/stepsiblingscaughtporn/" to "Step Siblings Caught",
        "category/mypervyfamilyporn/" to "My Pervy Family",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = mainUrl.trimEnd('/') + "/" + request.data.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val url = if (page > 1) "${base}page/$page/" else base

        val doc = app.get(url).document
        val items = doc.select("article").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        val hasNext = items.isNotEmpty() &&
            doc.selectFirst("link[rel=next], .nav-links a.next, .g1-pagination a.next, a.g1-load-more[href]") != null

        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = app.get("$mainUrl/?s=$q").document
        return doc.select("article").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = (doc.selectFirst("h1.entry-title")?.text() ?: doc.selectFirst("h1")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: "Video").trim()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst(".entry-content p")?.text()
        val tags = doc.select("a[rel=tag], a[href*=/category/]").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data).text
        var found = false

        // Fire HLS Player embeds: https://<host>/(video|embed|v|e)/<hash> (slashes may be escaped)
        val embedRe = Regex("""https?:(?:\\?/){2}([a-z0-9.\-]+)(?:\\?/)(?:video|embed|v|e)(?:\\?/)([A-Za-z0-9]{16,})""", RegexOption.IGNORE_CASE)
        val seen = HashSet<String>()
        embedRe.findAll(html).forEach { m ->
            val host = m.groupValues[1].lowercase()
            val hash = m.groupValues[2]
            if (host.contains("familypornhd")) return@forEach
            if (!seen.add("$host/$hash")) return@forEach
            val link = resolveFirePlayer(host, hash, data)
            if (link != null) {
                callback.invoke(
                    newExtractorLink(name, name, link, ExtractorLinkType.M3U8) {
                        this.referer = "https://$host/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("Origin" to "https://$host")
                    }
                )
                found = true
            }
        }

        // fallback: hand any other iframe to Cloudstream's built-in extractors
        if (!found) {
            Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
                val src = m.groupValues[1].replace("&amp;", "&")
                if (src.startsWith("http") && !src.contains("familypornhd")) {
                    if (loadExtractor(src, "$mainUrl/", subtitleCallback, callback)) found = true
                }
            }
        }

        return found
    }

    /** POST the player's getVideo endpoint and pull the signed master.m3u8 out of its JSON. */
    private suspend fun resolveFirePlayer(host: String, hash: String, pageRef: String): String? {
        return try {
            val embedUrl = "https://$host/video/$hash"
            // priming GET sets the fireplayer_player cookie the POST expects
            val cookies = try {
                app.get(embedUrl, referer = pageRef).cookies
            } catch (_: Exception) {
                emptyMap()
            }
            val body = app.post(
                "https://$host/player/index.php?data=$hash&do=getVideo",
                headers = mapOf(
                    "Origin" to "https://$host",
                    "Referer" to embedUrl,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "*/*",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                ),
                cookies = cookies,
                data = mapOf("hash" to hash, "r" to "$mainUrl/")
            ).text
            (Regex(""""securedLink"\s*:\s*"([^"]+)"""").find(body)
                ?: Regex(""""(?:videoSource|file)"\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(body))
                ?.groupValues?.get(1)?.replace("\\/", "/")?.takeIf { it.startsWith("http") }
        } catch (e: Exception) {
            null
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst(".entry-title a, a[rel=bookmark]") ?: return null
        val href = a.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = a.text().trim().takeIf { it.isNotBlank() } ?: return null
        val poster = selectFirst("img.wp-post-image, img")?.let { pickImage(it) }
        return newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) { this.posterUrl = poster }
    }

    /** bimber lazy-loads list thumbnails; take the largest srcset entry, else data-src/src. */
    private fun pickImage(img: Element): String? {
        val srcset = img.attr("srcset")
        if (srcset.isNotBlank()) {
            srcset.split(",").mapNotNull {
                val parts = it.trim().split(Regex("\\s+"))
                val u = parts.getOrNull(0) ?: return@mapNotNull null
                val w = parts.getOrNull(1)?.removeSuffix("w")?.toIntOrNull() ?: 0
                u to w
            }.maxByOrNull { it.second }?.first?.let { if (it.isNotBlank()) return it }
        }
        return img.attr("data-src").ifBlank { img.attr("src") }.takeIf { it.isNotBlank() }
    }
}
