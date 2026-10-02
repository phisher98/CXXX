package com.CXXX

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

class AllPornStream : MainAPI() {
    override var mainUrl = "https://allpornstream.com"
    override var name = "AllPornStream"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "?studio=ElegantAngel" to "Elegant Angel",
        "?studio=OnlyFans" to "OnlyFans",
        "?studio=DadCrush" to "Dad Crush",
        "?studio=Shoplyfter" to "Shoplyfter",
        "?studio=cum-4-k" to "Cum (4K)",
        "?studio=EvilAngel" to "Evil Angel",
        "?studio=Blacked" to "Blacked",
        "?studio=Tushy" to "Tushy",
        "?studio=Milfy" to "Milfy",
        "?studio=BrazzersExxtra" to "Brazzers Exxtra",
        "?studio=SexMex" to "SexMex",
        "?studio=PureTaboo" to "Pure Taboo",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // request.data already starts with '?', so append directly without extra slash
        val url = "$mainUrl/${request.data}&page=$page"
        val doc = app.get(url).document

        val json = doc.select("script[type=application/ld+json]")
            .firstOrNull { it.data().contains("ItemList") }
            ?.data()

        if (json.isNullOrBlank()) {
            return newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = emptyList(),
                    isHorizontalImages = true
                ),
                hasNext = false
            )
        }

        val root = runCatching { mapper.readValue(json, HomePosts::class.java) }.getOrNull()
        val home = root?.itemListElement?.map { it.toSearchResult() }.orEmpty()

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun ItemListElement.toSearchResult(): SearchResponse {
        val title = name.trim()
        val hrefRaw = url.trim()

        val href = if (hrefRaw.startsWith("http")) {
            hrefRaw
        } else {
            "$mainUrl$hrefRaw"
        }

        val poster = thumbnailUrl.firstOrNull()?.takeIf { it.isNotBlank() }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (page in 1..3) {
            val doc = app.get("$mainUrl/?search=${query.encodeUri()}&page=$page").document
            val json = doc.select("script[type=application/ld+json]")
                .firstOrNull { it.data().contains("ItemList") }
                ?.data() ?: break

            val root = runCatching { mapper.readValue(json, HomePosts::class.java) }.getOrNull() ?: break
            val results = root.itemListElement.map { it.toSearchResult() }
            if (results.isEmpty()) break
            searchResponse.addAll(results)
        }
        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        // Extract title from og:title or page title
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.title().substringBefore(" | ").trim()

        // Extract poster from og:image or first apstream preview image
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: Regex("""https://img\.apstream\.org/nzb/[^"\\]+preview_[^"\\]+\.webp""")
                .find(doc.html())?.value

        // Extract description
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")

        // Extract tags from category links
        val tags = doc.select("a[href*='/categories/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        // Extract embed URLs via regex from the Next.js page source
        val pageHtml = doc.html()
        val embedUrls = Regex("""embed_url\\?":\\?"(https?:[^\\"]+)""")
            .findAll(pageHtml)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        val hrefs = embedUrls.toJson()

        return newMovieLoadResponse(title, url, TvType.NSFW, hrefs) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val parsedList = runCatching {
            data.fromJson<List<String>>()
        }.getOrNull() ?: if (data.startsWith("http")) listOf(data) else emptyList()

        parsedList.map { url ->
            launch {
                runCatching {
                    Log.d("Phisher", url)
                    loadExtractor(url, "$mainUrl/", subtitleCallback, callback)
                }
            }
        }.joinAll()

        true
    }

    companion object {
        private val gson = Gson()
        private val mapper = jacksonObjectMapper().apply {
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
        private fun String.encodeUri(): String =
            java.net.URLEncoder.encode(this, "UTF-8")
    }

    private inline fun <reified T> String.fromJson(): T =
        gson.fromJson(this, object : TypeToken<T>() {}.type)
}
