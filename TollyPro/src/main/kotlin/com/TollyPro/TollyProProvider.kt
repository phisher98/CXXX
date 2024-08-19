package com.TollyPro

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class TollyProProvider: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TollyPro())
        registerExtractorAPI(Ds2play())
        registerExtractorAPI(Vidsp())
    }
}
