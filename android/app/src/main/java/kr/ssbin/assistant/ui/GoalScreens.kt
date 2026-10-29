package kr.ssbin.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kr.ssbin.assistant.chat.Chat
import kr.ssbin.assistant.data.ApiClient
import kr.ssbin.assistant.data.FixedBlockRequest
import kr.ssbin.assistant.data.GoalBlocksResponse
import kr.ssbin.assistant.data.GoalDto
import kr.ssbin.assistant.data.TimelineItem
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ERR = Color(0xFFC92A2A)

/** 서버가 정한 목표 색(#RRGGBB). 없거나 형식이 틀리면 회색. */
fun goalColor(hex: String?): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex!!.trim()))
}.getOrDefault(Color(0xFF868E96))

/** 서버 팔레트(GoalCoverage.PALETTE)와 같은 8색. 이름표·색 변경에서 고른다. */
private val PALETTE = listOf("#E64980", "#4C6EF5", "#12B886", "#FAB005", "#7950F2", "#FD7E14", "#15AABF", "#82C91E")

@Composable
fun GoalChip(label: String?, color: String?) {
    if (label.isNullOrBlank()) return
    Text(
        label,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.background(goalColor(color), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private fun localText(iso: String?, pattern: String): String = iso?.let {
    runCatching {
        OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(pattern))
    }.getOrNull()
} ?: ""

private fun loadLabel(status: String?): Pair<String, Color>? = when (status) {
    "ok" -> "여유" to Color(0xFF2B8A3E)
    "tight" -> "빠듯" to Color(0xFFE67700)
    "short" -> "부족" to ERR
    else -> null
}

// ------------------------------------------------------------------ 고정 블록 만들기·수정 (§4.1)

/** [+] 추가 화면 = 고정 블록 만들기. initial 이 있으면 그 고정 블록 수정. 저장은 사용자 행동이라 바로 반영된다. */
@Composable
fun FixedBlockDialog(initial: TimelineItem?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val startAt = initial?.let { OffsetDateTime.parse(it.start).atZoneSameInstant(ZoneId.systemDefault()) }
    val defaultStart = LocalDateTime.now().plusMinutes(30).withSecond(0).withNano(0)
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var date by remember { mutableStateOf((startAt?.toLocalDate() ?: defaultStart.toLocalDate()).toString()) }
    var time by remember {
        mutableStateOf((startAt?.toLocalTime() ?: defaultStart.toLocalTime()).format(DateTimeFormatter.ofPattern("HH:mm")))
    }
    var duration by remember {
        mutableStateOf((initial?.let {
            java.time.Duration.between(OffsetDateTime.parse(it.start), OffsetDateTime.parse(it.end)).toMinutes()
        } ?: 60).toString())
    }
    // 준비 시간은 비워 두면 비서가 장소·일의 성격을 보고 정한다(v3.1 §3.2.1).
    var lead by remember { mutableStateOf(initial?.let { if (it.prep_set_by == null) "" else it.lead_minutes.toString() } ?: "") }
    var location by remember { mutableStateOf(initial?.location ?: "") }
    var firstAction by remember { mutableStateOf("") }
    var goalId by remember { mutableStateOf(initial?.goal_id) }
    var goals by remember { mutableStateOf<List<GoalDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        goals = withContext(Dispatchers.IO) {
            runCatching { ApiClient.get(context).goals().goals.filter { it.status == "active" } }.getOrDefault(emptyList())
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "고정 블록 추가" else "고정 블록 수정") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("제목 (예: 치과, 컴활 공부)") })
                OutlinedTextField(date, { date = it }, label = { Text("날짜 (YYYY-MM-DD)") })
                OutlinedTextField(time, { time = it }, label = { Text("시작 (HH:MM)") })
                OutlinedTextField(duration, { duration = it }, label = { Text("길이(분)") })
                OutlinedTextField(lead, { lead = it }, label = { Text("출발·준비 시간(분, 선택)") }, placeholder = { Text("비워 두면 비서가 정해요") })
                OutlinedTextField(location, { location = it }, label = { Text("장소(선택)") })
                if (initial == null) {
                    OutlinedTextField(firstAction, { firstAction = it }, label = { Text("첫 행동(선택)") })
                }
                if (goals.isNotEmpty()) {
                    Text("목표 연결(선택)", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth().clickable { goalId = null }.padding(4.dp)) {
                        Text(if (goalId == null) "● 없음" else "○ 없음")
                    }
                    goals.forEach { g ->
                        Row(
                            Modifier.fillMaxWidth().clickable { goalId = g.id }.padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (goalId == g.id) "● " else "○ ")
                            GoalChip(g.label, g.color)
                            Spacer(Modifier.width(6.dp))
                            Text(g.title)
                        }
                    }
                }
                error?.let { Text(it, color = ERR) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val start = runCatching {
                    LocalDateTime.of(LocalDate.parse(date.trim()), LocalTime.parse(time.trim()))
                        .atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
                }.getOrNull()
                val minutes = duration.trim().toIntOrNull()
                val leadText = lead.trim()
                val leadMinutes = if (leadText.isEmpty()) null else leadText.toIntOrNull()
                if (title.isBlank() || start == null || minutes == null || (leadText.isNotEmpty() && leadMinutes == null)) {
                    error = "제목·날짜·시각·길이·출발 준비 형식을 확인하세요"
                    return@TextButton
                }
                val body = FixedBlockRequest(
                    title = title.trim(), start = start, duration_min = minutes, lead_minutes = leadMinutes,
                    location = location.trim().ifEmpty { null }, goal_id = goalId,
                    first_action = firstAction.trim().ifEmpty { null },
                )
                scope.launch {
                    val api = ApiClient.get(context)
                    val r = withContext(Dispatchers.IO) {
                        runCatching {
                            if (initial?.id == null) api.createFixedBlock(body) else api.patchFixedBlock(initial.id, body)
                        }
                    }
                    // 고정 블록끼리 겹치면 서버 사유("○○와 겹쳐요…")를 그대로 보여준다.
                    r.onSuccess { onSaved() }.onFailure { error = ApiClient.errorMessage(it) }
                }
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** 계획 블록은 비서가 관리한다. 길게 누르면 비서에게 바꿔 달라고 말할 수 있다(§4.1). */
@Composable
fun PlannedBlockDialog(item: TimelineItem, onDismiss: () -> Unit, onSent: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var request by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val title = item.title.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("비서가 잡은 계획 블록이에요. 바꾸고 싶은 걸 말하면 비서가 다시 배치하고 알려줘요.")
                OutlinedTextField(request, { request = it }, label = { Text("예: 저녁으로 옮겨줘 / 오늘은 빼줘") })
                error?.let { Text(it, color = ERR) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val text = "블록#${item.id}(${time(item.start)} $title): " + request.trim().ifEmpty { "다른 시간으로 옮겨줘" }
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { Chat.send(context, text, "chat") } }
                    r.onSuccess { onSent() }.onFailure { error = ApiClient.errorMessage(it) }
                }
            }) { Text("비서에게 보내기") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

private fun time(iso: String): String = localText(iso, "M/d HH:mm")

// ------------------------------------------------------------------ 목표 탭 (§4.2)

@Composable
fun GoalsScreen(tick: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var goals by remember { mutableStateOf<List<GoalDto>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<GoalDto?>(null) }

    LaunchedEffect(Unit) { Chat.refresh.collect { reload++ } }
    LaunchedEffect(tick, reload) {
        val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).goals().goals } }
        r.onSuccess { goals = it; error = null }.onFailure { error = ApiClient.errorMessage(it) }
        loaded = true
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp)) { TextButton(onClick = { reload++ }) { Text("새로고침") } }
        error?.let { Text(it, color = ERR, modifier = Modifier.padding(12.dp)) }
        if (loaded && goals.isEmpty() && error == null) {
            Text("채팅에 목표를 말하면 비서가 계획을 세워요.", modifier = Modifier.padding(20.dp))
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            items(goals, key = { it.id }) { g -> GoalCard(g, onClick = { detail = g }) }
        }
    }
    detail?.let { g ->
        GoalDetailDialog(g, onDismiss = { detail = null }, onChanged = { detail = null; reload++ })
    }
}

@Composable
private fun GoalCard(g: GoalDto, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable(onClick = onClick)) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(goalColor(g.color)))
            Column(Modifier.padding(12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GoalChip(g.label, g.color)
                    Spacer(Modifier.width(6.dp))
                    Text(g.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (g.status == "active") {
                        loadLabel(g.load_status)?.let { (text, color) ->
                            Text(text, color = Color.White, style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.background(color, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                    } else {
                        Text(if (g.status == "done") "완료" else "취소", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text("마감 " + (localText(g.deadline, "M/d(E)").ifEmpty { "없음" }), style = MaterialTheme.typography.bodySmall)
                g.progress?.let { p ->
                    val total = p.total ?: 0.0
                    if (total > 0) {
                        val done = p.done ?: 0.0
                        LinearProgressIndicator(
                            progress = { (done / total).toFloat().coerceIn(0f, 1f) },
                            color = goalColor(g.color),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${p.unit.orEmpty()} ${fmt(done)}/${fmt(total)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (g.waiting_until != null) {
                    Text("대기 중 · ${localText(g.waiting_until, "M/d")} 확인" +
                        (g.waiting_reason?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.bodySmall)
                } else if (g.status == "active") {
                    Text(
                        "다음 블록 " + (localText(g.next_block_start, "M/d(E) HH:mm").ifEmpty { "없음" }),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (g.coverage == "missing") ERR else Color.Unspecified,
                    )
                }
            }
        }
    }
}

private fun fmt(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

private enum class GoalEdit { NONE, DEADLINE, PRIORITY, LABEL, COMPLETE, CANCEL }

@Composable
private fun GoalDetailDialog(goal: GoalDto, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<GoalBlocksResponse?>(null) }
    var edit by remember { mutableStateOf(GoalEdit.NONE) }
    var error by remember { mutableStateOf<String?>(null) }
    val active = goal.status == "active"

    LaunchedEffect(goal.id) {
        val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).goalBlocks(goal.id) } }
        r.onSuccess { data = it }.onFailure { error = ApiClient.errorMessage(it) }
    }

    fun act(call: suspend () -> Unit) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { call() } }
            r.onSuccess { edit = GoalEdit.NONE; onChanged() }.onFailure { error = ApiClient.errorMessage(it) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GoalChip(goal.label, goal.color)
                Spacer(Modifier.width(6.dp))
                Text(goal.title)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(goal.strategy_summary.ifBlank { "전략 정리 중" })
                Text("마감 ${localText(goal.deadline, "M/d(E) HH:mm").ifEmpty { "없음" }} · 우선순위 ${goal.priority}",
                    style = MaterialTheme.typography.bodySmall)
                Text("블록", fontWeight = FontWeight.Bold)
                val blocks = data?.blocks
                if (blocks == null) Text("불러오는 중…") else if (blocks.isEmpty()) Text("아직 블록이 없어요")
                blocks?.forEach { b ->
                    Text("${time(b.start)} ${if (b.kind == "fixed") "[고정] " else ""}${b.title} · ${resultLabel(b.status)}",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (active) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { edit = GoalEdit.DEADLINE }) { Text("마감 변경") }
                        OutlinedButton(onClick = { edit = GoalEdit.PRIORITY }) { Text("우선순위") }
                    }
                    OutlinedButton(onClick = { edit = GoalEdit.LABEL }) { Text("이름표·색 변경") }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { edit = GoalEdit.COMPLETE }) { Text("완료") }
                        OutlinedButton(onClick = { edit = GoalEdit.CANCEL }) { Text("취소", color = ERR) }
                    }
                }
                error?.let { Text(it, color = ERR) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )

    val api = ApiClient.get(context)
    when (edit) {
        GoalEdit.DEADLINE -> {
            var value by remember { mutableStateOf(localText(goal.deadline, "yyyy-MM-dd")) }
            AlertDialog(
                onDismissRequest = { edit = GoalEdit.NONE },
                title = { Text("마감 변경") },
                text = { OutlinedTextField(value, { value = it }, label = { Text("YYYY-MM-DD (비우면 마감 없음)") }) },
                confirmButton = {
                    TextButton(onClick = {
                        val deadline = if (value.isBlank()) JsonNull else runCatching {
                            JsonPrimitive(LocalDate.parse(value.trim()).atTime(23, 59)
                                .atZone(ZoneId.systemDefault()).toOffsetDateTime().toString())
                        }.getOrNull()
                        if (deadline == null) { error = "날짜 형식을 확인하세요"; edit = GoalEdit.NONE; return@TextButton }
                        act { api.patchGoal(goal.id, JsonObject(mapOf("deadline" to deadline))) }
                    }) { Text("저장") }
                },
                dismissButton = { TextButton(onClick = { edit = GoalEdit.NONE }) { Text("취소") } },
            )
        }
        GoalEdit.PRIORITY -> AlertDialog(
            onDismissRequest = { edit = GoalEdit.NONE },
            title = { Text("우선순위 (1이 가장 높음)") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..5).forEach { p ->
                        OutlinedButton(onClick = { act { api.patchGoal(goal.id, JsonObject(mapOf("priority" to JsonPrimitive(p)))) } }) {
                            Text(if (p == goal.priority) "[$p]" else "$p")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { edit = GoalEdit.NONE }) { Text("닫기") } },
        )
        GoalEdit.LABEL -> {
            var label by remember { mutableStateOf(goal.label) }
            var color by remember { mutableStateOf(goal.color) }
            AlertDialog(
                onDismissRequest = { edit = GoalEdit.NONE },
                title = { Text("이름표·색") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(label, { label = it.take(6) }, label = { Text("이름표 (6자 이내)") })
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PALETTE.forEach { c ->
                                Box(
                                    Modifier.size(if (c.equals(color, ignoreCase = true)) 32.dp else 26.dp)
                                        .background(goalColor(c), CircleShape)
                                        .clickable { color = c }
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        act {
                            api.patchGoal(goal.id, JsonObject(mapOf("label" to JsonPrimitive(label), "color" to JsonPrimitive(color))))
                        }
                    }) { Text("저장") }
                },
                dismissButton = { TextButton(onClick = { edit = GoalEdit.NONE }) { Text("취소") } },
            )
        }
        GoalEdit.COMPLETE, GoalEdit.CANCEL -> {
            val complete = edit == GoalEdit.COMPLETE
            AlertDialog(
                onDismissRequest = { edit = GoalEdit.NONE },
                title = { Text(if (complete) "목표를 완료할까요?" else "목표를 취소할까요?") },
                text = {
                    Text(
                        if (complete) "남은 할 일과 계획 블록이 정리돼요. 직접 잡은 고정 블록은 그대로 남아요."
                        else "계획 블록은 지우고, 직접 잡은 고정 블록은 목표 연결만 풀고 남겨요."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        act { if (complete) api.completeGoal(goal.id) else api.cancelGoal(goal.id) }
                    }) { Text(if (complete) "완료" else "취소하기", color = if (complete) Color.Unspecified else ERR) }
                },
                dismissButton = { TextButton(onClick = { edit = GoalEdit.NONE }) { Text("아니요") } },
            )
        }
        GoalEdit.NONE -> {}
    }
}

private fun resultLabel(status: String): String = when (status) {
    "done" -> "완료"
    "partial" -> "부분"
    "missed" -> "놓침"
    "started" -> "진행 중"
    "nudging" -> "화면 표시 중"
    "planned" -> "예정"
    else -> status
}
