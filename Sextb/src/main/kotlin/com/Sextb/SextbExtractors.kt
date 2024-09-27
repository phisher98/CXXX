package com.Sextb

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.StreamSB
import com.lagradost.cloudstream3.extractors.StreamTape
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
                    ExtractorLink(
                        name,
                        name,
                        httpsify(finalLink),
                        url,
                        Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
            }
        }
        return null
    }
    }



/*
class TapeAdvertisement : StreamTape() {
    override var mainUrl = "https://tapeadvertisement.com/"
}*/
