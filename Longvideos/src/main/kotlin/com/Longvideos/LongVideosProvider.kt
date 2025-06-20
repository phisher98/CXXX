package com.megix

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class LongvideosProvider: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Longvideos())
    }
}
