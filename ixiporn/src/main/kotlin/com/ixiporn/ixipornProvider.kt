package com.ixiporn

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ixipornProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ixiporn())
    }
}
