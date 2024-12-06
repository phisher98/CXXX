package com.Xmaza

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class XmazaProvider: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Xmaza())
    }
}