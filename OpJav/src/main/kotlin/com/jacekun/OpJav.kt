package com.jacekun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.XStreamCdn
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class OpJav : MainAPI() {
    private val globalTvType = TvType.NSFW
    override var name = "OpJAV"
    override var mainUrl = "https://opjav.com"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasDownloadSupport = true
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val prefix = "Watch JAV"

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(mainUrl).document
        val all = ArrayList<HomePageList>()
        val body = document.getElementsByTag("body")
        val rows = mutableListOf<Pair<String, Element>>()
        val selectorSimple = "div.list-film-simple > div.item"
        val selectorRows = "div.list-film.row > div"

        body.select("div.content").forEach {
            if (it != null) {
                if (it.select(selectorRows).isEmpty()) {
                    rows.add(Pair(selectorSimple, it))
                } else {
                    rows.add(Pair(selectorRows, it))
                }
            }
        }

        var count = 0
        rows.forEach { row ->
            count++
            val title = "Row $count"
            val isSimple = row.first == selectorSimple
            val entries = row.second.select(row.first)
            val elements = entries.mapNotNull {
                if (it == null) return@mapNotNull null
                val link: String
                val name: String
                val image: String?
                var year: Int? = null

                if (isSimple) {
                    val inner = it.select("div.info")
                    link = fixUrlNull(inner.select("a").firstOrNull()?.attr("href")) ?: return@mapNotNull null
                    name = inner.text().trim()
                    val imgsrc = it.select("img")
                    image = fixUrlNull(imgsrc.attr("src").takeIf { s -> s.isNotBlank() } ?: imgsrc.attr("data-src"))
                } else {
                    val inner = it.select("div.inner")
                    val poster = inner.select("a.poster")
                    link = fixUrlNull(poster.attr("href")) ?: return@mapNotNull null
                    name = it.text().trim().removePrefix("HD").trim()
                    image = fixUrlNull(poster.select("img").attr("src"))
                    year = inner.select("dfn").getOrNull(1)?.text()?.toIntOrNull()
                }
                newMovieSearchResponse(
                    name = name,
                    url = link,
                    type = globalTvType,
                ).apply {
                    this.posterUrl = image
                    this.year = year
                }
            }.distinctBy { a -> a.url }

            if (elements.isNotEmpty()) {
                all.add(
                    HomePageList(
                        name = title,
                        list = elements
                    )
                )
            }
        }
        return newHomePageResponse(all)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        val encoded = query.trim().replace(" ", "+")

        for (page in 1..3) {
            val url = if (page == 1) "$mainUrl/search/$encoded/" else "$mainUrl/search/$encoded/page/$page/"
            val document = app.get(url).document
                .select("div.block-body > div.list-film.row > div, div.list-film-simple > div.item")

            val results = document.mapNotNull {
                val inner = it.select("div.inner, div.info")
                val innerPost = inner.select("a.poster, a").firstOrNull() ?: return@mapNotNull null

                val link = fixUrlNull(innerPost.attr("href")) ?: return@mapNotNull null
                val title = innerPost.attr("title").trim().removePrefix(prefix).trim().takeIf { t -> t.isNotBlank() }
                    ?: inner.text().trim()
                val imgsrc = innerPost.select("img")
                val image = fixUrlNull(imgsrc.attr("src").takeIf { s -> s.isNotBlank() } ?: imgsrc.attr("data-src"))
                val year = inner.select("dfn").lastOrNull()?.text()?.trim()?.toIntOrNull()

                newMovieSearchResponse(
                    name = title,
                    url = link,
                    type = globalTvType,
                ).apply {
                    this.posterUrl = image
                    this.year = year
                }
            }
            val unique = results.filterNot { item -> searchResponse.any { it.url == item.url } }
            if (unique.isEmpty()) break
            searchResponse.addAll(unique)
        }

        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val poster = fixUrlNull(
            doc.select("meta[itemprop=image]").getOrNull(1)?.attr("content")?.trim()
                ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
        )
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.removePrefix(prefix)?.trim()
            ?: doc.title().removePrefix(prefix).trim()
        val descript = doc.selectFirst("meta[name=keywords]")?.attr("content")?.trim()
        val year = doc.selectFirst("meta[itemprop=dateCreated]")?.attr("content")?.toIntOrNull()

        val tags = doc.select("dl > dd").getOrNull(1)?.select("a")?.mapNotNull {
            it?.text()?.trim()?.takeIf { t -> t.isNotBlank() }
        }

        // Fetch server links
        val watchlink = ArrayList<String>()
        val mainLink = doc.selectFirst("div.buttons.row a, a.btn-watch")?.attr("href").orEmpty()

        if (mainLink.isNotBlank()) {
            runCatching {
                val epsDoc = app.get(url = fixUrl(mainLink), referer = mainUrl).document
                epsDoc.select("div.block.servers li").mapNotNull {
                    val inner = it?.selectFirst("a") ?: return@mapNotNull null
                    val linkUrl = inner.attr("href")
                    val linkId = inner.attr("id")
                    Pair(linkUrl, linkId)
                }.amap {
                    val ajaxHead = mapOf(
                        Pair("Origin", mainUrl),
                        Pair("Referer", it.first)
                    )
                    val ajaxData = mapOf(
                        Pair("NextEpisode", "1"),
                        Pair("EpisodeID", it.second)
                    )
                    app.post("$mainUrl/ajax", headers = ajaxHead, data = ajaxData)
                        .document.select("iframe").forEach { iframe ->
                            val serverLink = iframe?.attr("src")?.trim().orEmpty()
                            if (serverLink.isNotBlank()) {
                                watchlink.add(serverLink)
                            }
                        }
                }
            }
        }

        doc.select("iframe[src]").forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
            if (!src.contains("google") && !src.contains("facebook")) {
                watchlink.add(src)
            }
        }

        val streamUrl = watchlink.distinct().toJson()
        return newMovieLoadResponse(
            name = title,
            url = url,
            type = globalTvType,
            dataUrl = streamUrl,
        ).apply {
            this.apiName = this@OpJav.name
            this.posterUrl = poster
            this.year = year
            this.plot = descript
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val urls = tryParseJson<List<String>>(data)
            ?: if (data.startsWith("http")) listOf(data) else emptyList()

        urls.forEach { link ->
            val url = fixUrl(link.trim())
            when {
                url.contains("opmovie.xyz") -> {
                    runCatching {
                        XStreamCdn().let {
                            it.domainUrl = "opmovie.xyz"
                            it.getSafeUrl(
                                url = url,
                                referer = url,
                                subtitleCallback = subtitleCallback,
                                callback = callback
                            )
                        }
                    }
                }
                else -> {
                    runCatching {
                        loadExtractor(
                            url = url,
                            referer = "$mainUrl/",
                            subtitleCallback = subtitleCallback,
                            callback = callback
                        )
                    }
                }
            }
        }
        return true
    }
}