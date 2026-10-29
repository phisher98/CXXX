package com.archivebate

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArchivebatePlugin : Plugin() {
    override fun load(context: Context) {
        ArchivebateBrowser.init(context)
        registerMainAPI(ArchivebateProvider())
    }
}
