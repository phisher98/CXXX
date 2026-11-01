package com.watchuu.porneec

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PorneecPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PorneecProvider())
    }
}
