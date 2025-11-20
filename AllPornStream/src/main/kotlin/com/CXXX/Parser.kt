package com.CXXX

import com.fasterxml.jackson.annotation.JsonProperty

data class Posts(
    val posts: List<PostMain>,
    val count: Long,
    val nextCursor: Any?,
)

data class PostMain(
    val id: String,
    @JsonProperty("mongodb_id")
    val mongodbId: String?,
    @JsonProperty("scraping_datetime")
    val scrapingDatetime: String,
    @JsonProperty("item_publish_date")
    val itemPublishDate: String,
    @JsonProperty("video_title")
    val videoTitle: String,
    @JsonProperty("item_id")
    val itemId: String,
    val producers: List<String>,
    val actors: List<String>,
    val categories: List<String>,
    val misc: Any?,
    @JsonProperty("image_details")
    val imageDetails: List<String>,
    val views: Long,
    val like: Long,
    val dislike: Long,
    @JsonProperty("video_urls")
    val videoUrls: Any?,
    val slug: String,
)


data class Load(
    val post: Post,
    val urls: List<Url>,
    val relatedPosts: List<RelatedPost>,
)

data class Post(
    val id: String,
    @JsonProperty("mongodb_id")
    val mongodbId: Any?,
    val actors: List<String>,
    val categories: List<String>,
    @JsonProperty("image_details")
    val imageDetails: List<String>,
    @JsonProperty("item_publish_date")
    val itemPublishDate: String,
    val producers: List<String>,
    @JsonProperty("video_description")
    val videoDescription: String,
    @JsonProperty("video_title")
    val videoTitle: String,
    @JsonProperty("video_urls")
    val videoUrls: VideoUrls,
    val views: Long,
    val like: Long,
    @JsonProperty("is_alive")
    val isAlive: Boolean,
    @JsonProperty("scraping_datetime")
    val scrapingDatetime: String,
    val slug: String,
)

data class VideoUrls(
    val link: List<List<String>>,
    val iframe: List<Iframe>,
)

data class Iframe(
    val url: String,
    @JsonProperty("status_code")
    val statusCode: Long,
    @JsonProperty("status_text")
    val statusText: String,
    @JsonProperty("last_status_check")
    val lastStatusCheck: String,
    @JsonProperty("last_status_change")
    val lastStatusChange: String,
)

data class Url(
    val url: String,
    @JsonProperty("status_code")
    val statusCode: Long,
    @JsonProperty("status_text")
    val statusText: String,
    @JsonProperty("last_status_check")
    val lastStatusCheck: String,
    @JsonProperty("last_status_change")
    val lastStatusChange: String,
)

data class RelatedPost(
    val id: String,
    @JsonProperty("mongodb_id")
    val mongodbId: String?,
    @JsonProperty("scraping_datetime")
    val scrapingDatetime: String,
    @JsonProperty("item_publish_date")
    val itemPublishDate: String,
    @JsonProperty("video_title")
    val videoTitle: String,
    @JsonProperty("item_id")
    val itemId: String,
    val producers: List<String>,
    val actors: List<String>,
    val categories: List<String>,
    val misc: List<Any?>,
    @JsonProperty("image_details")
    val imageDetails: List<String>,
    val views: Long,
    val like: Long,
    val dislike: Long,
    val slug: String,
)

