package com.reverb.model

import kotlinx.serialization.Serializable

@Serializable
data class ReplyRequest(
    val conversationId: String,
    val packageName: String,
    val replyBody: String
)

@Serializable
data class ReplyResponse(
    val success: Boolean,
    val error: String? = null
)
