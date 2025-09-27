package recloudstream

// Import các lớp cần thiết từ CloudStream và Kotlin
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.network.CloudflareKiller // Bỏ comment nếu cần
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlin.comparisons.minOf
import java.util.concurrent.atomic.AtomicBoolean // Dùng cho hasNextPage trong môi trường coroutine

// Thêm các import cần thiết cho coroutine
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// Định nghĩa lớp Provider chính, kế thừa từ MainAPI
class XnhauProvider : MainAPI() {
    // --- Thông tin cơ bản của Provider ---
    override var mainUrl = "https://xnhau.im"
    override var name = "xNhau"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )
    private val storageUrl = "https://xnhaustorage.com" // Có thể dùng const val

    // override val interceptor = CloudflareKiller() // Bỏ comment nếu trang web dùng Cloudflare

    // --- Data class để lưu thông tin path ---
    private data class VideoPathInfo(val group: String, val videoId: String)

    // --- Helper Functions ---

    private fun Element.toSearchResponse(): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val href = fixUrlNull(aTag.attr("href")) ?: return null
        val title = aTag.selectFirst("strong.title")?.text()?.trim() ?: aTag.attr("title").trim()
        if (title.isBlank() || href.isBlank()) return null

        val imgTag = aTag.selectFirst(".img img.thumb")
        var posterUrlExtracted = fixUrlNull(imgTag?.attr("data-original")) // Đổi tên biến để tránh trùng
        if (posterUrlExtracted.isNullOrBlank()) {
            posterUrlExtracted = fixUrlNull(imgTag?.attr("src"))
        }
        val webpUrl = fixUrlNull(imgTag?.attr("data-webp"))
        if (!webpUrl.isNullOrBlank()) {
            posterUrlExtracted = webpUrl
        }

        val qualityDetected = if (aTag.selectFirst(".is-hd") != null) SearchQuality.HD else SearchQuality.SD

        // *** FIX LỖI: Gọi newMovieSearchResponse theo signature bạn cung cấp ***
        return newMovieSearchResponse(
            name = title,
            url = href
            // Không truyền type, fix -> dùng mặc định
            // Không truyền apiName, year trực tiếp
        ) {
            // Thử đặt các thuộc tính có thể là 'var' bên trong lambda
            this.posterUrl = posterUrlExtracted // Hy vọng posterUrl là var
            this.quality = qualityDetected     // Hy vọng quality là var
            // Không đặt this.apiName, this.type, this.year vì chúng không có hoặc là val
        }
    }

     private fun qualityStringToInt(qualityLabel: String?): Int {
         return qualityLabel?.filter { it.isDigit() }?.toIntOrNull() ?: 0
    }

     private fun extractJsVar(jsString: String, key: String): String? {
        return Regex("""['"]?$key['"]?\s*:\s*['"]?([^'",]+)['"]?,?""").find(jsString)?.groupValues?.get(1)?.trim()
    }

    private fun findJsVariableContent(html: String, variableName: String): String? {
        val startMarker = "var $variableName = {"
        val startIndex = html.indexOf(startMarker)
        if (startIndex == -1) { return null }
        val endIndexSemicolon = html.indexOf("};", startIndex + startMarker.length)
        val endIndexBrace = html.indexOf("}", startIndex + startMarker.length)
        val endIndex = minOf(
            endIndexSemicolon.takeIf { it != -1 } ?: Int.MAX_VALUE,
            endIndexBrace.takeIf { it != -1 } ?: Int.MAX_VALUE
        )
        if (endIndex == Int.MAX_VALUE) return null
        return html.substring(startIndex + startMarker.length, endIndex).trim()
    }

    private fun extractGroupFromPosterUrl(posterUrl: String?): String? {
        if (posterUrl.isNullOrBlank()) return null
        return Regex("""/videos_screenshots/(\d+)/""").find(posterUrl)?.groupValues?.get(1)
    }

    // Hàm này có vẻ không được dùng trong logic hiện tại, nhưng giữ lại phòng trường hợp cần
    private fun extractPathInfoFromFlashvarUrl(rawFlashvarUrl: String?): VideoPathInfo? {
         if (rawFlashvarUrl.isNullOrBlank()) return null
         return Regex("""/(\d+)/(\d+)/[^/]+\.mp4""").find(rawFlashvarUrl)?.let { match ->
             val group = match.groupValues[1]
             val videoId = match.groupValues[2]
             if (group.isNotBlank() && videoId.isNotBlank()) {
                 VideoPathInfo(group, videoId)
             } else { null }
         }
    }

     private suspend fun checkUrlExists(url: String, referer: String?): Boolean {
        return try {
            // Dùng HEAD hoặc GET với Range để tiết kiệm băng thông
            val response = app.get(url, referer = referer, headers = mapOf("Range" to "bytes=0-0"), allowRedirects = true)
            println("HEAD Check for $url -> Status Code: ${response.code}")
            response.isSuccessful // Kiểm tra mã trạng thái 2xx
        } catch (e: Exception) {
            println("HEAD Check FAILED for $url: ${e.message?.take(100)}")
            false // Coi như không tồn tại nếu có lỗi mạng
        }
    }

    // --- Các hàm chính của API ---

    // Đã sửa: Sử dụng coroutines thay cho apmap và newHomePageResponse
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val homePageItems = listOf(
           Pair("Đang Xem", "/"),
           Pair("Mới Nhất", "/clip-sex-moi/"),
           Pair("Hay Nhất", "/clip-sex-hay/"),
           Pair("Hot Nhất", "/clip-sex-hot/")
       )

        val allResults = mutableListOf<HomePageList>()
        // Sử dụng AtomicBoolean cho an toàn luồng khi cập nhật từ nhiều coroutine
        val hasNextPageAtomic = AtomicBoolean(request.data == "/clip-sex-moi/")

        // Dùng coroutineScope để chạy song song
        coroutineScope {
            val deferredHomePageLists = homePageItems.map { (name, url) ->
                 // Chạy mỗi mục trong một coroutine riêng
                async {
                    val currentPage = if (request.data == url) page else 1
                    // Chỉ tải trang tiếp theo cho mục "Mới nhất"
                    if (currentPage > 1 && url != "/clip-sex-moi/") return@async null

                    try {
                        val pageUrl = if (currentPage > 1 && url == "/clip-sex-moi/") {
                            fixUrl(url.removeSuffix("/") + "/$currentPage/")
                        } else {
                            fixUrl(url)
                        }
                        val document = app.get(pageUrl).document

                        val itemsSelector = when (url) {
                            "/" -> "#list_videos_videos_watched_right_now_items .item"
                            "/clip-sex-moi/" -> ".main-container .list-videos .item" // Selector thử nghiệm
                            "/clip-sex-hay/" -> "#list_videos_common_videos_list_items .item"
                            "/clip-sex-hot/" -> "#list_videos_common_videos_list_items .item"
                            else -> ".main-container .list-videos .item" // Fallback
                        }
                        println("Fetching $name ($url) - Page: $currentPage - Using Selector: $itemsSelector")

                        val items = document.select(itemsSelector).mapNotNull { it.toSearchResponse() }
                        println("Found ${items.size} items for $name")

                        // Cập nhật hasNextPage chỉ khi đang xử lý mục "Mới nhất"
                        if (url == "/clip-sex-moi/") {
                             val hasNextLink = document.select(".pagination .next a[href]").isNotEmpty()
                             hasNextPageAtomic.set(hasNextLink) // Cập nhật biến Atomic
                        }

                        if (items.isNotEmpty()) {
                            HomePageList(name, items) // Trả về HomePageList nếu có items
                        } else {
                            if (currentPage == 1) { // Chỉ cảnh báo nếu trang đầu tiên không có item
                                println("WARN: No items found for $name using selector $itemsSelector on page $pageUrl")
                            }
                            null // Trả về null nếu không có item
                        }
                    } catch (e: Exception) {
                        println("ERROR fetching $name ($url): ${e.message?.take(100)}")
                        e.printStackTrace()
                        null // Trả về null nếu có lỗi
                    }
                }
            }
            // Đợi tất cả các coroutine hoàn thành và lấy kết quả, lọc bỏ null
            allResults.addAll(deferredHomePageLists.awaitAll().filterNotNull())
        } // Kết thúc coroutineScope

        // Sắp xếp lại kết quả theo thứ tự ban đầu của homePageItems
        val orderedResults = homePageItems.mapNotNull { (name, _) ->
            allResults.find { it.name == name }
        }

        // Ném lỗi nếu trang đầu tiên không tải được gì cả
        if (orderedResults.isEmpty() && page == 1) {
            throw ErrorLoadingException("Không tải được trang chủ")
        }

        // Dùng newHomePageResponse
        // Chỉ coi là có trang tiếp theo nếu đang yêu cầu dữ liệu của mục "Mới nhất" và biến Atomic là true
        return newHomePageResponse(orderedResults, hasNext = hasNextPageAtomic.get() && request.data == "/clip-sex-moi/")
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchUrl = "$mainUrl/search/${query}/"
        val document = app.get(searchUrl).document
        // Đảm bảo dùng selector đúng
        return document.select("#list_videos_videos_list_search_result_items .item").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val htmlContent = document.html()
        val flashvarsString = findJsVariableContent(htmlContent, "flashvars")
        val title = extractJsVar(flashvarsString ?: "", "video_title")
            ?: document.selectFirst("head title")?.text()?.substringBefore(" - xNhau")?.trim()
            ?: "Không có tiêu đề"
        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: fixUrlNull(extractJsVar(flashvarsString ?: "", "preview_url2"))
            ?: fixUrlNull(extractJsVar(flashvarsString ?: "", "preview_url1"))
             ?: fixUrlNull(extractJsVar(flashvarsString ?: "", "preview_url"))

        val description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: document.selectFirst(".info .item:contains(Mô tả:)")?.text()?.replace("Mô tả:", "")?.trim()
        val tags = (document.select(".info-content a[href^=/tags/]").mapNotNull { it.text() } +
                document.select(".info-content a[href^=/the-loai/]").mapNotNull { it.text() }).distinct()
        val recommendations = document.select("#list_videos_related_videos_items .item").mapNotNull { it.toSearchResponse() }

        // Dùng newMovieLoadResponse (đã đúng)
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
    }

    override suspend fun loadLinks(
        data: String, // Chính là URL của phim
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document
        val htmlContent = document.html()

        // 1. Lấy videoId (ưu tiên pageContext, fallback về URL)
        val pageContextString = findJsVariableContent(htmlContent, "pageContext")
        val videoId = extractJsVar(pageContextString ?: "", "videoId")
                       ?: data.substringAfterLast("video/", "").substringBefore("/")

        // 2. Lấy group từ URL ảnh thumbnail (ưu tiên og:image)
        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        val group = extractGroupFromPosterUrl(posterUrl)

        if (videoId.isBlank() || group.isNullOrBlank()) {
            println("WARN: Không thể lấy videoId ('$videoId') hoặc group ('$group') cho URL: $data")
            return false // Không thể tiếp tục nếu thiếu thông tin
        }

        println("INFO: Extracted videoId: $videoId, group: $group")

        // 3. Gọi hàm helper để tạo và *kiểm tra* các link chất lượng
        return generateAndVerifyLinks(group, videoId, callback)
    }

    /**
     * Hàm helper tạo link trên storageUrl, kiểm tra sự tồn tại và callback
     */
    private suspend fun generateAndVerifyLinks(
        group: String,
        videoId: String,
        callback: (ExtractorLink) -> Unit
    ) : Boolean {
         try {
            // Danh sách chất lượng cần kiểm tra (cao xuống thấp để ưu tiên)
             val qualitiesToCheck = listOf(
                 Triple("1080", "_1080p", 1080),
                 Triple("720", "_720p", 720),
                 Triple("480", "", 480) // Chất lượng cơ bản, không có suffix
             )

            var foundLink = false
            // Sắp xếp để chất lượng cao nhất được callback trước nếu tồn tại
             qualitiesToCheck.sortedByDescending { it.third }.forEach { (qualityLabel, suffix, qualityInt) ->
                 val fileName = "$videoId$suffix.mp4"
                 val fileUrl = "$storageUrl/$group/$videoId/$fileName"

                 // Kiểm tra xem link có thực sự tồn tại không bằng HEAD request
                 if (checkUrlExists(fileUrl, mainUrl)) {
                     println("INFO: Adding VALID link for ${qualityLabel}p: $fileUrl")
                     callback(
                        // Dùng newExtractorLink (đã đúng)
                        newExtractorLink(
                            source = this.name, // Tên provider
                            name = "${this.name} ${qualityLabel}p", // Tên link kèm chất lượng
                            url = fileUrl,
                            type = ExtractorLinkType.VIDEO // Luôn là VIDEO vì là MP4
                        ) {
                            this.referer = mainUrl // Đặt referer
                            this.quality = qualityInt // Gán chất lượng Int
                        }
                    )
                    foundLink = true // Đánh dấu đã tìm thấy link hợp lệ
                 } else {
                     println("WARN: Skipping ${qualityLabel}p link (check failed): $fileUrl")
                 }
            }

             // Fallback quan trọng: Nếu không có link nào qua được checkUrlExists
             // (kể cả 480p), thử thêm link 480p mà không cần kiểm tra.
            if (!foundLink) {
                 println("WARN: No links passed HEAD check. Adding 480p speculatively.")
                 val fileName480 = "$videoId.mp4" // Link 480p không có suffix
                 val fileUrl480 = "$storageUrl/$group/$videoId/$fileName480"
                  callback(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} 480p",
                            url = fileUrl480,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = 480 // Chất lượng 480
                        }
                    )
                 foundLink = true // Đánh dấu đã tìm thấy link fallback
            }

            if (!foundLink) { // Trường hợp cả fallback 480p cũng không được thêm (ít xảy ra)
                 println("WARN: Không tạo / thêm được link nào hợp lệ cho video $videoId")
            }
            return foundLink // Trả về true nếu ít nhất 1 link được thêm (kể cả fallback)

        } catch (e: Exception) {
            println("ERROR: Lỗi khi tạo link cho video $videoId: ${e.message}")
            e.printStackTrace()
            return false // Trả về false nếu có lỗi nghiêm trọng xảy ra
        }
    }
} // Đóng class XnhauProvider
