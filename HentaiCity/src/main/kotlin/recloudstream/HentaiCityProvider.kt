// Đặt tên package phù hợp với cấu trúc thư mục của bạn
package recloudstream // Thay đổi dòng này

// --- Imports cần thiết ---
import com.lagradost.cloudstream3.* // Import chính
import com.lagradost.cloudstream3.utils.* // Import các utils
import org.jsoup.nodes.Element           // Cho Element của Jsoup
import com.lagradost.cloudstream3.utils.ExtractorLink // Link trích xuất
import com.lagradost.cloudstream3.utils.ExtractorLinkType // Loại link
import org.jsoup.Jsoup                   // Cần cho Jsoup.parse trong load

// Định nghĩa lớp Plugin chính
class HentaiCityProvider : MainAPI() {
    // --- Thông tin cơ bản về Plugin ---
    override var name = "HentaiCity"
    override var mainUrl = "https://www.hentaicity.com"
    override var lang = "en"
    override val hasMainPage = true
    override var hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    // --- Helper Function để Parse Item (Video) ---
     private fun parseItem(element: Element): MovieSearchResponse? {
        val linkElement = element.selectFirst("a.thumb-img")
        val titleElement = element.selectFirst("p > a.video-title")

        val href = fixUrlNull(linkElement?.attr("href"))
        val title = titleElement?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: titleElement?.attr("title")?.trim()?.takeIf { it.isNotBlank() }
            ?: element.selectFirst("a.video-title")?.attr("title")?.trim()?.takeIf { it.isNotBlank() }
            ?: "Item (Title N/A) - ${href?.take(20)}..."

        if (href.isNullOrBlank()) return null

        val posterElement = linkElement?.selectFirst("img.thumbtrailer__image")
                           ?: element.selectFirst("img[src]")
        val posterUrl = fixUrlNull(posterElement?.attr("src"))

        if (posterUrl.isNullOrBlank()) {
            println("HentaiCityProvider WARNING: Poster URL is blank for '$title'.")
        }

        val qualityElement = linkElement?.selectFirst("span.flag-hd")
                          ?: element.selectFirst("span.flag-hd")
        val quality = if (qualityElement != null) SearchQuality.HD else SearchQuality.SD

        val tvType = if (href.contains("/video/")) TvType.NSFW else TvType.NSFW

        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
            this.quality = quality
        }
    }


    // --- Hàm lấy dữ liệu Trang Chủ (Sửa lỗi constructor HomePageResponse) ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = mutableListOf<HomePageList>()

        // Hàm cục bộ để thêm section
        fun addSectionLocal(title: String, elements: List<Element>?) {
            if (elements.isNullOrEmpty()) {
                println("HentaiCityProvider WARNING: No elements selected for section '$title'.")
                 return
             }
            val items = elements.mapNotNull { parseItem(it) }
            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(title, items))
            } else {
                println("HentaiCityProvider WARNING: No items successfully parsed for section '$title'.")
            }
        }

        try {
            // Section 1: New Hentai Releases
            addSectionLocal("New Hentai Releases", document.select("div.new-releases > div.item"))

            // Section 2: Most Popular Videos
            addSectionLocal("Most Popular Videos", document.select("h2:contains(Most Popular Videos) ~ div.thumb-list > div.outer-item > div.item").take(24))

            // Section 3: Recent Videos
            var recentElements: List<Element>? = null
            val recentContainer = document.select("h2:contains(Recent Videos) ~ div#taglink.thumb-list").firstOrNull()
            if (recentContainer != null) {
                recentElements = recentContainer.select("div.recent > div.item")
            }
            addSectionLocal("Recent Videos", recentElements)

            // Section 4: Manga/Comics - Đã bị loại bỏ theo yêu cầu trước
            // println("HentaiCityProvider INFO: Manga/Comics section intentionally skipped.")

        } catch (e: Exception) {
            println("HentaiCityProvider ERROR: Exception during getMainPage: ${e.message}")
            e.printStackTrace()
        }

        if (homePageList.isEmpty()) {
            println("HentaiCityProvider WARNING: getMainPage finished but HomePageList is empty.")
        }

        // *** SỬA LỖI: Sử dụng positional argument thay vì named argument ***
        return newHomePageResponse(homePageList, false)
    }


    // --- Hàm Tìm Kiếm ---
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/video/$query"
        return try {
            val document = app.get(searchUrl).document
            document.select("section.content > div.thumb-list div.outer-item > div.item").mapNotNull {
                parseItem(it)
            }
        } catch (e: Exception) {
            println("$name Search failed for query '$query': ${e.message}")
            emptyList()
        }
    }

    // --- Hàm Tải Chi Tiết Video (Sử dụng Selector chính xác hơn cho Recommendations) ---
override suspend fun load(url: String): LoadResponse? {
     return try {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div#playerz video")?.attr("poster"))
            ?: fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val synopsis = document.selectFirst("div.detail-box > div.ubox-text")?.html()?.replace("<br>", "\n")?.let { Jsoup.parse(it).text() }
                       ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val tags = document.select("div#taglink a:not(:has(svg))")
                         .mapNotNull { it.text()?.trim()?.takeIf { tag -> tag.isNotEmpty() } }
        val rating = document.selectFirst("div.info span:contains('%')")?.text()?.replace("%", "")?.trim()?.toIntOrNull()?.times(100)
        var year: Int? = null
        try {
            val infoDiv = document.selectFirst("div.fp_title div.information")?.text()
            if (infoDiv != null) {
                 val dateRegex = Regex("""(\w+\s+\d{1,2},\s*\d{4})""")
                 val dateMatch = dateRegex.find(infoDiv)
                 year = dateMatch?.groupValues?.get(1)?.split(",")?.lastOrNull()?.trim()?.toIntOrNull()
             }
        } catch (e: Exception){
             println("$name Error parsing year: ${e.message}")
        }

        // *** Lấy video liên quan (recommendations) - Dùng Selector chính xác và Log ***
         var recommendations: List<SearchResponse> = emptyList()
         try {
             // *** Sử dụng selector chính xác dựa trên cấu trúc HTML đã phân tích ***
             val recommendationSelector = "div#related_videos div.outer-item > div.item"
             val recommendationElements = document.select(recommendationSelector)
             println("HentaiCityProvider DEBUG Load: Found ${recommendationElements.size} potential recommendation elements using '$recommendationSelector'.")

             recommendations = recommendationElements.mapNotNull { element ->
                 // Log phần tử đầu tiên để debug cấu trúc nếu cần
                 // if (recommendations.isEmpty()) println("HentaiCityProvider DEBUG Load: First recommendation element HTML: ${element.outerHtml().take(200)}")
                 parseItem(element) // Gọi parseItem cho từng phần tử item
             }
             println("HentaiCityProvider DEBUG Load: Successfully parsed ${recommendations.size} recommendations.")

         } catch (e: Exception) {
              println("HentaiCityProvider ERROR Load: Exception parsing recommendations: ${e.message}")
              e.printStackTrace()
         }

         newMovieLoadResponse(title, url, TvType.NSFW, url) {
             this.posterUrl = poster
             this.plot = synopsis
             this.tags = tags
             this.score = Score.from10(rating)
             this.recommendations = recommendations // Gán recommendations
             this.year = year
         }
     } catch (e: Exception) {
         println("$name Load failed for url '$url': ${e.message}")
         null
     }
}
    // --- Hàm Lấy Link Xem Video ---
    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val document = app.get(data).document
            var foundLink = false
            document.selectFirst("div#playerz video source[type=\"application/x-mpegURL\"]")?.attr("src")?.let { hlsUrl ->
                if (hlsUrl.isNotBlank() && hlsUrl.contains("master.m3u8")) {
                    callback(newExtractorLink(source = this.name, name = "${this.name}", url = fixUrl(hlsUrl), type = ExtractorLinkType.M3U8) { this.referer = mainUrl })
                    foundLink = true
                }
            }
            fixUrlNull(document.selectFirst("meta[property=og:video:url]")?.attr("content"))?.let { mp4Url ->
                callback(newExtractorLink(source = this.name, name = "${this.name}", url = mp4Url, type = ExtractorLinkType.VIDEO) { this.referer = mainUrl; this.quality = Qualities.P480.value })
                if (!foundLink) foundLink = true
            }
            if (!foundLink) {
                document.select("script").find { script ->
                    if (script.data().contains("fluidPlayer(")) {
                        val scriptData = script.data()
                        val hlsRegex = Regex("""source\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
                        hlsRegex.find(scriptData)?.groupValues?.get(1)?.let { extractedHlsUrl ->
                            if (extractedHlsUrl.isNotBlank()) {
                                callback(newExtractorLink(source = this.name, name = "${this.name}", url = fixUrl(extractedHlsUrl), type = ExtractorLinkType.M3U8) { this.referer = mainUrl })
                                foundLink = true
                                return@find true
                            }
                        }
                    }
                    false
                }
            }
            foundLink
        } catch (e: Exception) {
            println("$name loadLinks failed for url '$data': ${e.message}")
            false
        }
    }
} // Kết thúc class HentaiCityProvider
