package com.watchuu.desihub

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DesiHubPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DesiHubProvider())
    }
}
