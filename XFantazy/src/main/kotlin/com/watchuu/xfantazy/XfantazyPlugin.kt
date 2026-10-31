package com.watchuu.xfantazy

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class XfantazyPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(XfantazyProvider())
    }
}
