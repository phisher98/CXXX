// 1. Package
package recloudstream

// 2. Imports
import android.util.Log // Import Log for debugging
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
// No need to import AppUtils specifically if using AppUtils.parseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

// --- ADJUST DATA CLASSES FOR SEARCH PAGE JSON ---
// Add SearchResult structure
data class SearchResult(
    @JsonProperty("videoThumbProps") val videoThumbProps: List<VideoThumbProps>? = null
    // Add other search result properties if needed
)

// Modify InitialsJson to include searchResult
data class InitialsJson(
    // For Video Page (load/loadLinks)
    val xplayerSettings: XPlayerSettings? = null,
    val videoEntity: VideoEntity? = null,
    val videoTagsComponent: VideoTagsComponent? = null,
    val relatedVideos: RelatedVideos? = null,
    // For Main Page (getMainPage)
    val layoutPage: LayoutPage? = null,
    // For Search Page (search)
    val searchResult: SearchResult? = null // Add searchResult key
)

data class XPlayerSettings(
    val sources: VideoSources? = null,
    val poster: Poster? = null,
    val subtitles: Subtitles? = null
)

data class VideoSources(
    val hls: HlsSources? = null,
    val standard: StandardSources? = null
)

data class HlsSources(
    val h264: HlsSource? = null
)

data class StandardSources(
    val h264: List<StandardSourceQuality>? = null
)

data class HlsSource(
    val url: String? = null
)

data class StandardSourceQuality(
    val quality: String? = null,
    val url: String? = null
)

data class Poster(
    val url: String? = null
)

data class Subtitles(
    val tracks: List<SubtitleTrack>? = null
)

data class SubtitleTrack(
    val label: String? = null,
    val lang: String? = null,
    val urls: SubtitleUrls? = null
)

data class SubtitleUrls(
    val vtt: String? = null
)

data class VideoEntity(
    val title: String? = null,
    val description: String? = null,
    val thumbBig: String? = null
)

data class VideoTagsComponent(
    val tags: List<Tag>? = null
)

data class Tag(
    val name: String? = null,
    val url: String? = null
)

data class RelatedVideos(
    val videoTabInitialData: VideoTabInitialData? = null
)

data class VideoTabInitialData(
    val videoListProps: VideoListProps? = null
)

data class LayoutPage( // For Main Page JSON structure
    @JsonProperty("videoListProps") val videoListProps: VideoListProps? = null
)

data class VideoListProps(
    val videoThumbProps: List<VideoThumbProps>? = null
)

data class VideoThumbProps(
    val title: String?,
    val pageURL: String?,
    @JsonProperty("thumbURL") val thumbUrl: String?
)
// --- KẾT THÚC DATA CLASSES ---


// === LỚP PROVIDER CHÍNH ===
class XhamsterProvider: MainAPI() {
    // Thông tin cơ bản
    override var mainUrl = "https://vi.xhspot.com" // Đã xác nhận URL này
    override var name = "Xhamster"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.NSFW)

    // Hàm phụ trợ parse JSON (No changes needed here, uses unified InitialsJson)
     private fun getInitialsJson(html: String): InitialsJson? {
        return try {
            val script = Jsoup.parse(html).selectFirst("script#initials-script")?.html() ?: return null
            val jsonString = script.removePrefix("window.initials=").removeSuffix(";")
            AppUtils.parseJson<InitialsJson>(jsonString) // Use AppUtils.parseJson
        } catch (e: Exception) {
            Log.e(name, "getInitialsJson failed: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    // Hàm phụ trợ parse HTML item (Keep as fallback)
    private fun parseVideoItem(element: Element): SearchResponse? {
         // ... (Keep the existing implementation) ...
         val titleElement = element.selectFirst("a.mobile-video-thumb__name")
        val imageElement = element.selectFirst("a.thumb-image-container img")

        val title = titleElement?.text() ?: imageElement?.attr("alt") ?: return null
        val href = titleElement?.attr("href") ?: element.selectFirst("a.thumb-image-container")?.attr("href") ?: return null
        val fixedHref = fixUrl(href) // Chỉ 1 tham số

        var posterUrl = imageElement?.attr("srcset")
        if (posterUrl.isNullOrBlank()) {
            posterUrl = imageElement?.attr("src")
        }
        val fixedPoster = posterUrl?.trim()?.let { fixUrl(it) }

        return newMovieSearchResponse(title, fixedHref, TvType.NSFW) {
            this.posterUrl = fixedPoster
        }
    }

    // === HÀM getMainPage (SỬA LỖI: Dùng newHomePageResponse) ===
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null
        Log.d(name, "getMainPage started for page $page")
        val document = try { app.get("$mainUrl/").document } catch (e: Exception) { Log.e(name, "Failed to fetch main page: ${e.message}"); return null }
        val initialData = getInitialsJson(document.html())
        var items: List<SearchResponse>? = null // Khai báo biến trước
        var listTitle = "Video Trang Chủ" // Tiêu đề mặc định

        // Ưu tiên parse JSON
        if (initialData != null) {
            Log.d(name, "getMainPage: Parsed initialData JSON.")
            items = initialData.layoutPage?.videoListProps?.videoThumbProps?.mapNotNull { item ->
                val title = item.title ?: return@mapNotNull null
                val href = fixUrlNull(item.pageURL) ?: return@mapNotNull null
                val poster = fixUrlNull(item.thumbUrl)
                newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
            }
            if (!items.isNullOrEmpty()) {
                listTitle = "Video Trang Chủ" // Đặt lại tiêu đề nếu lấy từ JSON
                Log.d(name, "getMainPage: Mapped ${items.size} items from JSON.")
            } else {
                 Log.w(name, "getMainPage: JSON items list is null or empty after mapping.")
                 items = null // Reset items để thử fallback
            }
        } else { Log.w(name, "getMainPage: Failed to parse initialData JSON.") }

        // Fallback về HTML nếu JSON thất bại hoặc không có item
        if (items.isNullOrEmpty()) {
            Log.w(name, "getMainPage: Falling back to HTML parsing.")
            items = document.select("div.mobile-video-thumb").mapNotNull { element ->
                parseVideoItem(element)
            }
             if (items.isNotEmpty()) {
                 listTitle = "Video Trang Chủ" // Đặt lại tiêu đề nếu lấy từ HTML
                 Log.d(name, "getMainPage: Mapped ${items.size} items from HTML fallback.")
             } else {
                  Log.w(name, "getMainPage: HTML fallback also yielded no items.")
             }
        }

        return if (!items.isNullOrEmpty()) {
             Log.i(name, "getMainPage: Successfully loaded ${items.size} items using $listTitle logic.")
             // SỬA LỖI: Sử dụng hàm helper newHomePageResponse
             // Thử cách gọi này trước:
             newHomePageResponse(listTitle, items)
             // Nếu cách trên lỗi, thử cách này (ít khả năng hơn):
             // newHomePageResponse(listOf(HomePageList(listTitle, items)))
        } else {
             Log.e(name, "getMainPage: No items found from JSON or HTML.")
             null
        }
    }

    // === HÀM search (REVISED TO PARSE JSON FIRST) ===
    override suspend fun search(query: String): List<SearchResponse>? {
        val searchUrl = "$mainUrl/search/$query"
        Log.d(name, "Search started for query '$query' at URL: $searchUrl")
        val document = try {
            app.get(searchUrl).document
        } catch (e: Exception) {
            Log.e(name, "Failed to fetch search page: ${e.message}")
            e.printStackTrace()
            return null
        }

        // **Ưu tiên parse JSON**
        val initialData = getInitialsJson(document.html())
        var results: List<SearchResponse>? = null

        if (initialData != null) {
             Log.d(name, "Search: Parsed initialData JSON.")
             // **Sử dụng đường dẫn searchResult.videoThumbProps** (đã xác minh từ search.txt)
             results = initialData.searchResult?.videoThumbProps?.mapNotNull { item ->
                 val title = item.title ?: return@mapNotNull null
                 val href = fixUrlNull(item.pageURL) ?: return@mapNotNull null
                 val poster = fixUrlNull(item.thumbUrl)
                 newMovieSearchResponse(title, href, TvType.NSFW) {
                     this.posterUrl = poster
                 }
             }
             Log.d(name, "Search: Mapped ${results?.size ?: 0} items from JSON.")
        } else {
            Log.w(name, "Search: Failed to parse initialData JSON.")
        }

        // **Fallback về HTML nếu JSON thất bại hoặc không có item**
        if (results.isNullOrEmpty()) {
            Log.w(name, "Search: JSON results list is null or empty. Falling back to HTML parsing.")
            results = document.select("div.mobile-video-thumb").mapNotNull { element ->
                parseVideoItem(element)
            }
            Log.d(name, "Search: Mapped ${results.size} items from HTML fallback.")
        }

        return if (!results.isNullOrEmpty()) {
             Log.i(name, "Search: Found ${results.size} results for query '$query'.")
             results
        } else {
             Log.e(name, "Search: No results found for query '$query'.")
             null
        }
    }


    // === HÀM load (REVISED - HTML ONLY FOR RECOMMENDATIONS) ===
    override suspend fun load(url: String): LoadResponse? {
        Log.d(name, "Loading URL: $url")
        val document = try { app.get(url).document } catch (e: Exception) { Log.e(name, "Failed to load URL $url: ${e.message}"); return null }
        val htmlContent = document.html() // Get HTML once
        val initialData = getInitialsJson(htmlContent) // Still parse JSON for other data

        // --- Extract main info (Prioritize JSON, fallback HTML) ---
        val title = initialData?.videoEntity?.title
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.selectFirst("title")?.text()?.substringBefore("|")?.trim()
            ?: run { Log.e(name, "Could not find title for $url"); return null }

        val plot = initialData?.videoEntity?.description
            ?: document.selectFirst("div.video-description p[itemprop=description]")?.text()?.trim()

        val poster = initialData?.xplayerSettings?.poster?.url
            ?: initialData?.videoEntity?.thumbBig
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        val fixedPoster = fixUrlNull(poster)

        val tags = initialData?.videoTagsComponent?.tags?.mapNotNull { it.name }
            ?: document.select("section[data-role=video-tags] a.item").mapNotNull { it.text() }.ifEmpty { null }
        // --- End of main info extraction ---

        // === Parse recommendations using HTML ONLY ===
        var recommendations: List<SearchResponse>? = null
        try {
            // Try the refined selector targeting the related videos section
            val relatedItemsSelector = "div[data-role=video-related] div.mobile-video-thumb"
            recommendations = document.select(relatedItemsSelector).mapNotNull { element ->
                parseVideoItem(element) // Reuse the HTML item parser
            }.ifEmpty { null } // Assign null if list is empty

            Log.d(name, "Found ${recommendations?.size ?: 0} recommendations using HTML selector '$relatedItemsSelector'.")

            // If the first selector failed, maybe try a broader one (less reliable)
            if (recommendations == null) {
                 Log.w(name, "Primary HTML selector for recommendations failed, trying broader selector.")
                 recommendations = document.select("ul.thumb-list div.mobile-video-thumb") // Broader guess
                    .mapNotNull { parseVideoItem(it) }
                    .filter { it.url != url } // Filter out the current video if it appears
                    .ifEmpty { null }
                 Log.d(name, "Found ${recommendations?.size ?: 0} recommendations using broader HTML selector.")
            }

        } catch (e: Exception) {
            Log.e(name, "Error parsing recommendations from HTML: ${e.message}")
            e.printStackTrace()
            recommendations = null
        }
        // === End of recommendations parsing ===

        Log.i(name, "Final recommendations count being added to LoadResponse: ${recommendations?.size}")

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
             this.plot = plot
             this.posterUrl = fixedPoster
             this.tags = tags
             this.recommendations = recommendations // Assign the parsed recommendations
        }
    }

    // Hàm loadLinks (Giữ nguyên như trước)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // ... (Code giữ nguyên) ...
         Log.d(name, "LoadLinks started for: $data")
        val document = try { app.get(data).document } catch (e: Exception) { Log.e(name, "Failed to get document for loadLinks: ${e.message}"); return false }
        val initialData = getInitialsJson(document.html()) ?: run { Log.e(name, "Failed to parse JSON for loadLinks."); return false }

        var foundLinks = false
        val sources = initialData.xplayerSettings?.sources
        val sourceName = this.name

        sources?.hls?.h264?.url?.let { m3u8Url ->
             val fixedM3u8Url = fixUrl(m3u8Url)
             Log.d(name, "Found HLS url: $fixedM3u8Url")
             try {
                 M3u8Helper.generateM3u8( source = sourceName, streamUrl = fixedM3u8Url, referer = data ).forEach { link -> callback(link); foundLinks = true }
             } catch (e: Exception) {
                 Log.e(name, "M3u8Helper failed: ${e.message}")
                 callback( newExtractorLink( source = sourceName, name = "$sourceName HLS", url = fixedM3u8Url) { this.referer = data; this.quality = Qualities.Unknown.value; this.type = ExtractorLinkType.M3U8 } ); foundLinks = true
             }
        } ?: Log.w(name, "No HLS source found in JSON.")

        sources?.standard?.h264?.forEach { qualitySource ->
            val qualityLabel = qualitySource.quality
            val videoUrl = qualitySource.url
            if (qualityLabel != null && videoUrl != null) {
                val fixedVideoUrl = fixUrl(videoUrl)
                val quality = qualityLabel.replace("p", "").toIntOrNull() ?: Qualities.Unknown.value
                 Log.d(name, "Found MP4 link: $qualityLabel - $fixedVideoUrl")
                callback( newExtractorLink( source = sourceName, name = "$sourceName MP4 $qualityLabel", url = fixedVideoUrl) { this.referer = data; this.quality = quality; this.type = ExtractorLinkType.VIDEO }); foundLinks = true
            } else { Log.w(name, "MP4 source item missing quality or url: $qualitySource") }
        } ?: Log.w(name, "No Standard H264 sources found in JSON.")

        initialData.xplayerSettings?.subtitles?.tracks?.forEach { track ->
            val subUrl = track.urls?.vtt
            val lang = track.lang ?: track.label ?: "Unknown"
            if (subUrl != null) {
                val fixedSubUrl = fixUrl(subUrl)
                Log.d(name, "Found subtitle: Lang=$lang, URL=$fixedSubUrl")
                try { subtitleCallback( SubtitleFile( lang = lang, url = fixedSubUrl )) } catch (e: Exception) { e.printStackTrace() }
            } else { Log.w(name, "Subtitle track missing VTT url: $track") }
        } ?: Log.w(name, "No subtitle tracks found in JSON.")

        if (!foundLinks) Log.w(name, "No video links (M3U8 or MP4) were found.")
        return foundLinks
    }

} // Kết thúc class SpotProvider
