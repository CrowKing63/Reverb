package com.reverb.model

import kotlinx.serialization.Serializable

@Serializable
data class SnapshotMessage(
    val type: String = "snapshot",
    val deviceName: String,
    val batteryLevel: Int,
    val batteryCharging: Boolean,
    val notifications: List<NotificationPayload>
)

@Serializable
data class StatusMessage(
    val type: String = "status",
    val deviceName: String,
    val batteryLevel: Int,
    val batteryCharging: Boolean
)

@Serializable
data class FilterConfig(
    val mode: String = "blacklist",   // "whitelist" | "blacklist"
    val packages: List<String> = emptyList()
)
