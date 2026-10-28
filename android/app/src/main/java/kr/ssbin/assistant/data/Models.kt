package kr.ssbin.assistant.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
data class RegisterRequest(
    val fcm_token: String,
    val app_version: String,
    val capabilities: Map<String, Boolean>,
)

@Serializable
data class RegisterResponse(val device_id: Long)

@Serializable
data class HeartbeatRequest(
    val device_id: Long,
    val capabilities: Map<String, Boolean>,
    val events: List<JsonObject> = emptyList(),
)

@Serializable
data class TasksResponse(val tasks: List<TaskDto>)

@Serializable
data class TaskDto(
    val id: Long,
    val title: String,
    val first_action: String,
    val est_minutes: Int,
    val status: String,
    val blocks: List<BlockDto>,
)

@Serializable
data class BlockDto(
    val id: Long,
    val start: String,
    val end: String,
    val status: String,
    val screen_text: String,
)

@Serializable
data class CreateTaskRequest(
    val title: String,
    val first_action: String,
    val start: String,
    val duration_min: Int,
)

@Serializable
data class SettingsResponse(val settings: Map<String, JsonElement>)

/** plan.sync 의 upcoming 항목. */
@Serializable
data class UpcomingItem(
    val block_id: Long,
    val start: String,
    val end: String,
    val task_title: String,
    val screen_text: String,
)

/** plan.sync 의 quiet_windows 항목. */
@Serializable
data class QuietWindow(val from: String, val to: String)
