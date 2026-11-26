package com.MissAv

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class MissAVProvider : MainAPI() {
    override var mainUrl              = "https://missav.ws"
    override var name                 = "MissAV"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded
    val subtitleCatUrl = "https://www.subtitlecat.com"

    override val mainPage = mainPageOf(
            "/dm514/en/new" to "Recent Update",
            "/dm588/en/release" to "New Release",
            "/dm291/en/today-hot" to "Most Viewed Today",
            "/dm169/en/weekly-hot" to "Most Viewed by Week",
            "/dm256/en/monthly-hot" to "Most Viewed by Month",
            "/dm97/en/fc2" to "Uncensored FC2 AV",
            "/dm34/en/madou" to "Madou AV",
            "/dm620/en/uncensored-leak" to "Uncensored Leak",
            "/en/klive" to "Korean Live AV"
        )
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
            val document = app.get("$mainUrl${request.data}?page=$page").document
            val responseList  = document.select(".thumbnail").mapNotNull { it.toSearchResult() }
            return newHomePageResponse(HomePageList(request.name, responseList, isHorizontalImages = true),hasNext = true)

    }

    private fun Element.toSearchResult(): SearchResponse {
        val status = this.select(".bg-blue-800").text()
        val title = if(status.isNotBlank()){"[$status] "+ this.select(".text-secondary").text()} else {this.select(".text-secondary").text()}
        val href = this.select(".text-secondary").attr("href")
        val posterUrl = this.selectFirst(".w-full")?.attr("data-src")
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val searchResponse = mutableListOf<SearchResponse>()

        for (i in 1..7) {
            val document = app.get("$mainUrl/en/search/$query?page=$i").document
            //val document = app.get("${mainUrl}/page/$i/?s=$query").document

            val results = document.select(".thumbnail").mapNotNull { it.toSearchResult() }

            if(results.isNotEmpty())
            {
                for (result in results)
                {
                    if(!searchResponse.contains(result))
                    {
                        searchResponse.add(result)
                    }
                }
            }
            else
            {
                break
            }
            /*if (!searchResponse.containsAll(results)) {
                searchResponse.addAll(results)
            } else {
                break
            }

            if (results.isEmpty()) break*/
        }

        return searchResponse

    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().toString()
        val poster = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {


            val response = app.get(data, referer = "$mainUrl/")
            val doc = response.document
            val html = response.text

            // The player source is packed (eval(function(p,a,c,k,e,d)...)). The page now carries two packed
            // blocks (the second is an anti-hotlink host check), so unpack every block, not just the first.
            val unpacked = Regex("""eval\(function\(p,a,c,k,e,[rd]\)[\s\S]*?\.split\('\|'\)[^)]*\)\)""")
                .findAll(html)
                .mapNotNull { runCatching { JsUnpacker(it.value).unpack() }.getOrNull() }
                .joinToString(";")
                .ifBlank { runCatching { getAndUnpack(html) }.getOrDefault("") }

            // Unpacked text keeps the JS escapes, e.g. source=\'https://c.surrit.com/<uuid>/playlist.m3u8\';
            // so allow an optional backslash before the quotes. Master playlist ("source=") first.
            val candidates = linkedSetOf<String>()
            Regex("""source(\w*)\s*=\s*\\?['"](https?://[^'"\\]+\.m3u8[^'"\\]*)\\?['"]""").findAll(unpacked)
                .sortedBy { it.groupValues[1].isNotEmpty() }
                .forEach { candidates.add(it.groupValues[2]) }

            // The CDN host rotates (surrit.com and b.surrit.com now answer 403, c.surrit.com works),
            // so keep the host the page gives and only fall back to guessed hosts built from the uuid.
            val uuidRegex = Regex("""surrit\.com\\?/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})""")
            val uuid = uuidRegex.find(unpacked)?.groupValues?.get(1)
                ?: uuidRegex.find(html)?.groupValues?.get(1)

            val ordered = buildList {
                addAll(candidates)
                if (uuid != null) {
                    listOf("c.surrit.com", "b.surrit.com", "surrit.com").forEach { host ->
                        add("https://$host/$uuid/playlist.m3u8")
                    }
                }
            }.distinct()

            val hlsHeaders = mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/", "User-Agent" to USER_AGENT)
            val playable = ordered.firstOrNull { url ->
                runCatching {
                    val r = app.get(url, headers = hlsHeaders, timeout = 15)
                    r.isSuccessful && r.text.trimStart().startsWith("#EXTM3U")
                }.getOrDefault(false)
            } ?: ordered.firstOrNull()

            if (playable != null) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = playable,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = hlsHeaders
                        this.quality = Qualities.Unknown.value
                    }
                )
            }

        try {
            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().toString()
            val javCode = "([a-zA-Z]+-\\d+)".toRegex().find(title)?.groups?.get(1)?.value
            if(!javCode.isNullOrEmpty())
            {
                val query = "$subtitleCatUrl/index.php?search=$javCode"
                val subDoc = app.get(query, timeout = 15).document
                val subList = subDoc.select("td a")
                for(item in subList)
                {
                    if(item.text().contains(javCode,ignoreCase = true))
                    {
                        val fullUrl = "$subtitleCatUrl/${item.attr("href")}"
                        val pDoc = app.get(fullUrl, timeout = 10).document
                        val sList = pDoc.select(".col-md-6.col-lg-4")
                        for(item in sList)
                        {
                            try {
                                val language = item.select(".sub-single span:nth-child(2)").text()
                                val text = item.select(".sub-single span:nth-child(3) a")
                                if(text != null && text.size > 0 && text[0].text() == "Download")
                                {
                                    val url = "$subtitleCatUrl${text[0].attr("href")}"
                                    subtitleCallback.invoke(
                                        SubtitleFile(
                                            language.replace("\uD83D\uDC4D \uD83D\uDC4E",""),  // Use label for the name
                                            url     // Use extracted URL
                                        )
                                    )
                                }
                            } catch (e: Exception) { }
                        }

                    }
                }

            }
        } catch (e: Exception) { }



        return true
    }
}
