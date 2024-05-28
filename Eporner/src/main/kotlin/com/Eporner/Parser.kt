package com.Eporner

import com.fasterxml.jackson.annotation.JsonProperty

data class Root(
    val vid: String,
    val available: Boolean,
    val fallback: Boolean,
    val code: Long,
    val message: String,
    val sources: Sources,
    val backupServers: List<Any?>,
    val backupServersHls: List<Any?>,
    val volPrefixes: List<Any?>,
    val volPrefixesHls: List<Any?>,
    val lastSpeed: Long,
    val vtt: String,
    val activeLimits: Long,
    val dashReport: String,
    val hlsReport: String,
    val volume: Long,
    val inplayer: Inplayer,
    val vast: Vast,
    val netblock: Any?,
    val speedtest: Speedtest,
)

data class Sources(
    val mp4: Mp4,
    val hls: Hls,
)

data class Mp4(
    @JsonProperty("2160p(4K) HD")
    val n2160p4KHd: n2160p4KHd,
    @JsonProperty("1440p(2K) HD")
    val n1440p2KHd: n1440p2KHd,
    @JsonProperty("1080p HD")
    val n1080pHd: n1080pHd,
    @JsonProperty("720p HD")
    val n720pHd: n720pHd,
    @JsonProperty("480p")
    val n480p: n480p,
    @JsonProperty("360p")
    val n360p: n360p,
    @JsonProperty("240p")
    val n240p: n240p,
)

data class n2160p4KHd(
    val labelShort: String,
    val src: String,
    val type: String,
    val default: Boolean,
)

data class n1440p2KHd(
    val labelShort: String,
    val src: String,
    val type: String,
    val default: Boolean,
)

data class n1080pHd(
    val labelShort: String,
    val src: String,
    val type: String,
    val default: Boolean,
)

data class n720pHd(
    val labelShort: String,
    val src: String,
    val type: String,
    val default: Boolean,
)

data class n480p(
    val labelShort: String,
    val src: String,
    val type: String,
    val default: Boolean,
)

data class n360p(
    val labelShort: String,
    val src: String,
    val type: String,
    val default: Boolean,
)

data class n240p(
    val labelShort: String,
    val src: String,
    val type: String,
    val default: Boolean,
)

data class Hls(
    val auto: Auto,
)

data class Auto(
    val src: String,
    val srcFallback: String,
    val type: String,
    val default: Boolean,
)

data class Inplayer(
    val active: Boolean,
    val src: String,
    val width: Long,
    val height: Long,
)

data class Vast(
    val active: Boolean,
    val tag: Any?,
    val timeout: Long,
    val maxSkipOffset: Long,
)

data class Speedtest(
    val speed: Long,
    val avgspeed: Long,
)
