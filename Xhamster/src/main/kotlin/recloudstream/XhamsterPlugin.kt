package recloudstream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
// Import này có thể cần thiết nếu registerMainAPI là extension function
// import com.lagradost.cloudstream3.plugins.PluginManager.registerMainAPI

@CloudstreamPlugin // Đánh dấu đây là plugin
class XhamsterPlugin: Plugin() { // Kế thừa Plugin
    override fun load(context: Context) {
        // Tất cả provider nên được thêm vào theo cách này.
        // Đăng ký AnimeHayProvider
        registerMainAPI(XhamsterProvider()) // Gọi đăng ký provider ở đây
    }
}
