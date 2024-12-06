package com.desisins
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class Desisins : MainAPI() { // all providers must be an instance of MainAPI
    override var mainUrl = "https://desisins.com"
    var surl= "https://shorts.desisins.com"
    override var name = "Desisins"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )
    
    private suspend fun getData(url: String,i: Int,id:Int): List<SearchResponse> {
       
        val response = app.post(
            "$url/wp-admin/admin-ajax.php",
            data = mapOf(
			    "action" to "grid_ajax_load_more",
			    "cat_id" to "$id",
			    "current_posts" to "$i",
			    "type" to ""
            )
        ).text

        var document=  Jsoup.parse(response)
        return document.select("div.home_post_cont").mapNotNull {
            toResult(it)
        }
    }
    private fun toResult(post: Element): SearchResponse {
        val url = post.select("h3 > a").attr("href")
        val title = post.select("h3 > a").text()
        var imageUrl = post.select("img").attr("src")
       // Log.d("post",post.toString())
        //val quality = post.select(".video-label").text()
        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = imageUrl
        }
    }
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
     //val url =if(page==1) "$mainUrl/${request.data}/" else  "$mainUrl/${request.data}/page/$page/" 
                val shorts = getData(surl,page*12-12,-1)
                val mms = getData(mainUrl, page * 12 - 12, 4)
		val nri4 = getData(mainUrl, page * 12 - 12, 366)
		val roleplay = getData(mainUrl, page * 12 - 12, 426)
		val livex = getData(mainUrl, page * 12 - 12, 12)
		val liveShow = getData(mainUrl, page * 12 - 12, 7)
		val solo = getData(mainUrl, page * 12 - 12, 8)
		val powershot = getData(mainUrl, page * 12 - 12, 66)
		val model = getData(mainUrl, page * 12 - 12, 2)
		val viral = getData(mainUrl, page * 12 - 12, 19)
		val chat = getData(mainUrl, page * 12 - 12, 365)
		val wksh = getData(mainUrl, page * 12 - 12, 6)
		val premium = getData(mainUrl, page * 12 - 12, 668)
        return newHomePageResponse(
        	 listOf(
        	       HomePageList("Shorts", shorts, false),
				    HomePageList("Mms", mms, false),
				    HomePageList("4NRI", nri4, false),
				    HomePageList("Roleplay", roleplay, false),
				    HomePageList("Livex", livex, false),
				    HomePageList("Live Show", liveShow, false),
				    HomePageList("Solo", solo, false),
				    HomePageList("Powershot", powershot, false),
				    HomePageList("Model", model, false),
				    HomePageList("Viral", viral, false),
				    HomePageList("Chat", chat, false),
				    HomePageList("Wksh", wksh, false),
				    HomePageList("Premium", premium, false) ),true
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document

        return document.select("div.home_post_cont").mapNotNull {
            toResult(it)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1").text()
        //val poster = fixUrlNull(document.select("h2 > img").first()?.attr("src"))
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = ""
 
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var src = app.get(data).document.select("iframe").attr("src")
        
        //Log.d("link",src)
        loadExtractor(
                src,
                "$mainUrl/",
                subtitleCallback,
                callback
           
        )
        return true
    }


}
