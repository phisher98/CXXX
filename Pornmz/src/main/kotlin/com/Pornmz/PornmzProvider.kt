package com.Pornmz

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class PornmzProvider: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Pornmz())
    }
}
