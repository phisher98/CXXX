package com.TollyPro

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Contex
import com.lagradost.cloudstream3.extractors.VidHidePro3

@CloudstreamPlugin
class TollyProProvider: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TollyPro())
        registerExtractorAPI(Ds2play())
        registerExtractorAPI(Vidsp())
        registerExtractorAPI(VidHidePro3())
        registerExtractorAPI(VidHideplus())
    }
}
