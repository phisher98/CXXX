package com.PornOne

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.extractors.StreamTape

@CloudstreamPlugin
class PornOnePlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PornOneProvider())
    }
}