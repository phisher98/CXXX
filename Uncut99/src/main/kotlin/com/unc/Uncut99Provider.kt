package com.unc

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Uncut99Provider: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Uncut99())
    }
}