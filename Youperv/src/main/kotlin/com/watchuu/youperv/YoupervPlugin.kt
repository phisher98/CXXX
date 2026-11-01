package com.watchuu.youperv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class YoupervPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(YoupervProvider())
    }
}
