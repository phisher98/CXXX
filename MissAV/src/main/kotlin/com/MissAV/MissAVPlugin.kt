package com.PornhoarderPlugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.Sextb.Stbturbo
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.extractors.Wishonly

@CloudstreamPlugin
class MissAVPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MissAVProvider())
    }
}