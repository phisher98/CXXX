package com.Happy2hub

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.extractors.PixelDrain
import com.lagradost.cloudstream3.extractors.Voe
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.extractors.Lulustream1
import com.lagradost.cloudstream3.extractors.DoodstreamCom

@CloudstreamPlugin
class Happy2hubProvider: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Happy2hub())
        registerExtractorAPI(Voe())
        registerExtractorAPI(PixelDrain())
        registerExtractorAPI(StreamTape())
        registerExtractorAPI(Lulustream1())
        registerExtractorAPI(DoodstreamCom())
    }
}