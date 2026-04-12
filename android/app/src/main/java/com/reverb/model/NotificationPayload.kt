package com.reverb.model

import kotlinx.serialization.Serializable

@Serializable
data class NotificationPayload(
    val type: String = "notification",
    val id: String,
    val timestamp: Long,
    val packageName: String,
    val appLabel: String,
    val category: String,       // "sms", "call", "media", "generic"
    val title: String? = null,
    val body: String? = null,
    val ticker: String? = null,
    val conversationId: String? = null,  // 전화번호 or 스레드 ID (답장 라우팅용)
    val actions: List<String> = emptyList(),
    val isGroupSummary: Boolean = false,
    val priority: Int = 0,
    val sbnKey: String = ""     // Phase 2 dismiss 동기화용
)
