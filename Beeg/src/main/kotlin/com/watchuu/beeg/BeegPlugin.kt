package com.watchuu.beeg

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BeegPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(BeegProvider())
    }
}
