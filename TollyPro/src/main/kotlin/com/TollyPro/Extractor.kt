package com.TollyPro

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.LuluStream
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class Vidsp : VidhideExtractor() {
    override var mainUrl = "https://vidsp.lol"
}

class VidHideplus : VidhideExtractor() {
    override var mainUrl = "https://vidhideplus.com"
}
class VidHidedht: VidhideExtractor() {
    override var mainUrl = "https://dhtpre.com"
}

class Vidhidehub : VidhideExtractor() {
    override var mainUrl = "https://vidhidehub.com"
}
class Vidhidetoul : VidhideExtractor() {
    override var mainUrl = "https://toul.hair"
}
class Xtapes : VidhideExtractor() {
    override var mainUrl = "https://xtapes.porn"
    override var name = "Xtapes"
}


class Luluvid : LuluStream() {
    override val name = "Lulustream"
    override val mainUrl = "https://luluvid.com"
}

open class Ds2play : ExtractorApi() {
    override var name = "DoodStream"
    override var mainUrl = "https://ds2play.com"
    override val requiresReferer = false

    override fun getExtractorUrl(id: String): String {
        return "https://dood.wf/d/$id"
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val response0 = app.get(url).text // html of DoodStream page to look for /pass_md5/...
        val md5 =mainUrl+(Regex("/pass_md5/[^']*").find(response0)?.value ?: return null)  // get https://dood.ws/pass_md5/...
        val trueUrl = app.get(md5, referer = url).text + "zUEJeL3mUN?token=" + md5.substringAfterLast("/")   //direct link to extract  (zUEJeL3mUN is random)
        val quality = Regex("\\d{3,4}p").find(response0.substringAfter("<title>").substringBefore("</title>"))?.groupValues?.get(0)
        return listOf(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = trueUrl
            ) {
                this.referer = mainUrl
                this.quality = getQualityFromName(quality)
            }
        ) // links are valid in 8h

    }
}

open class Bigwarp : ExtractorApi() {
    override val name = "Bigwarp"
    override val mainUrl = "https://bigwarp.io"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
    ): List<ExtractorLink>? {
        val response =app.get(url).document
        val script = response.selectFirst("script:containsData(sources)")?.data().toString()
        Regex("sources:\\s*\\[.file:\"(.*)\".*").find(script)?.groupValues?.get(1)?.let { link ->
                return listOf(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = link,
                        INFER_TYPE
                    ) {
                        this.referer = referer ?: "$mainUrl/"
                        this.quality = Qualities.P1080.value
                    }
                )
        }
        return null
    }
}