package com.jacekun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.loadExtractor
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

class JavGuru : MainAPI() {
    private val globaltvType = TvType.NSFW

    override var name = "Jav Guru"
    override var mainUrl = "https://jav.guru"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasDownloadSupport = true
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val vpnStatus = VPNStatus.MightBeNeeded

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val all = ArrayList<HomePageList>()
        val url = if (page <= 1) mainUrl else "$mainUrl/page/$page/"
        val doc = app.get(url).document

        val items = doc.select("main.site-main div.row, div.inside-article").mapNotNull {
            val aa = it.selectFirst("div.imgg a, a") ?: return@mapNotNull null
            val link = fixUrlNull(aa.attr("href")) ?: return@mapNotNull null
            val img = aa.selectFirst("img")
            val name = img?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: it.selectFirst("h2, h3, a")?.text()
                ?: return@mapNotNull null
            val image = fixUrlNull(img?.attr("src") ?: img?.attr("data-src"))

            newMovieSearchResponse(name, link, globaltvType) {
                this.posterUrl = image
            }
        }.distinctBy { it.url }

        if (items.isNotEmpty()) {
            all.add(HomePageList("Latest JAV", items, isHorizontalImages = true))
        }

        return newHomePageResponse(all, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        val encoded = query.trim().replace(" ", "+")

        for (page in 1..3) {
            val url = if (page == 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded"
            val doc = app.get(url).document
            val results = doc.select("main.site-main div.row, div.inside-article").mapNotNull {
                val aa = it.selectFirst("div.imgg a, a") ?: return@mapNotNull null
                val href = fixUrlNull(aa.attr("href")) ?: return@mapNotNull null
                val img = aa.selectFirst("img")
                val title = img?.attr("alt")?.takeIf { it.isNotBlank() }
                    ?: it.selectFirst("h2, h3")?.text()
                    ?: return@mapNotNull null
                val image = fixUrlNull(img?.attr("src")?.trim('\'') ?: img?.attr("data-src"))

                newMovieSearchResponse(title, href, globaltvType) {
                    this.posterUrl = image
                }
            }
            val unique = results.filterNot { item -> searchResponse.any { it.url == item.url } }
            if (unique.isEmpty()) break
            searchResponse.addAll(unique)
        }

        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val poster = fixUrlNull(
            doc.selectFirst("div.large-screenimg img, meta[property='og:image']")?.attr("src")
                ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
        )
        val title = doc.selectFirst("h1.titl, meta[property='og:title']")?.text()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.title()
        val descript = doc.selectFirst("div.wp-content p, meta[property='og:description']")?.text()?.trim()

        val infometaList = doc.select("div.infometa div.infoleft ul li")
        val year = infometaList.firstOrNull { it.text().contains("Release Date", ignoreCase = true) }
            ?.text()?.let { Regex("""\b(19\d\d|20\d\d)\b""").find(it)?.value?.toIntOrNull() }

        val tags = doc.select("div.infometa a[href*='/genre/'], div.infometa a[href*='/tag/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val actors = doc.select("div.infometa a[href*='/actress/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val recommendations = doc.select("div.related-posts div.inside-article, div.row")
            .mapNotNull {
                val aa = it.selectFirst("div.imgg a, a") ?: return@mapNotNull null
                val link = fixUrlNull(aa.attr("href")) ?: return@mapNotNull null
                val name = aa.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
                    ?: it.selectFirst("h2, h3, a")?.text()
                    ?: return@mapNotNull null
                newMovieSearchResponse(name, link, globaltvType)
            }
            .distinctBy { it.url }
            .filter { it.url != url }

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = globaltvType,
            dataUrl = url,
        ).apply {
            this.apiName = this@JavGuru.name
            this.posterUrl = poster
            this.year = year
            this.plot = descript
            this.tags = tags
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    private fun resolveSearcho(page: String): String? {
        val cid = Regex("""cid:\s*'([a-z0-9]+)'""").find(page)?.groupValues?.get(1) ?: return null
        val rtype = Regex("""rtype:\s*'([a-z]+)'""").find(page)?.groupValues?.get(1) ?: return null
        val keys = Regex("""'(data-[a-z0-9]+)'""").findAll(page).map { it.groupValues[1] }.take(3).toList()
        if (keys.size < 3) return null
        val el = Regex("""id="$cid"[^>]*""").find(page)?.groupValues?.get(0) ?: return null
        val token = keys.mapNotNull { k ->
            Regex("""$k="([^"]*)"""").find(el)?.groupValues?.get(1)
        }.joinToString("")
        if (token.length < 6) return null
        return "$mainUrl/searcho/?${rtype}r=${token.reversed()}"
    }

    private fun extractPackedHls(body: String, embedUrl: String): String? {
        val unpacker = JsUnpacker(body)
        val unpacked = if (unpacker.detect()) unpacker.unpack() ?: body else body
        val hls4 = Regex(""""hls4"\s*:\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)
        val hls2 = Regex(""""hls2"\s*:\s*"(https[^"]+)"""").find(unpacked)?.groupValues?.get(1)
        return when {
            hls4 != null -> if (hls4.startsWith("http")) hls4
                else "https://" + embedUrl.substringAfter("//").substringBefore("/") + hls4
            else -> hls2
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageBody = app.get(data).text
        val servers = Regex(""""iframe_url":"([A-Za-z0-9+/=]+)"""").findAll(pageBody)
            .mapNotNull { runCatching { base64Decode(it.groupValues[1]) }.getOrNull() }
            .distinct()
            .toList()

        var found = false
        servers.forEachIndexed { index, searcho ->
            runCatching {
                val searchoPage = app.get(searcho, referer = "$mainUrl/").text
                val realSrc = resolveSearcho(searchoPage) ?: return@runCatching
                val resp = app.get(realSrc, referer = searcho)
                val embedUrl = resp.url

                if (embedUrl.contains("vide0.net")) {
                    if (loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)) found = true
                    return@runCatching
                }

                val link = extractPackedHls(resp.text, embedUrl) ?: return@runCatching
                found = true
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Server ${index + 1}",
                        url = link
                    ) {
                        this.referer = embedUrl
                        this.quality = Qualities.Unknown.value
                        this.type = ExtractorLinkType.M3U8
                    }
                )
            }
        }
        return found
    }

    companion object {
        private val gson = Gson()
        private inline fun <reified T> String.fromJson(): T =
            gson.fromJson(this, object : TypeToken<T>() {}.type)
    }
}