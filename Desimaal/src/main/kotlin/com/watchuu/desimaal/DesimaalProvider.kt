package com.watchuu.desimaal

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * desimaal.live — built from the captures of 2026-11-01, including its own javascript bundle.
 *
 * The site encrypts its whole api, so this one is worth explaining.
 *
 * Every request carries `x-api-token`, and every response comes back as {"payload": "<base64>"}.
 * Both are CryptoJS `AES.encrypt(value, passphrase)` — the passphrase is a constant in
 * /assets/api-DNTstqCY.js, and the bundle shows exactly what it does with it:
 *
 *     rx = "c4b5a69d…"                                       // the passphrase
 *     x-api-token: AES.encrypt(Date.now().toString(), rx)    // so the token is the clock, sealed
 *     payload:     AES.decrypt(json.payload, rx).toString(Utf8)
 *
 * CryptoJS encrypts with an 8-byte random salt, derives key and iv with OpenSSL's
 * EVP_BytesToKey(MD5) and prefixes the ciphertext with "Salted__". The three functions below do
 * the same thing in reverse, which is why a sealed payload opens here at all.
 *
 * The api itself is clean once readable:
 *     GET /api/videos?page=N&limit=30            listing   {page,total,hasMore,data[]}
 *     GET /api/videos?random=true&limit=30       trending
 *     GET /api/videos/search?q=…&page=N&limit=30 search
 *     GET /api/videos/<id>                       detail
 *     GET /api/videos/<id>/play-url              {data:{video:"https://video.desimaal.live/v?token=…"}}
 *
 * Note: the passphrase is tied to a deploy. If the site rebuilds and stops answering, fetch the
 * bundle again and update KEY — nothing else here needs to change.
 */
class DesimaalProvider : MainAPI() {
    override var mainUrl = "https://desimaal.live"
    override var name = "Desimaal"
    override var lang = "hi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "" to "Latest",
        "trending" to "Trending"
    )

    private val apiUrl = "https://api.desimaal.live/api"

    // ── The api's encryption, reproduced ───────────────────────────────────────

    /** OpenSSL EVP_BytesToKey with MD5, which is what CryptoJS uses for a passphrase. */
    private fun evpBytesToKey(pass: ByteArray, salt: ByteArray, keyLen: Int, ivLen: Int): Pair<ByteArray, ByteArray> {
        var derived = ByteArray(0)
        var block = ByteArray(0)
        val md = MessageDigest.getInstance("MD5")
        while (derived.size < keyLen + ivLen) {
            md.reset()
            md.update(block)
            md.update(pass)
            md.update(salt)
            block = md.digest()
            derived += block
        }
        return derived.copyOfRange(0, keyLen) to derived.copyOfRange(keyLen, keyLen + ivLen)
    }

    private fun seal(plain: String): String {
        val salt = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val (key, iv) = evpBytesToKey(KEY.toByteArray(Charsets.UTF_8), salt, 32, 16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = "Salted__".toByteArray(Charsets.US_ASCII) + salt + body
        return android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
    }

    private fun open(b64: String): String? = runCatching {
        val raw = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
        if (raw.size < 32) return null
        if (String(raw, 0, 8, Charsets.US_ASCII) != "Salted__") return null
        val salt = raw.copyOfRange(8, 16)
        val body = raw.copyOfRange(16, raw.size)
        val (key, iv) = evpBytesToKey(KEY.toByteArray(Charsets.UTF_8), salt, 32, 16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.getOrNull()

    /** One api call: seals the clock as the token, then opens the payload that comes back. */
    private suspend fun api(path: String): JSONObject? {
        val body = runCatching {
            app.get(
                "$apiUrl$path",
                headers = mapOf(
                    "x-api-token" to seal(System.currentTimeMillis().toString()),
                    "Content-Type" to "application/json",
                    "Accept" to "*/*",
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/",
                    "User-Agent" to UA
                ),
                timeout = 30L
            ).text
        }.getOrNull() ?: return null

        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val payload = root.optString("payload", "")
        if (payload.isBlank()) return root
        val plain = open(payload) ?: return null
        return runCatching { JSONObject(plain) }.getOrNull()
    }

    // ── Listings ───────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = if (request.data == "trending") {
            "/videos?random=true&limit=30"
        } else {
            "/videos?page=$page&limit=30"
        }
        val json = api(path) ?: return newHomePageResponse(request.name, emptyList(), false)
        val items = json.optJSONArray("data").toItems()
        val hasMore = json.optBoolean("hasMore", false)
        return newHomePageResponse(request.name, items, hasNext = hasMore && request.data != "trending")
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        if (q.isBlank()) return emptyList()

        val first = api("/videos/search?q=$q&page=1&limit=30")?.optJSONArray("data").toItems()
        if (first.isEmpty()) return first
        val second = api("/videos/search?q=$q&page=2&limit=30")?.optJSONArray("data").toItems()
        return (first + second).distinctBy { it.url }
    }

    private fun org.json.JSONArray?.toItems(): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        if (this == null) return out
        for (i in 0 until length()) {
            val o = optJSONObject(i) ?: continue
            val id = o.optString("_id", "")
            val title = o.optString("title", "")
            if (id.isBlank() || title.isBlank()) continue

            out.add(
                newMovieSearchResponse(title, "$mainUrl/video/$id", TvType.NSFW) {
                    this.posterUrl = o.optString("thumbnail").ifBlank { null }
                }
            )
        }
        return out
    }

    // ── Detail ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val id = url.trimEnd('/').substringAfterLast('/')
        if (id.isBlank()) return null

        val json = api("/videos/$id") ?: return null
        val o = json.optJSONObject("data") ?: return null

        val title = o.optString("title", "Video")
        val series = o.optString("series", "")
        val models = o.optJSONArray("models").toStringList()
        val tags = o.optJSONArray("tags").toStringList()
        val duration = o.optString("duration", "")
        val plays = o.optInt("play_count", 0)

        val plot = buildString {
            if (series.isNotBlank()) appendLine("Series: $series")
            if (models.isNotEmpty()) appendLine("Cast: ${models.joinToString(", ")}")
            if (duration.isNotBlank() && duration != "00:00") appendLine("Duration: $duration")
            if (plays > 0) append("$plays views")
        }.trim().ifBlank { null }

        return newMovieLoadResponse(title, url, TvType.NSFW, id) {
            this.posterUrl = o.optString("thumbnail").ifBlank { null }
            this.plot = plot
            this.tags = (tags + models).distinct().take(20)
            this.year = o.optString("uploadDate").take(4).toIntOrNull()
        }
    }

    private fun org.json.JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until length()) optString(i).trim().takeIf { it.isNotBlank() }?.let { out.add(it) }
        return out
    }

    // ── Streams ────────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = data.trimEnd('/').substringAfterLast('/')
        if (id.isBlank()) return false

        val json = api("/videos/$id/play-url") ?: return false
        val video = json.optJSONObject("data")?.optString("video", "").orEmpty()
        if (video.isBlank()) return false

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = video,
                type = if (video.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "User-Agent" to UA
                )
            }
        )
        return true
    }

    private companion object {
        /** From /assets/api-DNTstqCY.js. Changes when they rebuild the site. */
        const val KEY = "c4b5a69d8e7f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b"

        const val UA = "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"
    }
}
