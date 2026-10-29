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
    val work_product_id: Long? = null,
)

/** plan.sync 의 quiet_windows 항목. */
@Serializable
data class QuietWindow(val from: String, val to: String)

// ------------------------------------------------------------------ v2 채팅·시간표

@Serializable
data class PostMessageRequest(
    val client_msg_id: String,
    val text: String,
    val source: String,
    val answered_question_id: Long? = null,
)

@Serializable
data class PostMessageResponse(val message_id: Long, val turn_id: Long? = null)

@Serializable
data class MessagesResponse(val messages: List<MessageDto>)

@Serializable
data class MessageDto(
    val id: Long,
    val role: String,
    val content: String,
    val kind: String,
    val options: List<String>? = null,
    val question_id: Long? = null,
    val question_status: String? = null,
    val confirmation_id: Long? = null,
    val confirmation_status: String? = null,
    val read: Boolean = false,
    val silent: Boolean = false,
    val created_at: String,
)

// ------------------------------------------------------------------ v3 작업물

@Serializable
data class SourceDto(val title: String? = null, val url: String)

@Serializable
data class WorkProductDto(
    val id: Long,
    val task_id: Long,
    val title: String,
    val kind: String,
    val content_md: String,
    val sources: List<SourceDto> = emptyList(),
)

@Serializable
data class ConfirmRequest(val accept: Boolean)

@Serializable
data class TimelineResponse(val days: List<TimelineDay>)

@Serializable
data class TimelineDay(val date: String, val wake: String, val sleep: String, val items: List<TimelineItem>)

@Serializable
data class TimelineItem(
    val kind: String,
    val start: String,
    val end: String,
    val id: Long? = null,
    val title: String? = null,
    val status: String? = null,
    val task_id: Long? = null,
    val work_product_id: Long? = null,
)
