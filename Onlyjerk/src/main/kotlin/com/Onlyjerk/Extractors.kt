package com.megix

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.extractors.Vidguardto

class Dooodster : DoodLaExtractor() {
    override var mainUrl = "https://dooodster.com"
}

class Listeamed : Vidguardto() {
    override var mainUrl = "https://listeamed.net"
}

class Beamed : Vidguardto() {
    override var mainUrl = "https://bembed.net"
}

class Bgwp : Bigwarp() {
    override var mainUrl = "https://bgwp.cc"
}

open class Bigwarp : ExtractorApi() {
    override var name = "Bigwarp"
    override var mainUrl = "https://bigwarp.io"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val link = app.get(url, allowRedirects = false).headers["location"] ?: url
        val source = app.get(link).document.selectFirst("body > script").toString()
        val regex = Regex("""file:\s*\"((?:https?://|//)[^\"]+)""")
        val matchResult = regex.find(source)
        val match = matchResult?.groupValues?.get(1)

        if (match != null) {
            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    match,
                    "",
                    Qualities.Unknown.value
                )
            )
        }
    }
}
