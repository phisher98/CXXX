package com.xxxtube

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class XXXTubePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(XXXTubeProvider())
    }
}
