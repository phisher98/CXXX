package com.CXXX

import com.fasterxml.jackson.annotation.JsonProperty

data class Posts(
    val posts: List<Post>,
    val count: Long,
)

data class Post(
    @JsonProperty("_id")
    val id: String,
    @JsonProperty("item_id")
    val itemId: Long,
    @JsonProperty("scraping_datetime")
    val scrapingDatetime: String,
    @JsonProperty("video_title")
    val videoTitle: String,
    @JsonProperty("image_details")
    val imageDetails: List<List<String>>,
    @JsonProperty("item_publish_date")
    val itemPublishDate: String,
    val categories: List<String>,
    val actors: List<String>,
    val producer: List<String>,
    val misc: List<Any?>,
    val views: Long,
    val slug: String,
    val dislike: Long?,
    val like: Long?,
)




data class Load(
    val post: LoadPost,
    val urls: List<Url>,
    val relatedPosts: List<RelatedPost>,
)

data class LoadPost(
    @JsonProperty("video_urls")
    val videoUrls: VideoUrls,
    @JsonProperty("_id")
    val id: String,
    @JsonProperty("video_title")
    val videoTitle: String,
    @JsonProperty("video_description")
    val videoDescription: String,
    @JsonProperty("image_details")
    val imageDetails: List<List<String>>,
    @JsonProperty("item_publish_date")
    val itemPublishDate: String,
    val categories: List<String>,
    val actors: List<String>,
    val producer: List<String>,
    val views: Long,
    @JsonProperty("is_alive")
    val isAlive: Boolean,
    val like: Long,
    val dislike: Long,
    val slug: String,
)

data class VideoUrls(
    val direct: List<Any?>,
    val iframe: List<Iframe>,
    val link: List<List<String>>,
)

data class Iframe(
    val url: String,
    @JsonProperty("status_code")
    val statusCode: Long,
    @JsonProperty("status_text")
    val statusText: String,
    @JsonProperty("last_status_change")
    val lastStatusChange: String,
    @JsonProperty("last_status_check")
    val lastStatusCheck: String,
)

data class Url(
    val url: String,
    @JsonProperty("status_code")
    val statusCode: Long,
    @JsonProperty("status_text")
    val statusText: String,
    @JsonProperty("last_status_change")
    val lastStatusChange: String,
    @JsonProperty("last_status_check")
    val lastStatusCheck: String,
)

data class RelatedPost(
    @JsonProperty("_id")
    val id: String,
    @JsonProperty("item_id")
    val itemId: Long,
    @JsonProperty("scraping_datetime")
    val scrapingDatetime: String,
    @JsonProperty("video_title")
    val videoTitle: String,
    @JsonProperty("image_details")
    val imageDetails: List<List<String>>,
    @JsonProperty("item_publish_date")
    val itemPublishDate: String,
    val categories: List<String>,
    val actors: List<String>,
    val producer: List<String>,
    val misc: List<Any?>,
    val views: Long,
    val like: Long?,
    val slug: String,
    val dislike: Long?,
)
