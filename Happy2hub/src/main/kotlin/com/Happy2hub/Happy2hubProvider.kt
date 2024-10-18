package com.Happy2hub

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.extractors.PixelDrain
import com.lagradost.cloudstream3.extractors.Voe

@CloudstreamPlugin
class ixipornProvider: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Happy2hub())
        registerExtractorAPI(Voe())
        registerExtractorAPI(PixelDrain())
    }
}