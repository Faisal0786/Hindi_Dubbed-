package com.hindi

import com.fasterxml.jackson.annotation.JsonProperty

data class AniZipResponse(

    @JsonProperty("episodes")
    val episodes: Map<String, AniZipEpisode> = emptyMap(),

    @JsonProperty("images")
    val images: List<AniZipImage> = emptyList()
)

data class AniZipEpisode(

    @JsonProperty("title")
    val title: Map<String, String>? = null,

    @JsonProperty("overview")
    val overview: String? = null,

    @JsonProperty("image")
    val image: String? = null,

    @JsonProperty("runtime")
    val runtime: Int? = null,

    @JsonProperty("rating")
    val rating: String? = null
)

data class AniZipImage(

    @JsonProperty("coverType")
    val coverType: String? = null,

    @JsonProperty("url")
    val url: String? = null
)