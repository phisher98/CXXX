package com.CXXX

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

open class Mydaddy : ExtractorApi() {
    override val name = "Mydaddy"
    override val mainUrl = "https://www.mydaddy.cc"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document= app.get(url).document.selectFirst("script:containsData(do_pl())")
                ?.toString()
        val jw= document?.substringAfter("replaceAll")?.substringAfter(",")?.substringBefore(")") ?:""
        val (one, _, three) = jw.split("+")
            .map { it.trim().removeSurrounding("\"") }
            val first = document?.let { Regex("""$one\s*=\s*"(.*?)";""").find(it)?.groupValues?.get(1) }
                ?.removePrefix("//")
                ?.removeSuffix("/") ?:""
        val third = document?.let { Regex("""$three\s*=\s*"(.*?)";""").find(it)?.groupValues?.get(1) } ?:""
        val finalurl="https://$first/pubs/$third"
        val regex = Regex("""title=\\"(\d+p|4K)""")
        val matches = regex.findAll(document.toString())
        val qualities = mutableListOf<String>()
        for (match in matches) {
            val quality = match.groupValues[1]
            if (quality == "4K") {
                qualities.add("2160")
            } else {
                qualities.add(quality.dropLast(1))
            }
        }
        for (quality in qualities) {
            val href="$finalurl/$quality.mp4"
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = href
                ) {
                    this.referer = ""
                    this.quality = getQualityFromName(quality)
                }
            )
        }
    }
}