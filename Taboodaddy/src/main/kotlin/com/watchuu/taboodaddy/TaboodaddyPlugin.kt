package com.watchuu.taboodaddy

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TaboodaddyPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TaboodaddyProvider())
    }
}
