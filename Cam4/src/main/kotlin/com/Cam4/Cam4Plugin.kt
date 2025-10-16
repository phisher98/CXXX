package com.Cam4

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Cam4Plugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Cam4Provider())
    }
}