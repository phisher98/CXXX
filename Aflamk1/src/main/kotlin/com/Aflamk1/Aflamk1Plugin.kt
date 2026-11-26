package com.Aflamk1

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Aflamk1Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Aflamk1())
    }
}
