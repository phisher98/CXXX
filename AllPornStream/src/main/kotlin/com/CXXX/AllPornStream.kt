package com.CXXX

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.AppUtils.toJson

class AllPornStream : MainAPI() {
    override var mainUrl = "https://allpornstream.com"
    override var name = "AllPornStream"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "studio=SexMex" to "SexMex Studio",
        "studio=BrazzersExxtra" to "BrazzersExxtra Studio",
        "studio=EvilAngel" to "EvilAngel Studio",
        "studio=PornWorld" to "PornWorld Studio",
        "studio=DorcelClub" to "DorcelClub Studio",
        "studio=TabooHeat" to "TabooHeat Studio",
        "studio=MyPervyFamily" to "MyPervyFamily Studio",
        "studio=BangBus" to "BangBus Studio",
        "studio=PureTaboo" to "PureTaboo Studio",
        "studio=PervMom" to "PervMom Studio",
        "studio=NubileFilms" to "NubileFilms Studio",
        "studio=FamilyStrokes" to "FamilyStrokes Studio",
        "studio=BrattySis" to "BrattySis Studio",
        "studio=MyFriendsHotMom" to "MyFriendsHotMom Studio",
        "studio=SweetSinner" to "SweetSinner Studio",
        "studio=FamilyXXX" to "FamilyXXX Studio",
        "studio=StepSiblingsCaught" to "StepSiblingsCaught Studio",
        )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val res = app.get("$mainUrl/api/posts?${request.data}&page=$page").parsedSafe<Posts>()?.posts
        val home = res?.map {
            it.toSearchResult() }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home!!,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Post.toSearchResult(): SearchResponse {
        val title = this.videoTitle
        val href = this.id
        val posterUrl = this.imageDetails.flatten()
            .firstOrNull { it.startsWith("http") }
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }

    }

    private fun RelatedPost.toSearchResult(): SearchResponse {
        val title = this.videoTitle
        val href = this.id
        val posterUrl = this.imageDetails
            .flatten()
            .firstOrNull { it.startsWith("http") }
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..3) {
            val res = app.get("$mainUrl/api/posts?search=$query&page=$i").parsedSafe<Posts>()?.posts.orEmpty()
            val results = res.map { it.toSearchResult() }
            val newResults = results.filterNot { it in searchResponse }
            searchResponse.addAll(newResults)
            if (newResults.isEmpty()) break
        }

        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val res = app.get("$mainUrl/api/post?id=${url.substringAfterLast("/")}").parsedSafe<Load>()
        val loaddata=res?.post
        val title = loaddata?.videoTitle ?: "Unknown"
        val poster = loaddata?.imageDetails?.flatten()
            ?.firstOrNull { it.startsWith("http") }
        val tags = loaddata?.categories?.map { it }
        val description = loaddata?.videoDescription
        val recommendations= res?.relatedPosts?.map { it.toSearchResult() }
        val hrefs=res?.urls?.map { it.url }?.toJson()
        return newMovieLoadResponse(title, url, TvType.NSFW, hrefs) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsedList = data.fromJson<List<String>>()
        parsedList.forEach {
            loadExtractor(it, "$mainUrl/", subtitleCallback, callback)
        }
        return true
    }
    val gson = Gson()
    private inline fun <reified T> String.fromJson(): T = gson.fromJson(this, object : TypeToken<T>() {}.type)
}


class StreamTapeto : StreamTape() {
    override var mainUrl = "https://streamtape.to"
}