package com.Chatrubate


import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class ChatrubateProvider : MainAPI() {
    override var mainUrl              = "https://chaturbate.com"
    override var name                 = "Chatrubate"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
            "/api/ts/roomlist/room-list/?limit=90" to "Featured",
            "/api/ts/roomlist/room-list/?genders=m&limit=90" to "Male",
            "/api/ts/roomlist/room-list/?genders=f&limit=90" to "Female",
            "/api/ts/roomlist/room-list/?genders=c&limit=90" to "Couples",
            "/api/ts/roomlist/room-list/?genders=t&limit=90" to "Trans",
        )
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
            var offset : Int
            if(page == 1)
            {
                offset = 0
            }
            else
            {
                offset = 90 * (page - 1)
            }
            val responseList = app.get("$mainUrl${request.data}&offset=$offset").parsedSafe<Response>()!!.rooms.map { room ->
                LiveSearchResponse(
                    name      = room.username,
                    url       = "$mainUrl/${room.username}",
                    apiName   = this@ChatrubateProvider.name,
                    type      = TvType.Live,
                    posterUrl = room.img,
                    lang      = null
                )
            }
            return newHomePageResponse(HomePageList(request.name, responseList, isHorizontalImages = true),hasNext = true)

    }

    override suspend fun search(query: String): List<SearchResponse> {

        val searchResponse = mutableListOf<LiveSearchResponse>()

        for (i in 0..3) {
            val results = app.get("$mainUrl/api/ts/roomlist/room-list/?hashtags=$query&limit=90&offset=${i*90}").parsedSafe<Response>()!!.rooms.map { room ->
                LiveSearchResponse(
                    name      = room.username,
                    url       = "$mainUrl/${room.username}",
                    apiName   = this@ChatrubateProvider.name,
                    type      = TvType.Live,
                    posterUrl = room.img,
                    lang      = null
                )
            }
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

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().toString().replace("| PornHoarder.tv","")
        val poster = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
    

         return LiveStreamLoadResponse(
            name      = title,
            url       = url,
            apiName   = this.name,
            dataUrl   = url,
            posterUrl = poster,
            plot      = description,
        )
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = app.get(data).document
        val script = doc.select("script").find { item-> item.html().contains("window.initialRoomDossier") }
        val json = script!!.html().substringAfter("window.initialRoomDossier = \"").substringBefore(";").unescapeUnicode()
        val m3u8Url = "\"hls_source\": \"(.*).m3u8\"".toRegex().find(json)?.groups?.get(1)?.value
        callback.invoke(
            ExtractorLink(
                source = name,
                name = name,
                url = m3u8Url.toString()+".m3u8",
                referer = "",
                Qualities.Unknown.value,
                isM3u8 = true
            )
        )

        return true
    }

    data class Room(
        @JsonProperty("img")    val img: String       = "",
        @JsonProperty("username")  val username: String  = "",
        @JsonProperty("subject")  val subject: String  = "",
        @JsonProperty("tags")  val tags: List<String> = arrayListOf()

    )

    data class Response(
        @JsonProperty("all_rooms_count") val all_rooms_count: String      = "",
        @JsonProperty("room_list_id")   val room_list_id: String        = "",
        @JsonProperty("total_count")   val total_count: String        = "",
        @JsonProperty("rooms")  val rooms: List<Room> = arrayListOf()
    )
}

fun String.unescapeUnicode() = replace("\\\\u([0-9A-Fa-f]{4})".toRegex()) {
    String(Character.toChars(it.groupValues[1].toInt(radix = 16)))
}
