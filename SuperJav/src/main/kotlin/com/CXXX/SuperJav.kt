package com.CXXX

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.extractors.VidhideExtractor

class SuperJav : MainAPI() {
    override var mainUrl              = "https://supjav.com"
    override var name                 = "SupJav"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded
    val subtitleCatUrl = "https://www.subtitlecat.com"
    override val mainPage = mainPageOf(
        "category/censored-jav" to "Censored Jav",
        "category/english-subtitles" to "English Jav",
        "tag/4k" to "4K",
        "tag/stepmother" to "Step Mother",
        "tag/tits" to "Tits",
        "reducing-mosaic" to "Reducing Mosaic",
        "tag/4k" to "",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}/page/$page").document
        val home     = document.select("div.post").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title      = this.selectFirst("a")?.attr("title") ?: "Unknown"
        val href       = this.selectFirst("a")!!.attr("href")
        val posterUrl  = this.select("a > img").attr("data-original")
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..2) {
            val document = app.get("${mainUrl}/page/$i?s=$query").document
            val results = document.select("div.post").mapNotNull { it.toSearchResult() }
            searchResponse.addAll(results)

            if (results.isEmpty()) break
        }

        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title        = document.selectFirst("div.archive-title h1")?.text() ?: "Unknown"
        val poster          = fixUrlNull(document.selectFirst("div.post-meta img")?.attr("src"))
        val description     = document.selectFirst(".div.post-meta h2")?.text()
        val tags          = document.select("div.tags a").map { it.text() }
        val recommendations = document.select("div.content:contains(You May Also Like) div.posts.clearfix div.post").mapNotNull { it.toSearchResult() }
        return newMovieLoadResponse(title.trim(), url, TvType.NSFW, url) {
            this.posterUrl       = poster
            this.plot            = description
            this.tags            = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        app.get(data).document.select("a.btn-server").amap {
            val id=it.attr("data-link").toCharArray().reversed().joinToString("")
            val fetchurl="https://lk1.supremejav.com/supjav.php?c=$id"
            val sourcefetch= app.get(fetchurl, referer = fetchurl, allowRedirects = false).headers["location"] ?:""
            Log.d("Phisher",sourcefetch)
            loadExtractor(sourcefetch, referer = mainUrl,subtitleCallback, callback)
        }

        try {
            val doc= app.get(data).document
            val title = doc.select("head > title").text()
            val javCode = "([a-zA-Z]+-\\d+)".toRegex().find(title)?.groups?.get(1)?.value
            if(!javCode.isNullOrEmpty())
            {
                val query = "$subtitleCatUrl/index.php?search=$javCode"
                val subDoc = app.get(query, timeout = 15).document
                val subList = subDoc.select("td a")
                for(item in subList)
                {
                    if(item.text().contains(javCode))
                    {
                        val fullUrl = "$subtitleCatUrl/${item.attr("href")}"

                        val pDoc = app.get(fullUrl, timeout = 10).document
                        val sList = pDoc.select(".col-md-6.col-lg-4")
                        for(item in sList)
                        {
                            try {
                                Log.d("Phisher",item.toString())

                                val language = item.select(".sub-single span:nth-child(2)").text()
                                val text = item.select(".sub-single span:nth-child(3) a")
                                if(text.size > 0 && text[0].text() == "Download")
                                {
                                    val url = "$subtitleCatUrl${text[0].attr("href")}"
                                    subtitleCallback.invoke(
                                        SubtitleFile(
                                            language.replace("\uD83D\uDC4D \uD83D\uDC4E",""),  // Use label for the name
                                            url     // Use extracted URL
                                        )
                                    )
                                }
                            } catch (_: Exception) { }
                        }

                    }
                }
            }
        } catch (_: Exception) { }

        return true
    }
}

@Suppress("NAME_SHADOWING")
open class Voe : ExtractorApi() {
    override val name = "Voe"
    override val mainUrl = "https://voe.sx"
    override val requiresReferer = true

    private val linkRegex =
        "(http|https)://([\\w_-]+(?:\\.[\\w_-]+)+)([\\w.,@?^=%&:/~+#-]*[\\w@?^=%&/~+#-])".toRegex()
    private val base64Regex = Regex("'.*'")
    private val redirectRegex = Regex("""window.location.href = '([^']+)';""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("VoeExtractor", "Initial URL: $url")

        var res = app.get(url, referer = referer).document
        Log.d("VoeExtractor", "Response received for initial URL")

        // Check for window.location.href in the script data
        val redirectUrl = redirectRegex.find(res.data())?.groupValues?.get(1)

        if (redirectUrl != null) {
            // If window.location.href is found, perform another GET request to the redirected URL
            Log.d("VoeExtractor", "Redirect found: $redirectUrl")
            res = app.get(redirectUrl, referer = referer).document
            Log.d("VoeExtractor", "Response received for redirected URL")
        }

        val script =
            if (!res.select("script").firstOrNull() { it.data().contains("sources =") }?.data()
                    .isNullOrEmpty()
            ) {
                res.select("script").find { it.data().contains("sources =") }?.data()
            } else {
                redirectRegex.find(res.data())?.groupValues?.get(1)?.let { redirectUrl ->
                    Log.d("VoeExtractor", "Redirecting to: $redirectUrl")
                    app.get(redirectUrl, referer = referer).document.select("script")
                        .find { it.data().contains("sources =") }?.data()
                }
            }

        Log.d("VoeExtractor", "Script data extracted: $script")

        val link =
            Regex("[\"']hls[\"']:\\s*[\"'](.*)[\"']").find(script ?: return)?.groupValues?.get(1)

        Log.d("VoeExtractor", "Video link found: $link")

        val videoLinks = mutableListOf<String>()

        if (!link.isNullOrBlank()) {
            videoLinks.add(
                when {
                    linkRegex.matches(link) -> link
                    else -> base64Decode(link)
                }
            )
        } else {
            val link2 = base64Regex.find(script)?.value ?: return
            val decoded = base64Decode(link2)
            val videoLinkDTO = AppUtils.parseJson<WcoSources>(decoded)
            videoLinkDTO.let { videoLinks.add(it.toString()) }
        }

        videoLinks.forEach { videoLink ->
            Log.d("VoeExtractor", "Generating M3U8 for: $videoLink")
            M3u8Helper.generateM3u8(
                name,
                videoLink,
                "$mainUrl/",
                headers = mapOf("Origin" to "$mainUrl/")
            ).forEach(callback)
        }
    }

    data class WcoSources(
        @JsonProperty("VideoLinkDTO") val VideoLinkDTO: String,
    )
}


class watchadsontape : StreamTape() {
    override var mainUrl = "https://watchadsontape.com"
    override var name = "StreamTape"
}


open class EmturbovidExtractor : ExtractorApi() {
    override var name = "Emturbovid"
    override var mainUrl = "https://emturbovid.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val response = app.get(
            url, referer = referer ?: "$mainUrl/"
        ).document.select("#video_player").attr("data-hash")
        val sources = mutableListOf<ExtractorLink>()
        if (response.isNotBlank()) {

            sources.add(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = response,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        return sources
    }
}

class fc2stream: VidhideExtractor() {
    override var mainUrl="https://fc2stream.tv"
    override val requiresReferer = false
}
