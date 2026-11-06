package kr.ssbin.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import kr.ssbin.assistant.data.ApiClient
import kr.ssbin.assistant.data.Prefs
import kr.ssbin.assistant.data.TimeWindowDto
import kr.ssbin.assistant.data.TimeWindowsRequest
import kr.ssbin.assistant.data.TimeWindowsResponse
import kr.ssbin.assistant.data.WindowPreviewDto
import kr.ssbin.assistant.work.Sync
import java.time.LocalTime
import java.time.OffsetDateTime

private val DND_COLOR = Color(0xFF5C7CFA)
private val ACTIVE_COLOR = Color(0xFFF76707)
private val DAY_NAMES = listOf("월", "화", "수", "목", "금", "토", "일")

/**
 * 방해 금지·적극 개입 시간대(빌드문서 v3.6 §1.4). 두 칸으로 나눠 목록·추가·편집·켜기/끄기, 오늘 타임라인 막대.
 * 저장은 목록 전체를 PUT 한다. 겹치면 서버가 막고 이유를 돌려준다(그대로 보여 준다).
 */
@Composable
fun TimeWindowsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<TimeWindowsResponse?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<TimeWindowDto?>(null) }
    var showLog by remember { mutableStateOf(false) }

    suspend fun load() {
        val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).timeWindows() } }
        r.onSuccess { state = it }.onFailure { message = ApiClient.errorMessage(it) }
    }

    fun save(list: List<TimeWindowDto>, done: () -> Unit = {}) {
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    ApiClient.get(context).putTimeWindows(TimeWindowsRequest(list)).also {
                        Sync.runOnce(context.applicationContext)
                    }
                }
            }
            r.onSuccess { state = it; message = "저장했습니다"; done() }
                .onFailure { message = ApiClient.errorMessage(it) }
        }
    }

    LaunchedEffect(Unit) { load() }

    val all = state?.windows.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("시간대", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        state?.let { TodayBars(it.today) }
        for (kind in listOf("dnd", "active")) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(10.dp).height(10.dp).background(color(kind), RoundedCornerShape(5.dp)))
                        Spacer(Modifier.width(6.dp))
                        Text(if (kind == "dnd") "방해 금지 시간" else "적극 개입 시간", fontWeight = FontWeight.Bold)
                    }
                    Text(
                        if (kind == "dnd") "소리·진동·음성·화면 넘김 없음. 끝나면 모아서 알려요."
                        else "알람 반복, 비서가 먼저 말 걸기, 소리로 말하기 허용.",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                    )
                    val mine = all.filter { it.kind == kind }
                    if (mine.isEmpty()) Text("없음", style = MaterialTheme.typography.bodySmall)
                    mine.forEach { w ->
                        Row(
                            Modifier.fillMaxWidth().clickable { editing = w },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(w.name, fontWeight = FontWeight.SemiBold)
                                Text(describe(w), style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = w.enabled, onCheckedChange = { on ->
                                save(all.map { if (it.id == w.id) it.copy(enabled = on) else it })
                            })
                        }
                    }
                    TextButton(onClick = {
                        editing = TimeWindowDto(
                            kind = kind,
                            name = if (kind == "dnd") "방해 금지" else "아침",
                            days = listOf(1, 2, 3, 4, 5),
                            start_time = if (kind == "dnd") "23:00" else "06:30",
                            end_type = if (kind == "dnd") "time" else "first_block",
                            end_time = if (kind == "dnd") "07:00" else null,
                        )
                    }) { Text("+ 추가") }
                }
            }
        }
        message?.let { Text(it, color = if (it == "저장했습니다") Color.Unspecified else Color(0xFFC92A2A)) }
        TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "막은 알림 기록 닫기" else "막은 알림 기록 보기") }
        if (showLog) {
            val log = Prefs.get(context).guardLog
            Text(if (log.isEmpty()) "없음" else log.take(30).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
        }
    }

    editing?.let { w ->
        TimeWindowDialog(
            initial = w,
            onDismiss = { editing = null },
            onDelete = if (w.id > 0) ({ save(all.filter { it.id != w.id }) { editing = null } }) else null,
            onSave = { edited ->
                val list = if (w.id > 0) all.map { if (it.id == w.id) edited else it } else all + edited
                save(list) { editing = null }
            },
        )
    }
}

private fun color(kind: String) = if (kind == "dnd") DND_COLOR else ACTIVE_COLOR

private fun describe(w: TimeWindowDto): String {
    val days = if (w.days.sorted() == (1..7).toList()) "매일"
    else if (w.days.sorted() == (1..5).toList()) "평일"
    else w.days.sorted().joinToString(",") { DAY_NAMES[it - 1] }
    val end = if (w.end_type == "first_block") "첫 블록(없으면 ${w.end_fallback_time})" else w.end_time.orEmpty()
    return "$days ${w.start_time}–$end" + if (!w.enabled) " · 꺼짐" else ""
}

/** 오늘 타임라인 미리보기(00~24시 막대). first_block 은 계산된 끝까지. */
@Composable
private fun TodayBars(today: List<WindowPreviewDto>) {
    Column {
        Text("오늘", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(22.dp).background(Color(0xFFE9ECEF), RoundedCornerShape(6.dp))
        ) {
            val total = maxWidth
            today.forEach { o ->
                val from = OffsetDateTime.parse(o.from)
                val to = OffsetDateTime.parse(o.to)
                val dayStart = from.toLocalDate().atStartOfDay().atOffset(from.offset)
                val todayDate = OffsetDateTime.now(from.offset).toLocalDate()
                val startMin = if (from.toLocalDate().isBefore(todayDate)) 0L
                else java.time.Duration.between(dayStart, from).toMinutes()
                val endMin = if (to.toLocalDate().isAfter(todayDate)) 24 * 60L
                else java.time.Duration.between(todayDate.atStartOfDay().atOffset(from.offset), to).toMinutes()
                if (endMin > startMin) {
                    Box(
                        Modifier.offset(x = total * (startMin / 1440f))
                            .width(total * ((endMin - startMin) / 1440f))
                            .height(22.dp)
                            .background(color(o.kind), RoundedCornerShape(6.dp))
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0", "6", "12", "18", "24").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = Color.Gray) }
        }
        today.forEach { o ->
            val f = OffsetDateTime.parse(o.from).toLocalTime().toString().take(5)
            val t = OffsetDateTime.parse(o.to).toLocalTime().toString().take(5)
            val tail = if (o.end_type == "first_block") (if (o.fallback) " (오늘 블록 없음)" else " (첫 블록)") else ""
            Text("● ${o.name} $f–$t$tail", style = MaterialTheme.typography.bodySmall, color = color(o.kind))
        }
    }
}

@Composable
private fun TimeWindowDialog(
    initial: TimeWindowDto,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (TimeWindowDto) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var days by remember { mutableStateOf(initial.days.toSet()) }
    var start by remember { mutableStateOf(LocalTime.parse(initial.start_time)) }
    var endType by remember { mutableStateOf(initial.end_type) }
    var end by remember { mutableStateOf(initial.end_time?.let { LocalTime.parse(it) } ?: LocalTime.of(7, 0)) }
    var fallback by remember { mutableStateOf(LocalTime.parse(initial.end_fallback_time)) }
    var open by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.kind == "dnd") "방해 금지 시간" else "적극 개입 시간") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("이름") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                // 요일 7개가 대화 상자 폭에 모두 들어가게 같은 너비의 동그라미로(칩은 토·일이 잘렸다)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DAY_NAMES.forEachIndexed { i, d ->
                        val day = i + 1
                        val on = day in days
                        Box(
                            Modifier.weight(1f).aspectRatio(1f)
                                .background(if (on) Color(0xFFD6336C) else MaterialTheme.colorScheme.surfaceVariant,
                                    androidx.compose.foundation.shape.CircleShape)
                                .clickable { days = if (on) days - day else days + day },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(d, color = if (on) Color.White else Color.Unspecified, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                WheelRow("시작", timeText(start), open == "start") { open = if (open == "start") "" else "start" }
                if (open == "start") TimeWheelPicker(start) { start = it }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = endType == "time", onClick = { endType = "time" }, label = { Text("끝 시각") })
                    FilterChip(selected = endType == "first_block", onClick = { endType = "first_block" },
                        label = { Text("첫 블록까지") })
                }
                if (endType == "time") {
                    val nextDay = !end.isAfter(start)
                    WheelRow("끝", timeText(end) + if (nextDay) " (다음 날)" else "", open == "end") {
                        open = if (open == "end") "" else "end"
                    }
                    if (open == "end") TimeWheelPicker(end) { end = it }
                } else {
                    Text("그날 첫 블록의 첫 알람(준비 시간 포함)에 끝나요. 첫 블록이 시작 시각보다 이르면 그날은 열리지 않아요.",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    WheelRow("블록 없을 때 끝", timeText(fallback), open == "fallback") {
                        open = if (open == "fallback") "" else "fallback"
                    }
                    if (open == "fallback") TimeWheelPicker(fallback) { fallback = it }
                }
                onDelete?.let { del ->
                    OutlinedButton(onClick = del) { Text("삭제", color = Color(0xFFC92A2A)) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = days.isNotEmpty() && (endType != "time" || end != start), onClick = {
                onSave(
                    initial.copy(
                        name = name.trim().ifEmpty { initial.name },
                        days = days.sorted(),
                        start_time = hm(start),
                        end_type = endType,
                        end_time = if (endType == "time") hm(end) else null,
                        end_fallback_time = hm(fallback),
                    )
                )
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

private fun hm(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)

@Composable
private fun WheelRow(label: String, value: String, opened: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (opened) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp))
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}
