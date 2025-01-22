package com.anhdaden

import com.fasterxml.jackson.annotation.JsonProperty

data class Response(
    @JsonProperty("result") val result: Result,
)

data class Result(
    @JsonProperty("docs") val docs: ArrayList<Doc>? = arrayListOf(),
)

data class Doc(
    @JsonProperty("videoId") val videoId: String,
    @JsonProperty("title_en") val title_en: String,
    @JsonProperty("preview_hp") val preview_hp: String,
)