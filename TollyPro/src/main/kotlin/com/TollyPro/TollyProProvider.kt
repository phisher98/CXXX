package com.TollyPro

import android.content.Context
import com.lagradost.cloudstream3.extractors.VidHidePro3
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TollyProProvider: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TollyPro())
        registerExtractorAPI(Ds2play())
        registerExtractorAPI(Vidsp())
        registerExtractorAPI(VidHidePro3())
        registerExtractorAPI(VidHideplus())
        registerExtractorAPI(VidHidedht())
        registerExtractorAPI(Vidhidehub())
        registerExtractorAPI(Bigwarp())
        registerExtractorAPI(Vidhidetoul())
    }
}
