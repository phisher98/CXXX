package recloudstream // Hoặc package của bạn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.mvvm.logError
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.random.Random
import org.jsoup.parser.Parser

// ĐỊNH NGHĨA RelatedItem Ở TOP-LEVEL HOẶC BÊN TRONG CLASS NHƯNG NGOÀI HÀM
private data class RelatedItemParse( // Đổi tên để tránh xung đột nếu có class RelatedItem khác
    @JsonProperty("u") val u: String?,
    @JsonProperty("i") val i: String?,
    @JsonProperty("tf") val tf: String?,
    @JsonProperty("d") val d: String?
)

class XnxxProvider : MainAPI() {
    override var mainUrl = "https://www.txnhh.com"
    override var name = "XNXX"
    // ... (các thuộc tính khác giữ nguyên) ...
    override val hasMainPage = true
    override var lang = "en"
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    companion object {
        // ... (companion object giữ nguyên) ...
        fun getQualityFromString(quality: String?): SearchQuality? {
            return when (quality?.trim()?.lowercase()) {
                "1080p" -> SearchQuality.HD
                "720p" -> SearchQuality.HD
                "480p" -> SearchQuality.SD
                "360p" -> SearchQuality.SD
                else -> null
            }
        }
        
        fun getQualityIntFromLinkType(type: String): Int {
            return when (type) {
                "hls" -> Qualities.Unknown.value 
                else -> Qualities.Unknown.value
            }
        }

        fun parseDuration(durationString: String?): Int? {
            if (durationString.isNullOrBlank()) return null
            var totalSeconds = 0
            Regex("""(\d+)\s*h""").find(durationString)?.groupValues?.get(1)?.toIntOrNull()?.let {
                totalSeconds += it * 3600
            }
            Regex("""(\d+)\s*min""").find(durationString)?.groupValues?.get(1)?.toIntOrNull()?.let {
                totalSeconds += it * 60
            }
            Regex("""(\d+)\s*s""").find(durationString)?.groupValues?.get(1)?.toIntOrNull()?.let {
                totalSeconds += it
            }
            return if (totalSeconds > 0) totalSeconds else null
        }
    }

    // HomePageItem vẫn có thể ở đây nếu chỉ dùng trong TxnhhProvider
    data class HomePageItem(
        @JsonProperty("i") val image: String?,
        @JsonProperty("u") val url: String?,
        @JsonProperty("t") val title: String?,
        @JsonProperty("tf") val titleFallback: String?,
        @JsonProperty("n") val count: String?,
        @JsonProperty("ty") val type: String?,
        @JsonProperty("no_rotate") val noRotate: Boolean? = null,
        @JsonProperty("tbk") val tbk: Boolean? = null,
        @JsonProperty("w") val weight: Int? = null
    )

    private suspend fun fetchSectionVideos(sectionUrl: String, maxItems: Int = Int.MAX_VALUE): List<SearchResponse> {
        // ... (giữ nguyên)
        println("TxnhhProvider DEBUG: fetchSectionVideos called for URL = $sectionUrl, maxItems = $maxItems")
        if (!sectionUrl.startsWith("http")) {
            println("TxnhhProvider WARNING: Invalid sectionUrl in fetchSectionVideos: $sectionUrl")
            return emptyList()
        }
        val videoList = mutableListOf<SearchResponse>()
        try {
            val document = app.get(sectionUrl).document 
            val videoElements = document.select("div.mozaique div.thumb-block")
            // println("TxnhhProvider DEBUG: Found ${videoElements.size} thumb-blocks in fetchSectionVideos for $sectionUrl")
            
            videoElements.take(maxItems).mapNotNullTo(videoList) { it.toSearchResponse() }
        } catch (e: Exception) {
            System.err.println("TxnhhProvider ERROR: Failed to fetch/parse section $sectionUrl. Error: ${e.message}")
        }
        return videoList
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // ... (giữ nguyên logic getMainPage như trước)
        println("TxnhhProvider DEBUG: getMainPage called, page: $page")
        val homePageListsResult = ArrayList<HomePageList>()
        var hasNextMainPage = false 

        if (page == 1) { 
            val document = app.get(mainUrl).document
            val scriptElements = document.select("script:containsData(xv.cats.write_thumb_block_list)")

            if (scriptElements.isNotEmpty()) {
                val scriptContent = scriptElements.html()
                val regex = Regex("""xv\.cats\.write_thumb_block_list\s*\(\s*(\[(?:.|\n)*?\])\s*,\s*['"]home-cat-list['"]""")
                val matchResult = regex.find(scriptContent)
                
                if (matchResult != null && matchResult.groupValues.size > 1) {
                    val arrayString = matchResult.groupValues[1].trim() 
                    if (arrayString.startsWith("[") && arrayString.endsWith("]")) {
                        try {
                            val allHomePageItems = AppUtils.parseJson<List<HomePageItem>>(arrayString)
                            val validSectionsSource = allHomePageItems.mapNotNull { item ->
                                var currentItemTitle = item.title ?: item.titleFallback 
                                val itemUrlPart = item.url
                                
                                if (itemUrlPart == null) return@mapNotNull null
                                val itemUrl = if (itemUrlPart.startsWith("/")) mainUrl + itemUrlPart else itemUrlPart

                                if (itemUrlPart == "/todays-selection") {
                                    currentItemTitle = "Today's Selection" 
                                } else if (currentItemTitle != null) {
                                    currentItemTitle = Parser.unescapeEntities(currentItemTitle, false)
                                }

                                if (currentItemTitle == null) return@mapNotNull null

                                val isGameOrStory = itemUrl.contains("nutaku.net") || itemUrl.contains("sexstories.com")
                                val isLikelyStaticLink = item.noRotate == true && item.count == "0" && item.tbk == false && 
                                                         (isGameOrStory || item.url == "/your-suggestions/straight" || item.url == "/tags" || item.url == "/pornstars")
                                
                                if (isGameOrStory || isLikelyStaticLink) null
                                else if (item.type == "cat" || item.type == "search" || item.url == "/todays-selection" || item.url == "/best" || item.url == "/hits" || item.url == "/fresh" || item.url == "/verified/videos" ) Pair(currentItemTitle, itemUrl)
                                else null
                            }.distinctBy { it.second } 

                            val sectionsToDisplayThisPage = mutableListOf<Pair<String, String>>()
                            validSectionsSource.find { it.second.endsWith("/todays-selection") }?.let { 
                                sectionsToDisplayThisPage.add(it)
                            }
                            
                            val otherSectionsPool = validSectionsSource.filterNot { sectionsToDisplayThisPage.map { sec -> sec.second }.contains(it.second) }.toMutableList()
                            
                            val itemsPerHomePage = 5
                            val randomItemsNeeded = itemsPerHomePage - sectionsToDisplayThisPage.size
                            
                            if (randomItemsNeeded > 0 && otherSectionsPool.isNotEmpty()) {
                                otherSectionsPool.shuffle(Random(System.currentTimeMillis())) 
                                sectionsToDisplayThisPage.addAll(otherSectionsPool.take(randomItemsNeeded))
                            }
                            
                            if (validSectionsSource.size > itemsPerHomePage && page < 3) { 
                                hasNextMainPage = true
                            }

                            if (sectionsToDisplayThisPage.isNotEmpty()) {
                                coroutineScope {
                                    val deferredLists = sectionsToDisplayThisPage.map { (sectionTitle, sectionUrl) ->
                                        async {
                                            val videos = fetchSectionVideos(sectionUrl) 
                                            if (videos.isNotEmpty()) HomePageList(sectionTitle, videos) else null
                                        }
                                    }
                                    deferredLists.forEach { it?.await()?.let { homePageListsResult.add(it) } }
                                }
                            }
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                }
            }
        } else if (page > 1) { 
             hasNextMainPage = false 
        }

        if (homePageListsResult.isEmpty() && page == 1) {
            homePageListsResult.add(HomePageList("Default Links (Fallback)", listOf(
                newMovieSearchResponse(name = "Asian Woman", url = "$mainUrl/search/asian_woman", type = TvType.NSFW) {},
                newMovieSearchResponse(name = "Today's Selection", url = "$mainUrl/todays-selection", type = TvType.NSFW) {}
            )))
            hasNextMainPage = false 
        }
        return newHomePageResponse(list = homePageListsResult, hasNext = hasNextMainPage)
    }

     private fun Element.toSearchResponse(): SearchResponse? {
        // ... (toSearchResponse giữ nguyên)
        val titleElement = this.selectFirst(".thumb-under p a") ?: return null
        val rawTitle = titleElement.attr("title") 
        val title = Parser.unescapeEntities(rawTitle, false)

        var rawHref = titleElement.attr("href")
        val cleanHrefPath: String
        val thumbNumPattern = Regex("""(/video-[^/]+)/(\d+/THUMBNUM/)(.+)""")
        val matchThumbNum = thumbNumPattern.find(rawHref)

        if (matchThumbNum != null && matchThumbNum.groupValues.size == 4) {
            cleanHrefPath = "${matchThumbNum.groupValues[1]}/${matchThumbNum.groupValues[3]}"
        } else {
            val problematicUrlPattern = Regex("""(/video-[^/]+)/(\d+/\d+/)(.+)""")
            val matchProblematic = problematicUrlPattern.find(rawHref)
            if (matchProblematic != null && matchProblematic.groupValues.size == 4) {
                cleanHrefPath = "${matchProblematic.groupValues[1]}/${matchProblematic.groupValues[3]}"
            } else {
                cleanHrefPath = rawHref
            }
        }
        
        val finalHref = mainUrl + cleanHrefPath
        val posterUrl = this.selectFirst(".thumb img")?.attr("data-src")?.let { if (it.startsWith("//")) "https:$it" else it }
        val metadataElement = this.selectFirst(".thumb-under p.metadata")
        val qualityText = metadataElement?.selectFirst("span.video-hd")?.text()?.trim()
        
        return newMovieSearchResponse(name = title, url = finalHref, type = TvType.NSFW) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(qualityText)
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? { 
        // ... (search giữ nguyên)
        println("TxnhhProvider DEBUG: search() called with query/URL = $query")
        val searchUrl = if (query.startsWith("http")) query else "$mainUrl/search/$query"
        
        val videoList = fetchSectionVideos(searchUrl) 
        
        return if (videoList.isEmpty()) {
            println("TxnhhProvider DEBUG: search() returning null as videoList is empty for $searchUrl (query: $query)")
            null 
        } else {
            videoList
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        println("TxnhhProvider DEBUG: load() called with URL = $url")
        val document = app.get(url).document
        
        val rawOgTitle = document.selectFirst("meta[property=og:title]")?.attr("content")
        val rawPageTitle = document.selectFirst(".video-title strong")?.text()
        val title = (rawOgTitle?.let { Parser.unescapeEntities(it, false) } ?: rawPageTitle?.let { Parser.unescapeEntities(it, false) }) ?: "Unknown Title"
        
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.let { if (it.startsWith("//")) "https:$it" else it }
        
        val rawDescription = document.selectFirst("p.video-description")?.text()?.trim()
        val description = rawDescription?.let { Parser.unescapeEntities(it, false) }

        val tags = document.select(".metadata-row.video-tags a:not(#suggestion)")
            .mapNotNull { Parser.unescapeEntities(it.text(), false).trim() } 
            .filter { it.isNotEmpty() }

        val scriptElements = document.select("script:containsData(html5player.setVideoHLS)")
        var hlsLink: String? = null

        if (scriptElements.isNotEmpty()) {
            val scriptContent = scriptElements.html()
            hlsLink = Regex("""html5player\.setVideoHLS\s*\(\s*['"](.*?)['"]\s*\)""").find(scriptContent)?.groupValues?.get(1)
        }
        
        val videoDataString = hlsLink?.let { "hls:$it" } ?: ""

        // Khôi phục Recommendations
        val relatedVideos = ArrayList<SearchResponse>()
        val relatedScript = document.select("script:containsData(var video_related)")
        if (relatedScript.isNotEmpty()) {
            val scriptContentRelated = relatedScript.html()
            val jsonRegexRelated = Regex("""var video_related\s*=\s*(\[(?:.|\n)*?\])\s*;""") 
            val matchRelated = jsonRegexRelated.find(scriptContentRelated)
            if (matchRelated != null && matchRelated.groupValues.size > 1) {
                val jsonArrayStringRelated = matchRelated.groupValues[1]
                try {
                    // Sử dụng RelatedItemParse đã định nghĩa ở top-level
                    val relatedItems = AppUtils.parseJson<List<RelatedItemParse>>(jsonArrayStringRelated)
                    relatedItems.forEach { related ->
                        val rawRelatedTitle = related.tf // Sử dụng thuộc tính của RelatedItemParse
                        val relatedTitle = rawRelatedTitle?.let { Parser.unescapeEntities(it, false)}
                        if (related.u != null && relatedTitle != null) { // Sử dụng thuộc tính của RelatedItemParse
                            var cleanRelatedHrefPath = related.u
                            val relThumbNumPattern = Regex("""(/video-[^/]+)/(\d+/THUMBNUM/)(.+)""")
                            val relProblematicPattern = Regex("""(/video-[^/]+)/(\d+/\d+/)(.+)""")
                            var relMatch = relThumbNumPattern.find(related.u)
                            if (relMatch != null && relMatch.groupValues.size == 4) {
                                cleanRelatedHrefPath = "${relMatch.groupValues[1]}/${relMatch.groupValues[3]}"
                            } else {
                                relMatch = relProblematicPattern.find(related.u)
                                if (relMatch != null && relMatch.groupValues.size == 4) {
                                    cleanRelatedHrefPath = "${relMatch.groupValues[1]}/${relMatch.groupValues[3]}"
                                }
                            }
                            val finalRelatedUrl = mainUrl + cleanRelatedHrefPath
                            relatedVideos.add(newMovieSearchResponse(
                                name = relatedTitle,
                                url = finalRelatedUrl,
                                type = TvType.NSFW
                            ) {
                                this.posterUrl = related.i?.let { if (it.startsWith("//")) "https:$it" else it } // Sử dụng thuộc tính của RelatedItemParse
                            })
                        }
                    }
                } catch (e: Exception) {
                     System.err.println("TxnhhProvider ERROR: Failed to parse related videos JSON. Error: ${e.message}")
                     e.printStackTrace()
                }
            }
        }

        var durationInSeconds: Int? = null
        document.selectFirst("meta[property=og:duration]")?.attr("content")?.let { durationMeta ->
            try {
                var tempDuration = 0
                Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""").find(durationMeta)?.let { match ->
                    match.groupValues.getOrNull(1)?.toIntOrNull()?.let { tempDuration += it * 3600 }
                    match.groupValues.getOrNull(2)?.toIntOrNull()?.let { tempDuration += it * 60 }
                    match.groupValues.getOrNull(3)?.toIntOrNull()?.let { tempDuration += it }
                }
                if (tempDuration > 0) durationInSeconds = tempDuration
            } catch (_: Exception) {}
        }

        return newMovieLoadResponse(name = title, url = url, type = TvType.NSFW, dataUrl = videoDataString) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = relatedVideos
            this.duration = durationInSeconds
        }
    }

    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // ... (Hàm loadLinks giữ nguyên)
        var hasAddedLink = false
        if (data.startsWith("hls:")) {
            val videoStreamUrl = data.substringAfter("hls:")
            if (videoStreamUrl.isNotBlank()) {
                try {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = videoStreamUrl,
                            type = ExtractorLinkType.M3U8,
                        ).apply {
                            this.quality = getQualityIntFromLinkType("hls")
                        }
                    )
                    hasAddedLink = true
                } catch (e: Exception) {
                    logError(e)
                }
            }
        }
        return hasAddedLink
    }
}
