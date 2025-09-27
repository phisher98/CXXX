package com.Happy2hub

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Happy2hub : MainAPI() {
    override var mainUrl              = "https://happy2hub.eu"
    override var name                 = "Happy2hub"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
            "ullu-a/" to "Ullu",
            "tag/primeplay-watch-online" to "Primeplay",
            "tag/altt-watch-online" to "Altt",
            "tag/bigshots-ott-watch-online" to "Bigshots",
            "tag/naari-magazine-watch-online" to "Naari",
            "tag/desiflix-originals-watch-online" to "Desiflix",
            "tag/idiot-boxx-watch-online" to "Idiot",
            "tag/hotshots-watch-online" to "Hotshots",
            "tag/mx-player-watch-online" to "MX Player",
            "tag/namastey-flix-originals" to "Namastey Flix",
            "tag/18" to "All Videos",
            "tag/brazzersexxtra" to "Brazzer",
            "tag/mojflix-watch-online" to "Mojflix",
            "tag/mangoflix-watch-online" to "Mangoflix",
            "tag/hothit-watch-online" to "Hothit",
            "tag/porn" to "All Porn",


    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}/page/$page", timeout = 20L).document
        val home     = document.select("div.content-wrap > div > div > div").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title     =this.select("h4 a").text()
        val href      =this.select("a").attr("href")
        val posterUrl =this.select("a img").attr("src")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..10) {
            val document = app.get("${mainUrl}/page/$i?s=$query").document

            val results = document.select("div.content-wrap > div > div > div").mapNotNull { it.toSearchResult() }

            if (!searchResponse.containsAll(results)) {
                searchResponse.addAll(results)
            } else {
                break
            }

            if (results.isEmpty()) break
        }

        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().toString()
        val poster = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val episodes = mutableListOf<Episode>()
        Log.d("Phisher",url)
        Log.d("Phisher",poster.toString())

        // Retrieve the first download link
        val href = document.select("div.entry-content.clearfix p a").attr("href")
        val PTag = app.get(href).document

        // Select all episodes (h4 or h5 containing "Episode")
        PTag.select("div.entry-content.clearfix h4:contains(Episode), div.entry-content.clearfix h5:contains(Episode)").map { episode ->

            // Extract episode number
            val epno = episode.text().substringAfter("Episode ").trim()

            // Temporary list to hold all links for this episode
            val episodeLinks = mutableListOf<String>()

            var nextElement = episode.nextElementSibling()

            // Continue collecting hrefs until the next <p> tag is encountered
            while (nextElement != null && nextElement.tagName() != "p") {
                // Check if the sibling element has any <a> tags
                nextElement.select("a").forEach { linkElement ->
                    val link = linkElement.attr("href")

                    // Only add non-empty href values
                    if (link.isNotEmpty()) {
                        episodeLinks.add(link)
                    }
                }
                // Move to the next sibling element
                nextElement = nextElement.nextElementSibling()
            }
            // Add all the links for this episode as a single Episode object
            if (episodeLinks.isNotEmpty()) {
                episodes.add(newEpisode(episodeLinks.joinToString()){
                    this.name="Episode $epno"
                    this.posterUrl = poster
                })
                }
        }

        // Return the response with all episodes
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }


    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val linksList = data.split(",").map { it.trim() }
        if (linksList.isNotEmpty()) {
            linksList.forEach {
                //Log.d("Phisher",it)
                loadExtractor(it,subtitleCallback, callback)
            }
        }
        return true
    }


}