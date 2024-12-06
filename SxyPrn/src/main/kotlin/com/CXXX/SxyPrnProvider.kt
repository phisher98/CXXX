package com.CXXX

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.extractors.Lulustream1
import com.lagradost.cloudstream3.extractors.Vidguardto
import com.lagradost.cloudstream3.extractors.DoodstreamCom


@CloudstreamPlugin
class SxyPrnProvider : Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(SxyPrn())
        registerExtractorAPI(StreamTape())
        registerExtractorAPI(Lulustream1())
        registerExtractorAPI(Vidguardto())
        registerExtractorAPI(DoodstreamCom())
    }
}
