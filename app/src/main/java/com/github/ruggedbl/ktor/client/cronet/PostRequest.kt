package com.github.ruggedbl.ktor.client.cronet

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class PostRequest(
    @SerialName("title")
    val title: String,
    @SerialName("body")
    val body: String,
    @SerialName("userId")
    val userId: Int
)