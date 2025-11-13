package com.megix

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Xmovies4uProvider: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Xmovies4u())
    }
}
