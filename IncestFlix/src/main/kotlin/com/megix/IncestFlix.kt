package com.megix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document

class IncestFlix : MainAPI() {
    override var mainUrl = "https://www.incestflix.com"
    override var name = "IncestFlix"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded
    private val ua = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"

    // Static homepage sections for specific tags requested
    override val mainPage = mainPageOf(
        "$mainUrl/tag/Reluctant/" to "Reluctant",
        "$mainUrl/tag/BS/" to "BS Brother, Sister",
        "$mainUrl/tag/MS/" to "MS Mother, Son",
        "$mainUrl/tag/FD/" to "FD Father, Daughter",
        "$mainUrl/tag/MD/" to "MD Mother, Daughter",
        "$mainUrl/random" to "Random",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = if (request.data.endsWith("/")) request.data else request.data + "/"
        val url = if (page <= 1) base else base + "page/$page/"
        val res = app.get(url, headers = mapOf("User-Agent" to ua), referer = mainUrl, allowRedirects = true)
        val document = res.document
        val pageOrigin = originOf(res.url)

        // Special-case random: resolve to the resulting watch page and show it as a single item
        if (request.data.endsWith("/random")) {
            val finalWatch = document.selectFirst("meta[property=og:url]")?.attr("content")
                ?: document.selectFirst("a[href*=/watch/]")?.attr("abs:href")
                ?: res.url
            if (!finalWatch.isNullOrBlank()) {
                val wdoc = app.get(finalWatch, headers = mapOf("User-Agent" to ua), referer = mainUrl).document
                val wtitle = wdoc.selectFirst("meta[property=og:title]")?.attr("content") ?: wdoc.title()
                val wposter = wdoc.selectFirst("meta[property=og:image]")?.attr("content")?.let { normalizeUrl(it) }
                val one = newMovieSearchResponse(wtitle.ifBlank { finalWatch }, finalWatch, TvType.Movie) {
                    this.posterUrl = wposter
                    this.posterHeaders = mapOf(
                        Pair("referer", "$mainUrl/"),
                        Pair("User-Agent", ua)
                    )
                }
                return newHomePageResponse(
                    list = HomePageList(
                        name = request.name,
                        list = listOf(one),
                        isHorizontalImages = true
                    ),
                    hasNext = true
                )
            }
        }

        // Tag pages sometimes use different paths (e.g. /video/..). Cover both.
        val anchors = document.select("a[href^=/watch], a[href*=/watch/], a[href*=/video/]")
        val built = anchors.mapIndexedNotNull { idx, a ->
            val href = a.attr("abs:href").ifBlank { normalizeUrl(a.attr("href")) }
            if (href.isBlank()) {
                null
            } else {
                val rawTitle = a.attr("title").ifBlank { a.ownText().ifBlank { a.text() } }.trim()
                val title = if (rawTitle.isNotBlank()) rawTitle else href.substringAfterLast('/').replace('-', ' ').trim().ifBlank { href }

            // Infer poster locally: prefer a larger card root if present
            val card = (a.parents().firstOrNull { parent ->
                val cls = parent.className()
                cls.contains("video-item") || cls.contains("post") || cls.contains("thumb") ||
                cls.contains("item") || parent.tagName().equals("article", true)
            } ?: a.parent()) ?: a
            val posterCandidates = mutableListOf<String>()
            // overlays / background-image styles (restrict to inside card only)
            card.select("div.video-overlay-click").forEach { e -> posterCandidates.add(e.attr("style")) }
            card.select("[style*=background-image]").forEach { posterCandidates.add(it.attr("style")) }
            // explicit attributes
            listOf("src", "data-src", "data-lazy-src", "data-original", "data-bg", "data-thumb", "data-thumbnail").forEach { attr ->
                card.select("img[$attr], [${attr}], source[$attr]").firstOrNull()?.let { el ->
                    val v = el.attr("abs:$attr").ifBlank { el.attr(attr) }
                    if (v.isNotBlank()) posterCandidates.add(v)
                }
            }
            // covers pattern from closest surfaces (any host): anchor -> card -> siblings
            runCatching {
                val coversRegex = Regex(
                    "((?:https?:)?//[^'\"\\s)]+)?/covers/[^'\"\\s)]+\\.(?:png|jpe?g|webp)",
                    RegexOption.IGNORE_CASE
                )
                // 1) anchor markup
                coversRegex.findAll(a.outerHtml()).firstOrNull()?.let { posterCandidates.add(resolveCoversUrl(it.value, pageOrigin)) }
                // 2) inside the card subtree
                if (posterCandidates.isEmpty()) {
                    coversRegex.findAll(card.outerHtml()).firstOrNull()?.let { posterCandidates.add(resolveCoversUrl(it.value, pageOrigin)) }
                }
                // 3) direct siblings only
                if (posterCandidates.isEmpty()) {
                    val sib = card.siblingElements().joinToString("\n") { it.outerHtml() }
                    coversRegex.findAll(sib).firstOrNull()?.let { posterCandidates.add(resolveCoversUrl(it.value, pageOrigin)) }
                }
            }
            // srcset handling (pick first url)
            card.select("img[srcset], source[srcset]").firstOrNull()?.attr("srcset")?.let { ss ->
                val first = ss.split(',').map { it.trim().substringBefore(' ') }.firstOrNull()
                if (!first.isNullOrBlank()) posterCandidates.add(first)
            }

            // Normalize all candidates to absolute URLs for reliable selection
            val normalized = posterCandidates.mapNotNull { raw ->
                val r = raw.trim()
                val fromStyle = extractBgUrl(r)
                val candidate = when {
                    r.contains("/covers/", true) -> resolveCoversUrl(r, pageOrigin)
                    !fromStyle.isNullOrBlank() -> normalizeUrl(fromStyle)
                    else -> normalizeUrl(r)
                }
                candidate.takeIf { it.startsWith("http") }
            }
            val poster = normalized.firstOrNull { it.contains("/covers/", true) } ?: normalized.firstOrNull()
            val norm = poster
                val item = newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = norm
                    this.posterHeaders = mapOf(
                        Pair("referer", "$mainUrl/"),
                        Pair("User-Agent", ua)
                    )
                }
                if (item.posterUrl.isNullOrBlank() && idx < 8) {
                    runCatching {
                        val wdoc = app.get(
                            href,
                            headers = mapOf("User-Agent" to ua),
                            referer = mainUrl,
                            allowRedirects = true,
                            timeout = 2000
                        ).document
                        val og = wdoc.selectFirst("meta[property=og:image]")?.attr("content")
                        if (!og.isNullOrBlank()) {
                            val n = normalizeUrl(og)
                            item.posterUrl = n
                            item.posterHeaders = mapOf(
                                Pair("referer", "$mainUrl/"),
                                Pair("User-Agent", ua)
                            )
                        }
                    }
                }
                item
            }
        }
            .distinctBy { it.url }
            .take(30)

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = built,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResultWithPoster(): SearchResponse? {
        val href = this.attr("abs:href").ifBlank {
            val rel = this.attr("href").ifBlank { return null }
            normalizeUrl(rel)
        }
        val rawTitle = this.attr("title").ifBlank {
            this.ownText().ifBlank { this.text() }
        }.trim()
        val title = if (rawTitle.isNotBlank()) rawTitle else href.substringAfterLast('/').replace('-', ' ').trim().ifBlank { href }

        // Try to infer poster from nearby elements (align with homepage card detection)
        val card = (this.parents().firstOrNull { parent ->
            val cls = parent.className()
            cls.contains("video-item") || cls.contains("post") || cls.contains("thumb") ||
            cls.contains("item") || parent.tagName().equals("article", true)
        } ?: this.parent()) ?: this
        val posterCandidates = mutableListOf<String>()
        // Compute current page origin once for URL resolution
        val origin = originOf(this.baseUri())
        // 1) Prefer anything directly inside the anchor subtree (strict)
        listOf("src", "data-src", "data-lazy-src", "data-original", "data-bg", "data-thumb", "data-thumbnail").forEach { attr ->
            this.select("img[$attr], source[$attr]").firstOrNull()?.let { el ->
                val v = el.attr("abs:$attr").ifBlank { el.attr(attr) }
                if (v.isNotBlank()) posterCandidates.add(v)
            }
        }
        runCatching {
            val coversRegex = Regex(
                "((?:https?:)?//[^'\"\\s)]+)?/covers/[^'\"\\s)]+\\.(?:png|jpe?g|webp)",
                RegexOption.IGNORE_CASE
            )
            coversRegex.findAll(this.outerHtml()).firstOrNull()?.let { posterCandidates.add(resolveCoversUrl(it.value, origin)) }
        }
        // 2) If still empty, use card-local styles and attributes (no parents/siblings)
        if (posterCandidates.isEmpty()) {
            card.select("div.video-overlay-click").forEach { e -> posterCandidates.add(e.attr("style")) }
            card.select("[style*=background-image]").forEach { posterCandidates.add(it.attr("style")) }
            listOf("src", "data-src", "data-lazy-src", "data-original", "data-bg", "data-thumb", "data-thumbnail").forEach { attr ->
                card.select("img[$attr], source[$attr]").firstOrNull()?.let { el ->
                    val v = el.attr("abs:$attr").ifBlank { el.attr(attr) }
                    if (v.isNotBlank()) posterCandidates.add(v)
                }
            }
            runCatching {
                val coversRegex = Regex(
                    "((?:https?:)?//[^'\"\\s)]+)?/covers/[^'\"\\s)]+\\.(?:png|jpe?g|webp)",
                    RegexOption.IGNORE_CASE
                )
                coversRegex.findAll(card.outerHtml()).firstOrNull()?.let { posterCandidates.add(resolveCoversUrl(it.value, origin)) }
            }
        }
        // srcset handling (pick first url)
        card.select("img[srcset], source[srcset]").firstOrNull()?.attr("srcset")?.let { ss ->
            val first = ss.split(',').map { it.trim().substringBefore(' ') }.firstOrNull()
            if (!first.isNullOrBlank()) posterCandidates.add(first)
        }

        // Normalize all candidates to absolute URLs for reliable selection
        val normalized = posterCandidates.mapNotNull { raw ->
            val r = raw.trim()
            val fromStyle = extractBgUrl(r)
            val candidate = when {
                r.contains("/covers/", true) -> resolveCoversUrl(r, origin)
                !fromStyle.isNullOrBlank() -> normalizeUrl(fromStyle)
                else -> normalizeUrl(r)
            }
            candidate.takeIf { it.startsWith("http") }
        }
        val poster = normalized.firstOrNull { it.contains("/covers/", true) } ?: normalized.firstOrNull()

        val item = newMovieSearchResponse(title, href, TvType.Movie) {
            val norm = poster
            this.posterUrl = norm
            this.posterHeaders = mapOf(
                Pair("referer", "$mainUrl/"),
                Pair("User-Agent", ua)
            )
        }
        return item
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Tag-based search: /tag/{slug}/page/{i}
        val slug = query.trim().replace(Regex("\\s+"), "-")
        val out = mutableListOf<SearchResponse>()
        for (i in 1..10) {
            val url = if (i == 1) "$mainUrl/tag/$slug/" else "$mainUrl/tag/$slug/page/$i/"
            val doc = app.get(url, headers = mapOf("User-Agent" to ua), referer = mainUrl).document
            val results = doc.select("a[href^=/watch], a[href*=/watch/], a[href*=/video/]")
                .mapNotNull { it.toSearchResultWithPoster() }
            out.addAll(results)
            if (results.isEmpty()) break
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("User-Agent" to ua), referer = mainUrl).document
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.selectFirst("title")?.text()
            ?: name
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.let { normalizeUrl(it) }

        // Collect related items with targeted og:image fallback for missing posters (fast, capped)
        val recAnchors = document.select("a[href*=/watch/]")
        val recommendations = recAnchors.mapNotNull { a ->
            a.toSearchResultWithPoster()
        }
            .filter { it.url != url }
            .distinctBy { it.url }
            .take(20)

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = mapOf(
                Pair("referer", mainUrl),
                Pair("User-Agent", ua)
            )
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = mapOf("User-Agent" to ua), referer = mainUrl).document

        // Try common patterns: direct <video><source>, iframes to hosts, or anchors to files
        val candidates = mutableListOf<String>()

        // PRIORITY: dedicated player element
        doc.selectFirst("video#incflix-player")?.let { v ->
            val poster = v.attr("poster").takeIf { it.isNotBlank() }?.let { normalizeUrl(it) }
            // Possible layouts:
            // 1) <video id=incflix-player><source src=...></video>
            // 2) <video id=incflix-player ... /> <source src=...></video>
            // 3) <video id=incflix-player src=...>
            val srcAttr = v.attr("src").takeIf { it.isNotBlank() }?.let { normalizeUrl(it) }
            val childSource = v.selectFirst("source[src]")?.attr("src")?.let { normalizeUrl(it) }
            val siblingSource = v.nextElementSibling()?.takeIf { it.tagName().equals("source", true) }?.attr("src")?.let { normalizeUrl(it) }
            val genSibling = doc.selectFirst("video#incflix-player ~ source[src]")?.attr("src")?.let { normalizeUrl(it) }

            listOf(srcAttr, childSource, siblingSource, genSibling).filterNotNull().forEach { s ->
                if (s.isNotBlank()) candidates.add(s)
            }
            // poster handled in load() via og:image
        }

        // video > source[src]
        candidates.addAll(doc.select("video source[src]").map { normalizeUrl(it.attr("src")) })
        // video[src]
        candidates.addAll(doc.select("video[src]").map { normalizeUrl(it.attr("src")) })
        // data-src/data-video on video/source
        candidates.addAll(doc.select("video[data-src], source[data-src]").map { normalizeUrl(it.attr("data-src")) })
        candidates.addAll(doc.select("video[data-video]").map { normalizeUrl(it.attr("data-video")) })
        // iframes (rare but keep)
        candidates.addAll(doc.select("iframe[src]").map { normalizeUrl(it.attr("src")) })
        // anchors that look like media links
        candidates.addAll(
            doc.select("a[href]")
                .map { normalizeUrl(it.attr("href")) }
                .filter { it.contains(".m3u8") || it.contains(".mp4") }
        )

        // As a last resort, global <source src> on page (some templates place it next to a self-closed video)
        candidates.addAll(doc.select("source[src]").map { normalizeUrl(it.attr("src")) })

        // Parse inline scripts for direct sources
        runCatching {
            val scriptText = doc.select("script").joinToString("\n") { it.data() }
            val m3u8Regex = Regex("https?:\\/\\/[^'\"\\s)]+\\.m3u8")
            val mp4Regex = Regex("https?:\\/\\/[^'\"\\s)]+\\.mp4")
            candidates.addAll(m3u8Regex.findAll(scriptText).map { it.value }.toList())
            candidates.addAll(mp4Regex.findAll(scriptText).map { it.value }.toList())
        }

        val unique = candidates.filter { it.isNotBlank() }.distinct()

        if (unique.isEmpty()) return false

        unique.forEach { link ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = link
                )
            )
        }
        return true
    }

    private fun extractBgUrl(styleOrUrl: String): String? {
        val style = styleOrUrl.trim()
        if (style.startsWith("http")) return style
        val match = Regex("background-image\\s*:\\s*url\\((['\"]?)(.*?)\\1\\)", RegexOption.IGNORE_CASE)
            .find(style)
        return match?.groupValues?.getOrNull(2)
    }

    private fun normalizeUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return when {
            url.startsWith("//") -> "https:" + url
            url.startsWith("http") -> url
            else -> fixUrl(url)
        }
    }

    private fun originOf(u: String): String {
        val full = normalizeUrl(u)
        return try {
            val uri = java.net.URI(full)
            val scheme = uri.scheme ?: "https"
            val host = uri.host ?: return mainUrl
            "$scheme://$host"
        } catch (e: Throwable) {
            mainUrl
        }
    }

    private fun resolveCoversUrl(raw: String, origin: String): String {
        val s = raw.trim()
        val valOrUrl = extractBgUrl(s) ?: s
        return when {
            valOrUrl.startsWith("http") -> valOrUrl
            valOrUrl.startsWith("//") -> "https:" + valOrUrl
            valOrUrl.startsWith("/covers/") -> origin + valOrUrl
            else -> normalizeUrl(valOrUrl)
        }
    }
}
