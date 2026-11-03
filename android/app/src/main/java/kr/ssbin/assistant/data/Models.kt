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
    /** v3.1: 개입 시각(= start − lead). 없으면(구 서버) start. */
    val alarm_at: String? = null,
    val kind: String = "planned",
    val goal_label: String = "",
    val goal_color: String = "",
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
    /** v3.1 풀스크린 대화(source=fullscreen|fullscreen_voice). */
    val intervention_id: Long? = null,
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
    /** v3.1: 시작 화면(풀스크린)에서 오간 말. */
    val intervention_id: Long? = null,
    val source: String? = null,
    /** 목표 질문(goal_deadline·goal_shortage·goal_done_check)의 목표. */
    val goal_id: Long? = null,
    /** v3.3: 채팅으로 올린 자료(파일 카드). */
    val document_id: Long? = null,
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

/** kind: fixed_block | planned_block | quiet | meal | free (v3.1). */
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
    val goal_id: Long? = null,
    val goal_label: String? = null,
    val goal_color: String? = null,
    val lead_minutes: Int = 0,
    val buffer_after_min: Int = 0,
    val location: String? = null,
    /** lead·buffer 를 누가 정했나(user | assistant | null=아직, 비서가 정함). */
    val prep_set_by: String? = null,
)

// ------------------------------------------------------------------ v3.1 목표·고정 블록

@Serializable
data class GoalProgress(val unit: String? = null, val total: Double? = null, val done: Double? = null)

@Serializable
data class GoalDto(
    val id: Long,
    val title: String,
    val label: String = "",
    val color: String = "",
    val deadline: String? = null,
    val status: String,
    val priority: Int = 3,
    val progress: GoalProgress? = null,
    val strategy_summary: String = "",
    val waiting_until: String? = null,
    val waiting_reason: String? = null,
    val load_status: String? = null,
    val next_block_start: String? = null,
    val upcoming_block_count: Int = 0,
    val coverage: String? = null,
)

@Serializable
data class GoalsResponse(val goals: List<GoalDto>)

@Serializable
data class GoalBlockDto(
    val id: Long,
    val kind: String,
    val title: String,
    val start: String,
    val end: String,
    val status: String,
)

@Serializable
data class GoalBlocksResponse(val goal: GoalDto, val blocks: List<GoalBlockDto>)

@Serializable
data class FixedBlockRequest(
    val title: String? = null,
    val start: String? = null,
    val duration_min: Int? = null,
    val lead_minutes: Int? = null,
    val location: String? = null,
    val goal_id: Long? = null,
    val first_action: String? = null,
    /** v3.2 §3: 사용자 메모(단발 일정, 또는 반복의 '이번만'). */
    val memo: String? = null,
    /** v3.3 §2: 진행 중 일정은 시작 대신 끝 시각만 보낸다. */
    val end: String? = null,
    /** v3.3 §1: dnd | quiet | allow, "" = 비서가 정함 */
    val interrupt_level: String? = null,
)

// ------------------------------------------------------------------ v3.3 이동 시간·자료

@Serializable
data class TravelLegDto(
    val direction: String,
    val from: String,
    val to: String,
    val mode: String,
    val minutes: Int,
    val estimate: Boolean = false,
)

@Serializable
data class TravelStatusDto(
    val status: String = "",
    val status_text: String = "",
    val calls_today: Int = 0,
    val daily_limit: Int = 0,
    val home_address: String = "",
    val home_found: Boolean = false,
    val mode: String = "transit",
    val found: Boolean? = null,
    val found_address: String? = null,
)

@Serializable
data class DocumentDto(
    val id: Long,
    val goal_id: Long? = null,
    val filename: String,
    val mime: String = "",
    val size_bytes: Long = 0,
    val status: String,
    val error: String? = null,
    val pages: Int? = null,
    val uploaded_at: String? = null,
    val summary_md: String = "",
    val extracted: JsonElement? = null,
)

@Serializable
data class DocumentsResponse(val documents: List<DocumentDto>)

// ------------------------------------------------------------------ v3.2 달력·마감·블록 기록·반복 일정

@Serializable
data class LinkedPrep(val id: Long, val start: String, val title: String, val status: String)

@Serializable
data class CalendarBlock(
    val id: Long,
    val kind: String,
    val start: String,
    val end: String,
    val status: String,
    val actual_start: String? = null,
    val actual_end: String? = null,
    val title: String = "",
    val task_id: Long? = null,
    val goal_id: Long? = null,
    val goal_label: String = "",
    val goal_color: String = "",
    val series_id: Long? = null,
    val lead_minutes: Int = 0,
    val location: String? = null,
    val memo: String = "",
    val linked_prep: List<LinkedPrep> = emptyList(),
    val deadline: String? = null,
    val linked_fixed_block_id: Long? = null,
    val linked_title: String? = null,
    val interrupt_level: String? = null,
    val interrupt_set_by: String? = null,
    val place_name: String? = null,
    val in_progress: Boolean = false,
    val travel: List<TravelLegDto> = emptyList(),
)

@Serializable
data class DeadlineDto(
    val task_id: Long? = null,
    val goal_id: Long? = null,
    val title: String,
    val at: String,
    val blocks_before: Int = 0,
    val passed: Boolean = false,
)

@Serializable
data class CalendarResponse(val blocks: List<CalendarBlock>, val deadlines: List<DeadlineDto> = emptyList())

@Serializable
data class TaskBlockDto(val id: Long, val start: String, val end: String, val status: String)

@Serializable
data class TaskDetailDto(
    val id: Long,
    val title: String,
    val status: String,
    val deadline: String? = null,
    val remaining_minutes: Int = 0,
    val left_text: String? = null,
    val blocks: List<TaskBlockDto> = emptyList(),
)

@Serializable
data class BlockLogDto(
    val id: Long,
    val at: String,
    val type: String,
    val actor: String,
    val content: String,
)

@Serializable
data class SeriesDto(
    val id: Long,
    val title: String,
    val freq: String,
    val interval: Int = 1,
    val by_days: List<Int> = emptyList(),
    val month_day: Int? = null,
    val start_time: String,
    val duration_min: Int,
    val start_date: String,
    val until_date: String? = null,
    val lead_minutes: Int = 0,
    val location: String? = null,
    val goal_id: Long? = null,
    val first_action: String? = null,
    val memo: String = "",
    val rule_text: String = "",
    val interrupt_level: String? = null,
    val place_name: String? = null,
)

@Serializable
data class BlockDetailDto(
    val id: Long,
    val kind: String,
    val start: String,
    val end: String,
    val status: String,
    val title: String = "",
    val task_id: Long? = null,
    val goal_label: String = "",
    val goal_color: String = "",
    val series_id: Long? = null,
    val lead_minutes: Int = 0,
    val location: String? = null,
    val memo: String = "",
    val once_memo: String = "",
    val linked_prep: List<LinkedPrep> = emptyList(),
    val deadline: String? = null,
    val linked_fixed_block_id: Long? = null,
    val linked_title: String? = null,
    val series: SeriesDto? = null,
    val occurrence_date: String? = null,
    val logs: List<BlockLogDto> = emptyList(),
    val interrupt_level: String? = null,
    val interrupt_set_by: String? = null,
    val place_name: String? = null,
    val place_source: String? = null,
    val in_progress: Boolean = false,
    val travel: List<TravelLegDto> = emptyList(),
)

@Serializable
data class TurnStatusDto(val stage: String, val stage_text: String = "", val elapsed_sec: Long = 0)

@Serializable
data class SeriesRequest(
    val title: String? = null,
    val freq: String? = null,
    val interval: Int? = null,
    val by_days: List<Int>? = null,
    val month_day: Int? = null,
    val start_time: String? = null,
    val duration_min: Int? = null,
    val start_date: String? = null,
    val until_date: String? = null,
    val lead_minutes: Int? = null,
    val location: String? = null,
    val goal_id: Long? = null,
    val first_action: String? = null,
    val memo: String? = null,
    val interrupt_level: String? = null,
)

@Serializable
data class AppLatestDto(
    val available: Boolean = false,
    val version_code: Long = 0,
    val version_name: String = "",
    val size: Long = 0,
)
