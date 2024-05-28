package com.Eporner

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class EpornerProvider: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Eporner())
    }
}