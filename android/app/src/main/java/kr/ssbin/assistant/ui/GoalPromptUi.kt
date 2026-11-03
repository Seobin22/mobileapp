package kr.ssbin.assistant.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kr.ssbin.assistant.chat.Chat
import kr.ssbin.assistant.data.ApiClient
import kr.ssbin.assistant.data.MessageDto
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** 날짜 선택 → 시각 선택. 고르면 ISO-8601(시간대 포함)로 돌려준다. */
fun pickDateTime(context: Context, initial: ZonedDateTime?, onPicked: (String) -> Unit) {
    val start = initial ?: ZonedDateTime.now().plusDays(7).withHour(23).withMinute(0)
    DatePickerDialog(context, { _, y, m, d ->
        TimePickerDialog(context, { _, h, min ->
            val picked = LocalDateTime.of(y, m + 1, d, h, min).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
            onPicked(picked)
        }, start.hour, start.minute, true).show()
    }, start.year, start.monthValue - 1, start.dayOfMonth).show()
}

private fun handled(context: Context, messageId: Long): Boolean =
    context.getSharedPreferences("goal_prompts", Context.MODE_PRIVATE).getBoolean("m$messageId", false)

private fun markHandled(context: Context, messageId: Long) {
    context.getSharedPreferences("goal_prompts", Context.MODE_PRIVATE).edit().putBoolean("m$messageId", true).apply()
}

/**
 * 코드가 보낸 목표 질문(마감 지남·시간 없음·할 일 모두 끝남)의 버튼. 목표 탭과 같은 API 를 바로 부른다.
 * [마감 연장]은 날짜·시각 선택을 바로 연다. 처리한 질문은 버튼을 숨긴다(이 기기 기준).
 */
@Composable
fun GoalPromptButtons(m: MessageDto, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val goalId = m.goal_id ?: return
    var done by remember { mutableStateOf(handled(context, m.id)) }
    var result by remember { mutableStateOf<String?>(null) }
    var confirmCancel by remember { mutableStateOf(false) }
    if (done) {
        Text(result ?: "처리함", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        return
    }

    fun act(label: String, block: suspend () -> Unit) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { block() } }
            r.onSuccess {
                markHandled(context, m.id)
                result = label
                done = true
                onChanged()
            }.onFailure { result = ApiClient.errorMessage(it) }
        }
    }

    val api = ApiClient.get(context)
    fun extend() = pickDateTime(context, null) { iso ->
        act("마감을 바꿨어요") { api.patchGoal(goalId, JsonObject(mapOf("deadline" to JsonPrimitive(iso)))) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (m.kind) {
            "goal_deadline" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { extend() }) { Text("마감 연장") }
                OutlinedButton(onClick = { act("완료했어요") { api.completeGoal(goalId) } }) { Text("완료") }
                OutlinedButton(onClick = { confirmCancel = true }) { Text("취소", color = Color(0xFFC92A2A)) }
            }
            "goal_shortage" -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { extend() }) { Text("마감 늦추기") }
                OutlinedButton(onClick = { markHandled(context, m.id); result = "그대로 둬요"; done = true }) { Text("그대로") }
            }
            "goal_done_check" -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { act("완료했어요") { api.completeGoal(goalId) } }) { Text("완료") }
                OutlinedButton(onClick = {
                    act("비서에게 말했어요") { Chat.send(context, "목표#$goalId 에 할 일이 더 있어", "chat") }
                }) { Text("할 일 더 있어") }
            }
        }
        result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("목표를 취소할까요?") },
            text = { Text("할 일과 남은 계획 블록이 함께 취소돼요.") },
            confirmButton = {
                TextButton(onClick = { confirmCancel = false; act("취소했어요") { api.cancelGoal(goalId) } }) {
                    Text("취소하기", color = Color(0xFFC92A2A))
                }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("아니요") } },
        )
    }
}
