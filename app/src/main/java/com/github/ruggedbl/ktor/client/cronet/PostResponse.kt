package com.github.ruggedbl.ktor.client.cronet

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class PostResponse(
    @SerialName("title")
    val title: String,
    @SerialName("body")
    val body: String,
    @SerialName("userId")
    val userId: Int,
    @SerialName("id")
    val id: Int
)