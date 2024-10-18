package com.TollyPro

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Contex

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
