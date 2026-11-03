package com.blumovies

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BluMoviesPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(BluMoviesProvider())
    }
}
