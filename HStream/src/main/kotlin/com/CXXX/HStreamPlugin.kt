package com.CXXX

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class HStreamPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HStream())
    }
}