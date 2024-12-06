package com.CXXX

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class HqpornerPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Hqporner())
        registerExtractorAPI(Mydaddy())
    }
}