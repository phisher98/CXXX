package com.CXXX

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.extractors.StreamTape

@CloudstreamPlugin
class SuperJavPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SuperJav())
        registerExtractorAPI(EmturbovidExtractor())
        registerExtractorAPI(StreamTape())
        registerExtractorAPI(watchadsontape())
        registerExtractorAPI(fc2stream())
    }
}
