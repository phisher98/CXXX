package com.watchuu.xfantazy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import kotlin.random.Random

/**
 * xfantazy.com — Next.js behind Cloudflare, built from the captures of 2026-10-29 and 2026-10-30.
 *
 * Deliberately defensive about *loading*: a provider is constructed the moment the plugin loads,
 * and anything that runs in that constructor can take the whole plugin down with it. So this class
 * holds no state beyond the five MainAPI fields and the home rows — no lazy delegates, no compiled
 * Regex, no constants. Everything else is built inside the function that needs it.
 *
 * Endpoints (all seen in the captures):
 *   /top , /trending , /top?sort=mostViewed      listings, ?page=N for more
 *   /search/{query}                              search, ?page=N
 *   /video/{hru}?_rsc=…                          detail, carries the `videoInfo` object
 *   https://api.xfantazy.com/pub/client-token    sets the k2sAccessToken cookie
 *   https://api.xfantazy.com/pub/video-sources/{mongoId}
 *        → {"sources":[{"type":"video/mp4","label":"360p","src":"https://…"},
 *                      {"type":"video/mp4","label":"original","src":"blocked","blocked":true}]}
 *   Streams want `Referer: https://xfantazy.com/`; the free source is 360p, "original" is blocked.
 */
class XfantazyProvider : MainAPI() {
    override var mainUrl = "https://xfantazy.com"
    override var name = "XFantazy"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    // "path|query" — the part after the pipe is a fixed query, `page` is appended per request.
    override val mainPage = mainPageOf(
        "top" to "New",
        "trending" to "Trending",
        "top|sort=mostViewed" to "Most Viewed"
    )

    // ── Home ───────────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.substringBefore('|')
        val sort = request.data.substringAfter('|', "")
        val query = buildList {
            if (sort.isNotBlank()) add(sort)
            if (page > 1) add("page=$page")
        }.joinToString("&")

        val items = cards(payload(path, query))
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ── Search ─────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        if (q.isBlank()) return emptyList()

        val first = cards(payload("search/$q", ""))
        if (first.isEmpty()) return first
        // One more page merged in, so search returns a usable list without the newer
        // paginated-search API.
        val second = cards(payload("search/$q", "page=2"))
        return (first + second).distinctBy { it.url }
    }

    // ── Detail ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val path = url.removePrefix(mainUrl).trimStart('/').substringBefore('?')
        if (path.isBlank()) return null

        var body = fetch(rscUrl(path, ""), rsc = true).orEmpty()
        if (!body.contains("\"videoInfo\"")) {
            body = fetch("$mainUrl/$path").orEmpty().replace("\\\"", "\"")
        }
        val scope = body.substring(maxOf(body.indexOf("\"videoInfo\""), 0))

        val id = firstMatch(scope, """"id"\s*:\s*"([0-9a-f]{24})"""")
        val title = firstMatch(scope, """"title"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            ?.let(::unescape)
            ?: url.substringAfterLast('/').replace('-', ' ').trim().ifBlank { "Video" }
        val description = firstMatch(scope, """"description"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            ?.let(::unescape)
        val userFileId = firstMatch(scope, """"userFileId"\s*:\s*"([^"]+)"""")

        val tags = (namesIn(scope, "tags") + namesIn(scope, "categories")).distinct()

        return newMovieLoadResponse(title, url, TvType.NSFW, id ?: url) {
            this.posterUrl = userFileId?.let { "https://s.xfantazy.com/thumbnail/$it/w320h180/0" }
            this.plot = description
            this.tags = tags
        }
    }

    // ── Streams ────────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Normally load() hands over the mongo id. If the detail page could not be read it hands
        // over the page url instead, so fall back to resolving the id from the page itself.
        val id = videoIdOf(data) ?: resolveIdFromPage(data) ?: return false

        val apiHeaders = mapOf(
            "User-Agent" to userAgent(),
            "Accept" to "*/*",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/"
        )

        // The site fetches a client token first, and every video-sources request in the capture
        // carries the k2sAccessToken cookie that comes back with it. Send it explicitly rather
        // than relying on a shared cookie jar.
        val tokenCookies = runCatching {
            app.get("https://api.xfantazy.com/pub/client-token", headers = apiHeaders).cookies
        }.getOrNull().orEmpty()

        val json = runCatching {
            app.get(
                "https://api.xfantazy.com/pub/video-sources/$id",
                headers = apiHeaders,
                cookies = tokenCookies
            ).text
        }.getOrNull() ?: return false

        val sources = Regex(""""sources"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            .find(json)?.groupValues?.get(1) ?: return false

        val blocked = Regex(""""blocked"\s*:\s*true""")
        var found = false
        Regex("""\{[^{}]*\}""").findAll(sources).forEach { obj ->
            val o = obj.value
            if (blocked.containsMatchIn(o)) return@forEach
            val src = firstMatch(o, """"src"\s*:\s*"([^"]+)"""") ?: return@forEach
            if (!src.startsWith("http")) return@forEach
            val label = firstMatch(o, """"label"\s*:\s*"([^"]+)"""") ?: "360p"

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name ($label)",
                    url = src,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = getQualityFromName(label)
                    this.headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to userAgent())
                }
            )
            found = true
        }
        return found
    }

    /** A mongo id handed over as-is, or one embedded in a /video/… url. */
    private fun videoIdOf(data: String): String? = when {
        data.matches(Regex("[0-9a-f]{24}")) -> data
        else -> firstMatch(data, """/video/([0-9a-f]{24})""")
    }

    /** Last resort: read the page and take the id out of its videoInfo object. */
    private suspend fun resolveIdFromPage(data: String): String? {
        val url = when {
            data.startsWith("http") -> data
            data.startsWith("/") -> "$mainUrl$data"
            else -> "$mainUrl/video/$data"
        }
        val path = url.removePrefix(mainUrl).trimStart('/').substringBefore('?')
        if (path.isBlank()) return null

        var body = fetch(rscUrl(path, ""), rsc = true).orEmpty()
        if (!body.contains("\"videoInfo\"")) {
            body = fetch(url).orEmpty().replace("\\\"", "\"")
        }
        val scope = body.substring(maxOf(body.indexOf("\"videoInfo\""), 0))
        return firstMatch(scope, """"id"\s*:\s*"([0-9a-f]{24})"""")
    }

    // ── Transport ──────────────────────────────────────────────────────────────

    private fun userAgent(): String =
        "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"

    private suspend fun fetch(url: String, rsc: Boolean = false): String? {
        val headers = mutableMapOf(
            "User-Agent" to userAgent(),
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to "$mainUrl/"
        )
        if (rsc) headers["RSC"] = "1"

        val plain = runCatching { app.get(url, headers = headers, timeout = 25L).text }.getOrNull()
        if (plain != null && !isChallenge(plain)) return plain

        // Cloudflare answers the document pages with a challenge; let the app's own solver try.
        val solved = runCatching {
            app.get(url, headers = headers, interceptor = CloudflareKiller(), timeout = 30L).text
        }.getOrNull()
        if (solved != null && !isChallenge(solved)) return solved
        return null
    }

    private fun isChallenge(body: String): Boolean {
        if (body.length > 4000) return false
        return body.contains("Just a moment", ignoreCase = true) ||
            body.contains("challenge-platform") ||
            body.contains("cf_chl_opt") ||
            body.contains("Attention Required", ignoreCase = true)
    }

    private fun rscUrl(path: String, query: String): String {
        val base = if (path.isBlank()) "$mainUrl/" else "$mainUrl/$path"
        val q = buildList {
            if (query.isNotBlank()) add(query)
            add("_rsc=${randomToken()}")
        }.joinToString("&")
        return "$base?$q"
    }

    private fun randomToken(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..16).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    /** RSC payload when it works, otherwise the page with the escaped flight data unescaped. */
    private suspend fun payload(path: String, query: String): String {
        fetch(rscUrl(path, query), rsc = true)?.let { if (it.contains("userFileId")) return it }

        val plainUrl = if (path.isBlank()) "$mainUrl/" else "$mainUrl/$path"
        val withQuery = if (query.isBlank()) plainUrl else "$plainUrl?$query"
        return fetch(withQuery)?.replace("\\\"", "\"") ?: ""
    }

    // ── Cards ──────────────────────────────────────────────────────────────────

    private fun cards(payload: String): List<SearchResponse> {
        if (payload.isBlank()) return emptyList()

        return Regex(""""thumb"\s*:\s*\{([^{}]*)\}""").findAll(payload).mapNotNull { match ->
            val block = match.groupValues[1]
            val id = firstMatch(block, """"id"\s*:\s*"([0-9a-f]{24})"""") ?: return@mapNotNull null
            val rawTitle = firstMatch(block, """"title"\s*:\s*"((?:[^"\\]|\\.)*)"""")
                ?: return@mapNotNull null
            val title = unescape(rawTitle).trim()
            if (title.isBlank()) return@mapNotNull null

            val userFileId = firstMatch(block, """"userFileId"\s*:\s*"([^"]+)"""")
            val hru = firstMatch(block, """"hru"\s*:\s*"([^"]+)"""")
            val url = if (!hru.isNullOrBlank()) fixUrl("/video/$hru") else fixUrl("/video/$id")

            newMovieSearchResponse(title, url, TvType.NSFW) {
                this.posterUrl = userFileId?.let { "https://s.xfantazy.com/thumbnail/$it/w320h180/0" }
            }
        }.distinctBy { it.url }.toList()
    }

    /** `"tags":[{…},{"name":"…"}]` → the names inside that array. */
    private fun namesIn(scope: String, field: String): List<String> {
        val array = Regex("\"$field\"\\s*:\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
            .find(scope)?.groupValues?.get(1) ?: return emptyList()
        return Regex(""""name"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            .findAll(array).map { unescape(it.groupValues[1]).trim() }
            .filter { it.isNotBlank() }.toList()
    }

    private fun firstMatch(text: String, regex: String): String? =
        Regex(regex).find(text)?.groupValues?.get(1)

    private fun unescape(value: String): String {
        if (!value.contains('\\')) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val n = value[i + 1]) {
                    'n', 'r', 't' -> { out.append(' '); i += 2 }
                    'u' -> {
                        val hex = value.substring(i + 2, minOf(i + 6, value.length))
                        val code = hex.toIntOrNull(16)
                        if (code != null) out.append(code.toChar())
                        i += 6
                    }
                    else -> { out.append(n); i += 2 }
                }
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
