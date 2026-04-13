package com.xprimehub

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.extractors.PixelDrain
import com.lagradost.cloudstream3.app

@CloudstreamPlugin
class XPrimeHubProvider: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(XPrimeHub())
        registerExtractorAPI(PixelDrain())
        registerExtractorAPI(VCloud())
    }
    companion object {
        private const val DOMAINS_URL =
            "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json"
        var cachedDomains: Domains? = null

        suspend fun getDomains(forceRefresh: Boolean = false): Domains? {
            if (cachedDomains == null || forceRefresh) {
                try {
                    cachedDomains = app.get(DOMAINS_URL).parsedSafe<Domains>()
                } catch (e: Exception) {
                    e.printStackTrace()
                    return null
                }
            }
            return cachedDomains
        }

        data class Domains(
            @JsonProperty("xprimehub")
            val xprimehub: String,
        )
    }
}