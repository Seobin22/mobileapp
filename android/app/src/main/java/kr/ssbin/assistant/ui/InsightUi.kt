package kr.ssbin.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kr.ssbin.assistant.chat.Chat
import kr.ssbin.assistant.data.ApiClient
import kr.ssbin.assistant.data.CardBlockDto
import kr.ssbin.assistant.data.CardDto
import kr.ssbin.assistant.data.CardFieldDto
import kr.ssbin.assistant.data.FactDto
import kr.ssbin.assistant.data.MemoryItemDto
import kr.ssbin.assistant.data.PatternSummaryDto
import kr.ssbin.assistant.data.ProfileSectionDto
import kr.ssbin.assistant.data.StatDto
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// v3.5 §10: 정리 카드·아침 카드(채팅 안), 분류 칩(일정 입력), "비서가 아는 나" 화면.

private val INSIGHT_PINK = Color(0xFFD6336C)
private val FAINT = 0.45f

/** 분류(§1). 이동(travel)은 시스템 전용이라 고르지 않는다. */
val CATEGORY_OPTIONS = listOf(
    "study" to "공부", "work" to "일", "class" to "수업", "exercise" to "운동", "chores" to "집안일·잡무",
    "selfcare" to "씻기·식사·준비", "rest" to "휴식", "social" to "약속", "other" to "기타",
)

fun categoryLabel(c: String?): String = CATEGORY_OPTIONS.firstOrNull { it.first == c }?.second ?: (c ?: "")

private fun CardFieldDto.str(): String? = (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
private fun CardFieldDto.int(): Int? = (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull
private fun CardFieldDto.list(): List<String> = (value as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

/** 작은 알약 버튼 묶음. faint 면 추정값(연하게). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Pills(options: List<Pair<String, String>>, selected: Set<String>, faint: Boolean = false, onClick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (key, label) ->
            val on = key in selected
            Text(
                label,
                color = if (on) Color.White else INSIGHT_PINK,
                fontSize = 13.sp,
                modifier = Modifier
                    .alpha(if (on && faint) FAINT else 1f)
                    .background(if (on) INSIGHT_PINK else Color(0xFFFFF0F5), RoundedCornerShape(50))
                    .clickable { onClick(key) }
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            )
        }
    }
}

/** 일정 입력 화면의 분류 칩(선택). 다시 누르면 선택을 지운다. */
@Composable
fun CategoryChips(selected: String, onSelect: (String) -> Unit) {
    Text("분류(선택)", fontWeight = FontWeight.Bold)
    Pills(CATEGORY_OPTIONS, setOf(selected)) { key -> onSelect(if (key == selected) "" else key) }
}

private class Entry(b: CardBlockDto) {
    var outcome by mutableStateOf(b.outcome.str())
    var focus by mutableStateOf(b.focus.int())
    var reasons by mutableStateOf(b.reason_tags.list().toSet())
    var progress by mutableIntStateOf(b.progress_done.int() ?: 0)
    val notes = mutableStateMapOf<String, String>()
    val open = mutableStateMapOf<String, Boolean>()
    var touched by mutableStateOf(false)
    val outcomeGuess = b.outcome.str() != null && !b.outcome.confirmed
    val focusGuess = b.focus.int() != null && !b.focus.confirmed
    val reasonGuess = b.reason_tags.list().isNotEmpty() && !b.reason_tags.confirmed
}

private fun hmOf(iso: String): String = runCatching {
    OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
}.getOrDefault("")

/** §3.3 정리 카드. 모든 칸은 비워도 된다. 안 건드린 블록은 건너뛴 것으로 저장된다. */
@Composable
fun OutcomeCardView(card: CardDto) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries = remember(card.id) { card.payload.blocks.associate { it.block_id to Entry(it) } }
    var dayNote by remember(card.id) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember(card.id) { mutableStateOf<String?>(null) }

    val closed = card.status !in listOf("sent", "later")
    if (closed || done != null) {
        Text(done ?: when (card.status) {
            "submitted" -> "저장됨"
            "closed" -> "다음 정리 카드로 옮겼어요"
            "expired" -> "지난 카드예요"
            else -> card.status
        }, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        return
    }
    val chips = card.payload.reason_chips
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        card.payload.blocks.forEach { b ->
            val e = entries.getValue(b.block_id)
            HorizontalDivider()
            Text(b.title + (if (b.fixed) " (고정)" else "") + "  " + hmOf(b.start) + "–" + hmOf(b.end), fontWeight = FontWeight.Bold)
            val outcomeOptions = if (b.fixed) listOf("done" to "갔음", "partial" to "일부만", "missed" to "못 감")
            else listOf("done" to "다 함", "partial" to "일부", "missed" to "못 함")
            Text("결과·진행", style = MaterialTheme.typography.labelMedium)
            Pills(outcomeOptions, setOfNotNull(e.outcome), faint = e.outcomeGuess && !e.touched) {
                e.outcome = if (e.outcome == it && e.touched) null else it
                e.touched = true
            }
            b.progress_unit?.let { unit ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { if (e.progress > 0) e.progress--; e.touched = true }) { Text("−") }
                    Text("${e.progress}$unit")
                    TextButton(onClick = { e.progress++; e.touched = true }) { Text("+") }
                }
            }
            NoteToggle(e, "outcome_note")
            Text("집중", style = MaterialTheme.typography.labelMedium)
            Pills(listOf("1" to "안 됨", "2" to "보통", "3" to "잘 됨"), setOfNotNull(e.focus?.toString()),
                faint = e.focusGuess && !e.touched) {
                val v = it.toInt()
                e.focus = if (e.focus == v && e.touched) null else v
                e.touched = true
            }
            NoteToggle(e, "focus_note")
            Text("이유", style = MaterialTheme.typography.labelMedium)
            val reasonOptions = when (e.outcome) {
                "done" -> chips.done
                "partial", "missed" -> chips.not_done
                else -> chips.done + chips.not_done
            }.map { it.tag to it.label }
            Pills(reasonOptions, e.reasons, faint = e.reasonGuess && !e.touched) {
                e.reasons = if (it in e.reasons) e.reasons - it else e.reasons + it
                e.touched = true
            }
            NoteToggle(e, "reason_note")
            OutlinedTextField(e.notes["extra_note"] ?: "", { e.notes["extra_note"] = it; e.touched = true },
                label = { Text("추가 내용") }, placeholder = { Text("이 블록 전반에 대해 자유롭게") },
                modifier = Modifier.fillMaxWidth())
        }
        if (card.payload.evening) {
            HorizontalDivider()
            OutlinedTextField(dayNote, { dayNote = it }, label = { Text("오늘 하루 한마디") }, modifier = Modifier.fillMaxWidth())
        }
        error?.let { Text(it, color = Color(0xFFC92A2A)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !saving, onClick = {
                saving = true
                val blocks = card.payload.blocks.map { b ->
                    val e = entries.getValue(b.block_id)
                    val m = mutableMapOf<String, JsonElement>("block_id" to JsonPrimitive(b.block_id))
                    if (e.touched) {
                        e.outcome?.let { m["outcome"] = JsonPrimitive(it) }
                        e.focus?.let { m["focus"] = JsonPrimitive(it) }
                        if (e.reasons.isNotEmpty()) m["reason_tags"] = JsonArray(e.reasons.map { JsonPrimitive(it) })
                        if (b.progress_unit != null && e.progress > (b.progress_done.int() ?: 0)) m["progress_done"] = JsonPrimitive(e.progress)
                        e.notes.forEach { (k, v) -> if (v.isNotBlank()) m[k] = JsonPrimitive(v.trim()) }
                    }
                    JsonObject(m)
                }
                val body = JsonObject(mapOf("blocks" to JsonArray(blocks), "day_note" to JsonPrimitive(dayNote.trim())))
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).submitCard(card.id, body) } }
                    saving = false
                    r.onSuccess { done = "저장됨"; Chat.requestRefresh() }.onFailure { error = ApiClient.errorMessage(it) }
                }
            }) { Text("저장") }
            OutlinedButton(enabled = !saving, onClick = {
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).cardLater(card.id) } }
                    r.onSuccess { done = "나중에 다시 물어볼게요"; Chat.requestRefresh() }.onFailure { error = ApiClient.errorMessage(it) }
                }
            }) { Text("나중에") }
        }
    }
}

@Composable
private fun NoteToggle(e: Entry, key: String) {
    val open = e.open[key] == true || !e.notes[key].isNullOrEmpty()
    if (!open) {
        Text("[서술]", color = INSIGHT_PINK, fontSize = 12.sp, modifier = Modifier.clickable { e.open[key] = true })
    } else {
        OutlinedTextField(e.notes[key] ?: "", { e.notes[key] = it; e.touched = true }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("자유롭게") })
    }
}

/** §4 아침 카드: 잠·컨디션. 버튼은 바로 저장, 답하지 않아도 된다. */
@Composable
fun DayCardView(card: CardDto) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sleep by remember(card.id) { mutableStateOf<Int?>(null) }
    var condition by remember(card.id) { mutableStateOf<Int?>(null) }
    var sleepNote by remember(card.id) { mutableStateOf("") }
    var conditionNote by remember(card.id) { mutableStateOf("") }
    var openSleep by remember { mutableStateOf(false) }
    var openCondition by remember { mutableStateOf(false) }
    var done by remember(card.id) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    if (card.status != "sent" || done != null) {
        Text(done ?: "저장됨", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("잠", style = MaterialTheme.typography.labelMedium)
        Pills(listOf("1" to "못 잠", "2" to "보통", "3" to "잘 잠"), setOfNotNull(sleep?.toString())) { sleep = it.toInt() }
        if (openSleep) OutlinedTextField(sleepNote, { sleepNote = it }, modifier = Modifier.fillMaxWidth())
        else Text("[서술]", color = INSIGHT_PINK, fontSize = 12.sp, modifier = Modifier.clickable { openSleep = true })
        Text("컨디션", style = MaterialTheme.typography.labelMedium)
        Pills(listOf("1" to "나쁨", "2" to "보통", "3" to "좋음"), setOfNotNull(condition?.toString())) { condition = it.toInt() }
        if (openCondition) OutlinedTextField(conditionNote, { conditionNote = it }, modifier = Modifier.fillMaxWidth())
        else Text("[서술]", color = INSIGHT_PINK, fontSize = 12.sp, modifier = Modifier.clickable { openCondition = true })
        error?.let { Text(it, color = Color(0xFFC92A2A)) }
        Button(onClick = {
            val m = mutableMapOf<String, JsonElement>()
            sleep?.let { m["sleep"] = JsonPrimitive(it) }
            condition?.let { m["condition"] = JsonPrimitive(it) }
            if (sleepNote.isNotBlank()) m["sleep_note"] = JsonPrimitive(sleepNote.trim())
            if (conditionNote.isNotBlank()) m["condition_note"] = JsonPrimitive(conditionNote.trim())
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    runCatching { ApiClient.get(context).submitDayCard(card.logical_day, JsonObject(m)) }
                }
                r.onSuccess { done = "저장됨"; Chat.requestRefresh() }.onFailure { error = ApiClient.errorMessage(it) }
            }
        }) { Text("저장") }
    }
}

// ------------------------------------------------------------------ §10.3 비서가 아는 나

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutMeScreen(onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("프로필", "패턴", "대하는 법", "기억", "지난 기록")
    Scaffold(topBar = {
        TopAppBar(title = { Text("비서가 아는 나") }, navigationIcon = { TextButton(onClick = onBack) { Text("← 뒤로") } })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            MemoryToggle()
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            Box(Modifier.fillMaxSize().padding(12.dp)) {
                when (tab) {
                    0 -> ProfileTab()
                    1 -> PatternTab()
                    2 -> MemoryListTab(kinds = listOf("guideline"), editable = true)
                    3 -> MemoryListTab(kinds = listOf("fact", "episode"), editable = false)
                    else -> HistoryTab()
                }
            }
        }
    }
}

/** 기억 끄기: 사실 뽑기·밤 정리를 멈춘다(카드·통계는 계속). */
@Composable
private fun MemoryToggle() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        enabled = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).memoryProfile().memory_enabled }.getOrNull() }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("기억하기", fontWeight = FontWeight.Bold)
            Text("끄면 대화에서 사실을 뽑거나 밤에 정리하지 않아요. 정리 카드와 통계는 계속돼요.",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        enabled?.let { on ->
            Switch(checked = on, onCheckedChange = { v ->
                enabled = v
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        ApiClient.get(context).putSetting(JsonObject(mapOf("key" to JsonPrimitive("memory.enabled"), "value" to JsonPrimitive(v))))
                    }
                }
            })
        }
    }
}

@Composable
private fun ProfileTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sections by remember { mutableStateOf<List<ProfileSectionDto>>(emptyList()) }
    val drafts = remember { mutableStateMapOf<String, String>() }
    var message by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).memoryProfile() } }
        r.onSuccess { p -> sections = p.sections; p.sections.forEach { drafts[it.section] = it.content } }
            .onFailure { message = ApiClient.errorMessage(it) }
    }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("비서가 모든 대화에서 보는 요약이에요. 직접 고치면 비서는 덧붙이기만 해요(섹션당 400자).",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        sections.forEach { s ->
            val draft = drafts[s.section] ?: ""
            OutlinedTextField(draft, { drafts[s.section] = it.take(400) },
                label = { Text(s.label + (if (s.updated_by == "user") " · 내가 씀" else "")) },
                modifier = Modifier.fillMaxWidth(), minLines = 2)
            if (draft != s.content) {
                TextButton(onClick = {
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching {
                                ApiClient.get(context).patchProfile(JsonObject(mapOf(
                                    "section" to JsonPrimitive(s.section), "content" to JsonPrimitive(draft.trim()))))
                            }
                        }
                        r.onSuccess { message = "저장했어요"; reload++ }.onFailure { message = ApiClient.errorMessage(it) }
                    }
                }) { Text("저장") }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun pct(v: Double?): String = if (v == null) "-" else "${Math.round(v * 100)}%"

/** 막대 한 줄. 근거 부족이면 회색. */
@Composable
private fun StatBar(label: String, s: StatDto, ratio: Boolean = true) {
    val grey = s.insufficient
    val v = (s.value ?: 0.0).coerceIn(0.0, if (ratio) 1.0 else 2.0) / (if (ratio) 1.0 else 2.0)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.width(76.dp), fontSize = 13.sp)
        Box(Modifier.weight(1f).height(14.dp).background(Color(0xFFF1F3F5), RoundedCornerShape(4.dp))) {
            Box(Modifier.fillMaxWidth(v.toFloat()).height(14.dp)
                .background(if (grey) Color(0xFFCED4DA) else INSIGHT_PINK, RoundedCornerShape(4.dp)))
        }
        Spacer(Modifier.width(6.dp))
        Text((if (ratio) pct(s.value) else String.format("%.2f", s.value ?: 0.0)) + " n=${s.n}" + if (grey) " 근거 부족" else "",
            fontSize = 12.sp, color = if (grey) Color.Gray else Color.Unspecified)
    }
}

private val BAND_LABELS = mapOf("dawn" to "새벽", "morning" to "오전", "afternoon" to "오후", "evening" to "저녁", "night" to "밤")

@Composable
private fun PatternTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<PatternSummaryDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).patternSummary() } }
        r.onSuccess { summary = it }.onFailure { error = ApiClient.errorMessage(it) }
    }
    val s = summary
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        error?.let { Text(it, color = Color(0xFFC92A2A)) }
        if (s == null) return@Column
        Text("패턴(비서가 숫자 근거로 정리한 것)", fontWeight = FontWeight.Bold)
        if (s.insights.isEmpty()) Text("아직 없어요. 며칠 기록이 쌓이면 생겨요.", color = Color.Gray)
        s.insights.forEach { i ->
            Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp)).padding(8.dp)) {
                Text(i.content)
                Text(evidenceText(i) + " · " + (i.updated_at ?: "").take(10), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).wrongMemoryItem(i.id) } }
                        reload++
                    }
                }) { Text("틀렸어요", color = Color(0xFFC92A2A)) }
            }
        }
        HorizontalDivider()
        Text("시간대별 완료율", fontWeight = FontWeight.Bold)
        listOf("dawn", "morning", "afternoon", "evening", "night").forEach { b ->
            s.band_completion.firstOrNull { it.key == b }?.let { StatBar(BAND_LABELS[b] ?: b, it) }
        }
        if (s.band_completion.isEmpty()) Text("아직 기록이 없어요", color = Color.Gray)
        Text("잠·컨디션별 완료율", fontWeight = FontWeight.Bold)
        s.sleep_completion.sortedBy { it.key }.forEach { StatBar("잠 " + mapOf("1" to "못 잠", "2" to "보통", "3" to "잘 잠")[it.key], it) }
        s.condition_completion.sortedBy { it.key }.forEach {
            StatBar("컨디션 " + mapOf("1" to "나쁨", "2" to "보통", "3" to "좋음")[it.key], it)
        }
        if (s.sleep_completion.isEmpty() && s.condition_completion.isEmpty()) Text("아침 카드에 답하면 생겨요", color = Color.Gray)
        Text("목표별 속도(필요 속도 대비)", fontWeight = FontWeight.Bold)
        s.goal_pace.forEach { StatBar((it.title ?: "목표").take(6), it, ratio = false) }
        if (s.goal_pace.isEmpty()) Text("마감이 있는 목표가 없어요", color = Color.Gray)
        Text("근거 부족(n<${s.min_samples})은 회색이에요. 비서는 그런 값으로 단정하지 않아요.",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
}

private fun evidenceText(i: MemoryItemDto): String {
    val stats = ((i.evidence as? JsonObject)?.get("stats") as? JsonArray) ?: return "근거 없음"
    if (stats.isEmpty()) return "근거 없음"
    return stats.joinToString(", ") { s ->
        val o = s as? JsonObject
        val n = o?.get("n")?.jsonPrimitive?.contentOrNull ?: "?"
        "n=$n"
    }
}

/** 대하는 법(guideline)·사실·일화 목록. 분류로 거르고 검색하고 지운다. */
@Composable
private fun MemoryListTab(kinds: List<String>, editable: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<MemoryItemDto>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(kinds.first()) }
    var reload by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<MemoryItemDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reload, kind, category) {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                ApiClient.get(context).memoryItems(kind = kind, category = category.ifEmpty { null },
                    query = query.trim().ifEmpty { null }).items
            }
        }
        r.onSuccess { items = it }.onFailure { error = ApiClient.errorMessage(it) }
    }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (kinds.size > 1) {
            Pills(kinds.map { it to (if (it == "fact") "사실" else "일화") }, setOf(kind)) { kind = it }
        }
        Pills(listOf("" to "전체") + CATEGORY_OPTIONS + listOf("snooze" to "미루기", "persuasion" to "설득",
            "health" to "건강", "preference" to "선호"), setOf(category)) { category = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, label = { Text("검색") }, modifier = Modifier.weight(1f), singleLine = true)
            TextButton(onClick = { reload++ }) { Text("찾기") }
        }
        error?.let { Text(it, color = Color(0xFFC92A2A)) }
        if (items.isEmpty()) Text("없어요", color = Color.Gray)
        items.forEach { i ->
            Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(i.content)
                    Text(categoryLabel(i.category).ifEmpty { i.category } + " · " + when (i.created_by) {
                        "user" -> "내가 말함"
                        "reflection" -> "밤 정리"
                        "extraction" -> "대화에서"
                        else -> i.created_by
                    }, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                if (editable) TextButton(onClick = { editing = i }) { Text("고치기") }
                TextButton(onClick = {
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).deleteMemoryItem(i.id) } }
                        r.onFailure { error = ApiClient.errorMessage(it) }
                        reload++
                    }
                }) { Text("지우기", color = Color(0xFFC92A2A)) }
            }
        }
    }
    editing?.let { item ->
        var text by remember(item.id) { mutableStateOf(item.content) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("대하는 법 고치기") },
            text = { OutlinedTextField(text, { text = it.take(200) }, minLines = 2) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching {
                                ApiClient.get(context).patchMemoryItem(item.id, JsonObject(mapOf("content" to JsonPrimitive(text.trim()))))
                            }
                        }
                        r.onFailure { error = ApiClient.errorMessage(it) }
                        editing = null
                        reload++
                    }
                }) { Text("저장") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("취소") } },
        )
    }
}

private val RESULT_TEXT = mapOf("done" to "다 함", "partial" to "일부", "missed" to "못 함")
private val FOCUS_TEXT = mapOf(1 to "안 됨", 2 to "보통", 3 to "잘 됨")

/** 지난 기록: 날짜별 결과·집중·이유·서술. 여기서 고치면 내가 정한 값이 된다. */
@Composable
private fun HistoryTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var day by remember { mutableStateOf(LocalDate.now()) }
    var blocks by remember { mutableStateOf<List<FactDto>>(emptyList()) }
    var chips by remember { mutableStateOf<Map<String, Map<String, String>>>(emptyMap()) }
    var dayFacts by remember { mutableStateOf<JsonObject?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<FactDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(day, reload) {
        val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).factBlocks(day.toString()) } }
        r.onSuccess { blocks = it.blocks; chips = it.reason_chips; dayFacts = it.day_facts as? JsonObject }
            .onFailure { error = ApiClient.errorMessage(it) }
    }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { day = day.minusDays(1) }) { Text("◀") }
            Text(day.toString(), fontWeight = FontWeight.Bold)
            TextButton(onClick = { day = day.plusDays(1) }) { Text("▶") }
        }
        dayFacts?.let { d ->
            val sleep = d["sleep"]?.jsonPrimitive?.intOrNull
            val cond = d["condition"]?.jsonPrimitive?.intOrNull
            Text("잠 " + (mapOf(1 to "못 잠", 2 to "보통", 3 to "잘 잠")[sleep] ?: "-") + " · 컨디션 "
                + (mapOf(1 to "나쁨", 2 to "보통", 3 to "좋음")[cond] ?: "-")
                + (d["day_note"]?.jsonPrimitive?.contentOrNull?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
        }
        error?.let { Text(it, color = Color(0xFFC92A2A)) }
        if (blocks.isEmpty()) Text("이날 기록이 없어요", color = Color.Gray)
        val labels = (chips["done"] ?: emptyMap()) + (chips["not_done"] ?: emptyMap())
        blocks.forEach { f ->
            Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp)).clickable { editing = f }.padding(8.dp)) {
                Text((f.planned_start?.let { hmOf(it) } ?: "") + " " + f.title + (if (f.kind == "fixed") " (고정)" else ""),
                    fontWeight = FontWeight.Bold)
                Text("결과 " + (RESULT_TEXT[f.outcome] ?: "-") + srcMark(f.outcome_src) + " · 집중 " + (FOCUS_TEXT[f.focus] ?: "-")
                    + srcMark(f.focus_src) + " · 이유 " + f.reason_tags.joinToString { labels[it] ?: it }.ifEmpty { "-" },
                    style = MaterialTheme.typography.bodySmall)
                listOfNotNull(f.outcome_note, f.focus_note, f.reason_note, f.extra_note).forEach {
                    Text("“$it”", style = MaterialTheme.typography.bodySmall, color = Color(0xFF495057))
                }
            }
        }
    }
    editing?.let { f ->
        var outcome by remember(f.block_id) { mutableStateOf(f.outcome) }
        var focus by remember(f.block_id) { mutableStateOf(f.focus) }
        var reasons by remember(f.block_id) { mutableStateOf(f.reason_tags.toSet()) }
        var note by remember(f.block_id) { mutableStateOf(f.extra_note ?: "") }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(f.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pills(RESULT_TEXT.toList(), setOfNotNull(outcome)) { outcome = it }
                    Pills(FOCUS_TEXT.map { it.key.toString() to it.value }, setOfNotNull(focus?.toString())) { focus = it.toInt() }
                    val options = (if (outcome == "done") chips["done"] else if (outcome == null) (chips["done"] ?: emptyMap()) + (chips["not_done"] ?: emptyMap()) else chips["not_done"])
                        ?: emptyMap()
                    Pills(options.toList(), reasons) { reasons = if (it in reasons) reasons - it else reasons + it }
                    OutlinedTextField(note, { note = it }, label = { Text("추가 내용") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val m = mutableMapOf<String, JsonElement>()
                    outcome?.let { m["outcome"] = JsonPrimitive(it) }
                    focus?.let { m["focus"] = JsonPrimitive(it) }
                    m["reason_tags"] = JsonArray(reasons.map { JsonPrimitive(it) })
                    if (note.isNotBlank()) m["extra_note"] = JsonPrimitive(note.trim())
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).patchFact(f.block_id, JsonObject(m)) } }
                        r.onFailure { error = ApiClient.errorMessage(it) }
                        editing = null
                        reload++
                    }
                }) { Text("저장") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("취소") } },
        )
    }
}

private fun srcMark(src: String?): String = when (src) {
    "inferred" -> "(추정)"
    "text" -> "(글에서)"
    else -> ""
}
