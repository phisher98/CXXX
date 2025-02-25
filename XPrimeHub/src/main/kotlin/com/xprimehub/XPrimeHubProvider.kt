package com.xprimehub

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.extractors.PixelDrain

@CloudstreamPlugin
class XPrimeHubProvider: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(XPrimeHub())
        registerExtractorAPI(PixelDrain())
        registerExtractorAPI(VCloud())
    }
}