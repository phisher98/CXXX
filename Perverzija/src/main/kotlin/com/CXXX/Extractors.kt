package com.CXXX

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

open class Xtremestream : ExtractorApi() {
    override var name = "Xtremestream"
    override var mainUrl = "https://pervl4.xtremestream.xyz"
    override val requiresReferer = true
    private val client = OkHttpClient()

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit){

        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            .header("Referer", referer.toString())
            .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
            .build()

        val response = client.newCall(request).execute()
        val html = response.body?.string() ?: return

        val playerScript =
            Jsoup.parse(html).selectXpath("//script[contains(text(),'var video_id')]")
                .html()
        val sources = mutableListOf<ExtractorLink>()
        if (playerScript.isNotBlank()) {
            val videoId = playerScript.substringAfter("var video_id = `").substringBefore("`;")
            val m3u8LoaderUrl =
                playerScript.substringAfter("var m3u8_loader_url = `").substringBefore("`;")

            if (videoId.isNotBlank() && m3u8LoaderUrl.isNotBlank()) {
                M3u8Helper.generateM3u8(
                    name,
                    "$m3u8LoaderUrl/$videoId",
                    "$m3u8LoaderUrl/$videoId"
                ).forEach { link ->
                    sources.add(link)
                }
            }
        }
        sources.forEach { source ->
            callback.invoke(source)
        }
    }
}
