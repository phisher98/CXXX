package com.CXXX

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

// ── Home page (JSON-LD ItemList) ────────────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class HomePosts(
    @JsonProperty("@context") val context: String? = null,
    @JsonProperty("@type")    val type: String? = null,
    val itemListElement: List<ItemListElement> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ItemListElement(
    @JsonProperty("@context") val context: String? = null,
    @JsonProperty("@type")    val type: String? = null,
    val position: Long? = null,
    val name: String = "",
    val url: String = "",
    val description: String? = null,
    val thumbnailUrl: List<String> = emptyList(),
    val uploadDate: String? = null,
    val interactionStatistic: InteractionStatistic? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class InteractionStatistic(
    @JsonProperty("@type") val type: String? = null,
    val interactionType: InteractionType? = null,
    val userInteractionCount: Long? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class InteractionType(
    @JsonProperty("@type") val type: String? = null,
)