package com.JAVHd

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.extractors.StreamTape

@CloudstreamPlugin
class JAVHDPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(JAVHDProvider())
        registerExtractorAPI(StreamTape())
        registerExtractorAPI(Stbturbo())
        registerExtractorAPI(Turbovid())
        registerExtractorAPI(MyCloudZ())
        registerExtractorAPI(Cloudwish())
    }
}