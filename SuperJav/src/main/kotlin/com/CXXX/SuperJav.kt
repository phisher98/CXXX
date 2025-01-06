package com.CXXX

import com.lagradost.api.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.extractors.StreamWishExtractor

class SuperJav : MainAPI() {
    override var mainUrl              = "https://supjav.com"
    override var name                 = "SupJav"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

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
        return true
    }
}



class watchadsontape : StreamTape() {
    override var mainUrl = "https://watchadsontape.com"
    override var name = "StreamTape"
}

open class Fc2stream : ExtractorApi() {
    override val name = "Streamwish"
    override val mainUrl = "https://fc2stream.tv"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "Accept" to "*/*",
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Origin" to "$mainUrl/",
            "User-Agent" to USER_AGENT
        )
        val response = app.get(getEmbedUrl(url), referer = referer)
        val script = if (!getPacked(response.text).isNullOrEmpty()) {
            getAndUnpack(response.text)
        } else if (!response.document.select("script").firstOrNull {
                it.html().contains("jwplayer(\"vplayer\").setup(")
            }?.html().isNullOrEmpty()
        ) {
            response.document.select("script").firstOrNull {
                it.html().contains("jwplayer(\"vplayer\").setup(")
            }?.html()
        } else {
            response.document.selectFirst("script:containsData(sources:)")?.data()
        }
        val m3u8 =
            Regex("file:\\s*\"(.*?m3u8.*?)\"").find(script ?: return)?.groupValues?.getOrNull(1)
        M3u8Helper.generateM3u8(
            name,
            m3u8 ?: return,
            mainUrl,
            headers = headers
        ).forEach(callback)
    }

    private fun getEmbedUrl(url: String): String {
        return if (url.contains("/f/")) {
            val videoId = url.substringAfter("/f/")
            "$mainUrl/$videoId"
        } else {
            url
        }
    }

}


open class EmturbovidExtractor : ExtractorApi() {
    override var name = "Emturbovid"
    override var mainUrl = "https://emturbovid.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        Log.d("Phisher url",url)
        val response = app.get(
            url, referer = referer ?: "$mainUrl/"
        ).document.select("script:containsData(function(h,u,n,t,e,r))").text()
        Log.d("Phisher url",response)
        val sources = mutableListOf<ExtractorLink>()
        if (response.isNotBlank()) {
            val m3u8Url =""

            sources.add(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Url,
                    referer = "$mainUrl/",
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
        }
        return sources
    }
}