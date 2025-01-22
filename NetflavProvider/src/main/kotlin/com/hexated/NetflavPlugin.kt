package com.anhdaden

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.*

@CloudstreamPlugin
class NetflavPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NetflavProvider())
    }
}