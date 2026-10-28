package com.familypornhd

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FamilyPornHDPlugin : Plugin() {
    override fun load(context: Context) {
        FamilyPornHDBrowser.init(context)
        registerMainAPI(FamilyPornHD())
        // gear icon in Extensions -> FamilyPornHD: Cloudflare bypass settings
        openSettings = { ctx -> FamilyPornHDSettings.show(ctx) }
    }
}
