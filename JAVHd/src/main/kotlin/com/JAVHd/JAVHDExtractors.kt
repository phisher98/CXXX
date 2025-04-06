package com.JAVHd

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.*

open class Stbturbo : ExtractorApi() {
    override var name = "Stbturbo"
    override var mainUrl = "https://stbturbo.xyz/"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        with(app.get(url)) {
            this.document.let { document ->
                val finalLink = document.select("#video_player").attr("data-hash")
                return listOf(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = httpsify(finalLink),
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }
        return null
    }
    }



class Turbovid : Stbturbo() {
    override var name = "Stbturbo"
    override var mainUrl = "https://turbovid.xyz/"
    override val requiresReferer = false
}

class MyCloudZ : VidhideExtractor() {
    override var name = "MyCloudZ"
    override var mainUrl = "https://mycloudz.cc/"
    override val requiresReferer = false
}

class Cloudwish : StreamWishExtractor() {
    override var name = "Cloudwish"
    override var mainUrl = "https://cloudwish.xyz/"
    override val requiresReferer = false
}
