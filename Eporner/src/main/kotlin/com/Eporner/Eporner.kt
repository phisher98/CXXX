package com.Eporner

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.math.BigInteger
import java.net.URLEncoder

class Eporner : MainAPI() {
    override var mainUrl              = "https://www.eporner.com"
    override var name                 = "Eporner"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "" to "Recent Videos",
        "best-videos" to "Best Videos",
        "top-rated" to "Top Rated",
        "most-viewed" to "Most Viewed",
        "cat/milf" to "Milf",
        "cat/japanese" to "Japanese",
        "cat/desi" to "Desi",
        "cat/hd-1080p" to "1080 Porn",
        "cat/4k-porn" to "4K Porn",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            request.data.isBlank() && page <= 1 -> "$mainUrl/"
            request.data.isBlank() -> "$mainUrl/$page/"
            page <= 1 -> "$mainUrl/${request.data}/"
            else -> "$mainUrl/${request.data}/$page/"
        }
        val document = app.get(url).document
        val home = document.select("div.mb").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home.distinctBy { it.url },
                isHorizontalImages = true
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElem = this.selectFirst("div.mbcontent a, div.mbunder p.mbtit a, a[href*='/video-']") ?: return null
        val href = fixUrlNull(linkElem.attr("href")) ?: return null
        val titleText = this.selectFirst("div.mbunder p.mbtit a")?.text()
            ?: linkElem.attr("title")
        if (titleText.isNullOrBlank()) return null
        val title = fixTitle(titleText).trim()

        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-original")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() }
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    private suspend fun apiSearch(query: String, page: Int): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/api/v2/video/search?query=$encoded&per_page=30&page=$page&thumbsize=big&gay=0&lq=1&format=json"
        val body = app.get(url, referer = "$mainUrl/").text
        val videos = JSONObject(body).optJSONArray("videos") ?: return emptyList()

        val results = mutableListOf<SearchResponse>()
        for (i in 0 until videos.length()) {
            val video = videos.optJSONObject(i) ?: continue
            val link = video.optString("url").takeIf { it.isNotBlank() } ?: continue
            val title = video.optString("title").takeIf { it.isNotBlank() } ?: continue
            val poster = video.optJSONObject("default_thumb")?.optString("src")?.takeIf { it.isNotBlank() }
                ?: video.optJSONArray("thumbs")?.optJSONObject(0)?.optString("src")?.takeIf { it.isNotBlank() }

            results.add(
                newMovieSearchResponse(fixTitle(title), fixUrl(link), TvType.NSFW) {
                    this.posterUrl = fixUrlNull(poster)
                }
            )
        }
        return results
    }

    private suspend fun htmlSearch(query: String, page: Int): List<SearchResponse> {
        val subquery = query.trim().replace(Regex("[^a-zA-Z0-9]+"), "-").trim('-')
        if (subquery.isBlank()) return emptyList()
        val document = app.get("$mainUrl/search/$subquery/$page/").document
        return document.select("div.mb").mapNotNull { it.toSearchResult() }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        for (page in 1..2) {
            val api = runCatching { apiSearch(query, page) }.getOrDefault(emptyList())
            val pageResults = if (api.isNotEmpty()) api else htmlSearch(query, page)
            val newItems = pageResults.filterNot { item -> results.any { it.url == item.url } }
            if (newItems.isEmpty()) break
            results.addAll(newItems)
        }
        return results.distinctBy { it.url }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val api = runCatching { apiSearch(query, page) }.getOrDefault(emptyList())
        val results = if (api.isNotEmpty()) api else runCatching { htmlSearch(query, page) }.getOrDefault(emptyList())
        return newSearchResponseList(results.distinctBy { it.url }, results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: document.title().substringBefore(" - EPORNER").trim()
        val poster = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val tags = document.select("div.video-tags a, a[href*='/cat/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val recommendations = document.select("div.mb")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .filter { it.url != url }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return runCatching {
            val doc = app.get(data).text
            val vid = Regex("""EP\.video\.player\.vid\s*=\s*'([^']+)'""").find(doc)?.groupValues?.getOrNull(1)
                ?: Regex("""/video-([a-zA-Z0-9]+)/""").find(data)?.groupValues?.getOrNull(1)
                ?: return false
            val hash = Regex("""EP\.video\.player\.hash\s*=\s*'([^']+)'""").find(doc)?.groupValues?.getOrNull(1)
                ?: return false

            val encodedHash = base36(hash)
            val url = "https://www.eporner.com/xhr/video/$vid?hash=$encodedHash"
            val json = app.get(url, referer = "$mainUrl/").text
            val jsonObject = JSONObject(json)
            val sources = jsonObject.optJSONObject("sources") ?: return false
            val mp4Sources = sources.optJSONObject("mp4") ?: return false
            val qualities = mp4Sources.keys()
            while (qualities.hasNext()) {
                val quality = qualities.next() as String
                val sourceObject = mp4Sources.optJSONObject(quality) ?: continue
                val src = sourceObject.optString("src")
                if (src.isBlank()) continue
                val labelShort = sourceObject.optString("labelShort")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = if (labelShort.isNotBlank()) "$name $labelShort" else name,
                        url = src,
                        type = INFER_TYPE
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = getIndexQuality(labelShort)
                    }
                )
            }
            true
        }.getOrDefault(false)
    }

    private fun base36(hash: String): String {
        return if (hash.length >= 32) {
            val part1 = BigInteger(hash.substring(0, 8), 16).toString(36)
            val part2 = BigInteger(hash.substring(8, 16), 16).toString(36)
            val part3 = BigInteger(hash.substring(16, 24), 16).toString(36)
            val part4 = BigInteger(hash.substring(24, 32), 16).toString(36)
            part1 + part2 + part3 + part4
        } else {
            hash
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}
