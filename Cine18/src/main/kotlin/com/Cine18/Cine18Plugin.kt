package com.Cine18

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Cine18Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Cine18())
    }
}
