package kr.ssbin.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kr.ssbin.assistant.chat.Chat
import kr.ssbin.assistant.data.ApiClient
import kr.ssbin.assistant.data.BlockDetailDto
import kr.ssbin.assistant.data.CalendarBlock
import kr.ssbin.assistant.data.CalendarResponse
import kr.ssbin.assistant.data.DeadlineDto
import kr.ssbin.assistant.data.FixedBlockRequest
import kr.ssbin.assistant.data.GoalDto
import kr.ssbin.assistant.data.SeriesRequest
import kr.ssbin.assistant.data.TaskDetailDto
import kr.ssbin.assistant.data.TimelineItem
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

private val CAL_PINK = Color(0xFFD6336C)
private val CAL_ERR = Color(0xFFC92A2A)
private val FIXED_BG = Color(0xFFDEE2E6)
private val FIXED_TEXT = Color(0xFF343A40)
private val FLAG = Color(0xFFE03131)
private const val DAY_NAMES = "월화수목금토일"

enum class CalMode { Month, Week, Day }

private fun zoned(iso: String): ZonedDateTime = OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault())
private fun hm(iso: String?): String = iso?.let { runCatching { zoned(it).format(DateTimeFormatter.ofPattern("HH:mm")) }.getOrNull() } ?: ""
private fun mdHm(iso: String?): String = iso?.let { runCatching { zoned(it).format(DateTimeFormatter.ofPattern("M/d(E) HH:mm")) }.getOrNull() } ?: ""
private fun isoOf(t: LocalDateTime): String = t.atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()

/** 반복 규칙 표시용 */
private fun freqLabel(freq: String, interval: Int): String = when (freq) {
    "daily" -> "매일"
    "weekly" -> if (interval == 2) "격주" else "매주"
    "monthly" -> "매월"
    else -> "안 함"
}

// ------------------------------------------------------------------ 달력 탭 (§8.1)

/**
 * 달력(빌드문서 v3.2 §8.1). 월·주·일 보기. 고정은 회색·'고정'·반복 아이콘·메모 첫 줄, 계획은 목표 색·이름표,
 * 마감은 빨간 깃발(월 보기는 빨간 점). 빈 시간을 길게 누르면 그 시각으로 고정 일정 추가.
 */
@Composable
fun CalendarScreen(tick: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(CalMode.Week) }
    var anchor by remember { mutableStateOf(LocalDate.now()) }
    var data by remember { mutableStateOf<CalendarResponse?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var openBlock by remember { mutableStateOf<Long?>(null) }
    var openTask by remember { mutableStateOf<Long?>(null) }
    var addAt by remember { mutableStateOf<LocalDateTime?>(null) }
    var adding by remember { mutableStateOf(false) }
    var editBlock by remember { mutableStateOf<Long?>(null) }

    val from: LocalDate = when (mode) {
        CalMode.Month -> anchor.withDayOfMonth(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        CalMode.Week -> anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        CalMode.Day -> anchor
    }
    val days = when (mode) {
        CalMode.Month -> 42
        CalMode.Week -> 7
        CalMode.Day -> 1
    }

    LaunchedEffect(Unit) { Chat.refresh.collect { reload++ } }
    LaunchedEffect(tick, reload, from, days) {
        loading = true
        val r = withContext(Dispatchers.IO) {
            runCatching {
                ApiClient.get(context).calendar(isoOf(from.atStartOfDay()), isoOf(from.plusDays(days.toLong()).atStartOfDay()))
            }
        }
        loading = false
        r.onSuccess { data = it; error = null }.onFailure { error = ApiClient.errorMessage(it) }
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                anchor = when (mode) {
                    CalMode.Month -> anchor.minusMonths(1)
                    CalMode.Week -> anchor.minusWeeks(1)
                    CalMode.Day -> anchor.minusDays(1)
                }
            }) { Text("◀") }
            Text(
                when (mode) {
                    CalMode.Month -> anchor.format(DateTimeFormatter.ofPattern("yyyy년 M월"))
                    CalMode.Week -> from.format(DateTimeFormatter.ofPattern("M/d")) + " – " +
                        from.plusDays(6).format(DateTimeFormatter.ofPattern("M/d"))
                    CalMode.Day -> anchor.format(DateTimeFormatter.ofPattern("M/d(E)"))
                },
                fontWeight = FontWeight.Bold,
            )
            TextButton(onClick = {
                anchor = when (mode) {
                    CalMode.Month -> anchor.plusMonths(1)
                    CalMode.Week -> anchor.plusWeeks(1)
                    CalMode.Day -> anchor.plusDays(1)
                }
            }) { Text("▶") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { anchor = LocalDate.now() }) { Text("오늘") }
            Button(onClick = { addAt = null; adding = true }) { Text("+") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(CalMode.Month to "월", CalMode.Week to "주", CalMode.Day to "일").forEach { (m, label) ->
                val selected = mode == m
                Text(
                    label,
                    color = if (selected) Color.White else CAL_PINK,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(if (selected) CAL_PINK else Color.Transparent, RoundedCornerShape(50))
                        .clickable { mode = m }.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }
        }
        if (loading && data == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (error != null && data == null) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("불러오지 못했어요", color = CAL_ERR)
                Text(error.orEmpty(), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { reload++ }) { Text("다시 시도") }
            }
        } else {
            val d = data ?: CalendarResponse(emptyList(), emptyList())
            if (loading) Text("불러오는 중…", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp))
            when (mode) {
                CalMode.Month -> MonthView(anchor, from, d, onDay = { anchor = it; mode = CalMode.Day })
                CalMode.Week -> TimeGrid((0 until 7).map { from.plusDays(it.toLong()) }, d, compact = true,
                    onBlock = { openBlock = it }, onTask = { openTask = it },
                    onLongPress = { addAt = it; adding = true }, onDay = { anchor = it; mode = CalMode.Day })
                CalMode.Day -> TimeGrid(listOf(anchor), d, compact = false,
                    onBlock = { openBlock = it }, onTask = { openTask = it },
                    onLongPress = { addAt = it; adding = true }, onDay = {})
            }
        }
    }

    openBlock?.let { id ->
        BlockDetailDialog(id, onDismiss = { openBlock = null }, onChanged = { reload++ },
            onEdit = { openBlock = null; editBlock = id })
    }
    openTask?.let { id -> TaskDetailDialog(id, onDismiss = { openTask = null }, onChanged = { reload++ }) }
    if (adding) {
        FixedEditDialog(blockId = null, presetStart = addAt, onDismiss = { adding = false }, onSaved = { adding = false; reload++ })
    }
    editBlock?.let { id ->
        FixedEditDialog(blockId = id, presetStart = null, onDismiss = { editBlock = null }, onSaved = { editBlock = null; reload++ })
    }
}

@Composable
private fun MonthView(anchor: LocalDate, from: LocalDate, data: CalendarResponse, onDay: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    Column(Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            DAY_NAMES.forEach { n ->
                Text(n.toString(), Modifier.weight(1f).padding(4.dp), style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray)
            }
        }
        for (week in 0 until 6) {
            Row(Modifier.fillMaxWidth()) {
                for (dow in 0 until 7) {
                    val date = from.plusDays((week * 7 + dow).toLong())
                    val blocks = data.blocks.filter { zoned(it.start).toLocalDate() == date }
                    val hasDeadline = data.deadlines.any { zoned(it.at).toLocalDate() == date }
                    Column(
                        Modifier.weight(1f).aspectRatio(0.8f).padding(1.dp)
                            .background(if (date == today) Color(0xFFFFE3EC) else Color.White, RoundedCornerShape(6.dp))
                            .clickable { onDay(date) }.padding(3.dp),
                    ) {
                        Text(
                            date.dayOfMonth.toString(),
                            fontSize = 12.sp,
                            color = if (date.month != anchor.month) Color.LightGray else Color.Unspecified,
                            fontWeight = if (date == today) FontWeight.Bold else null,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            val colors = blocks.map { if (it.kind == "fixed") "fixed" else it.goal_color }.distinct().take(4)
                            colors.forEach { c ->
                                Box(Modifier.size(6.dp).background(if (c == "fixed") Color.Gray else goalColor(c), CircleShape))
                            }
                            if (hasDeadline) Box(Modifier.size(6.dp).background(FLAG, CircleShape))
                        }
                    }
                }
            }
        }
    }
}

/** 시간표에 그릴 칸 하나: 그 날 안으로 자른 시작·끝(분, firstHour 기준)과 나란히 놓을 열. */
private data class Placed(val block: CalendarBlock, val top: Long, val bottom: Long, var col: Int = 0, var cols: Int = 1)

/** 끝난 블록은 실제로 끝난 시각까지만(일찍 끝난 '일부 완료'가 다음 블록과 겹쳐 보이지 않게). */
private fun shownEnd(b: CalendarBlock): ZonedDateTime {
    val planned = zoned(b.end)
    val actual = b.actual_end?.let { runCatching { zoned(it) }.getOrNull() }
    return if (actual != null && actual.isBefore(planned) && b.status in listOf("done", "partial")) actual else planned
}

/**
 * 하루 칸 배치: 시간이 겹치는 칸끼리 묶어 그 묶음 안에서만 나란히 놓는다(겹치지 않는 칸은 전체 폭).
 * 짧은 칸은 최소 높이까지 늘리되 같은 열의 다음 칸 시작을 넘지 않는다.
 */
private fun layoutDay(blocks: List<CalendarBlock>, dayStart: ZonedDateTime, dayEnd: ZonedDateTime, minMinutes: Long): List<Placed> {
    val placed = blocks.mapNotNull { b ->
        val s = zoned(b.start).let { if (it.isBefore(dayStart)) dayStart else it }
        val e = shownEnd(b).let { if (it.isAfter(dayEnd)) dayEnd else it }
        if (!e.isAfter(s)) null
        else Placed(b, java.time.Duration.between(dayStart, s).toMinutes(), java.time.Duration.between(dayStart, e).toMinutes())
    }.sortedWith(compareBy({ it.top }, { -it.bottom }))
    var group = mutableListOf<Placed>()
    var groupEnd = -1L
    fun close() {
        val colEnds = mutableListOf<Long>()
        for (p in group) {
            val free = colEnds.indexOfFirst { it <= p.top }
            if (free >= 0) { p.col = free; colEnds[free] = p.bottom } else { p.col = colEnds.size; colEnds.add(p.bottom) }
        }
        group.forEach { it.cols = colEnds.size.coerceAtLeast(1) }
        group = mutableListOf()
    }
    for (p in placed) {
        if (group.isNotEmpty() && p.top >= groupEnd) close()
        group.add(p)
        groupEnd = maxOf(groupEnd, p.bottom)
    }
    if (group.isNotEmpty()) close()
    // 최소 높이: 같은 열에서 다음 칸 시작 전까지만 늘린다
    return placed.map { p ->
        val next = placed.filter { it !== p && it.col == p.col && it.top >= p.bottom }.minOfOrNull { it.top } ?: Long.MAX_VALUE
        val wanted = maxOf(p.bottom, p.top + minMinutes)
        p.copy(bottom = minOf(wanted, next)).also { it.col = p.col; it.cols = p.cols }
    }
}

/**
 * 주·일 보기: 에브리타임 같은 표준 시간표. 위에 요일 머리줄(마감 깃발도 여기), 왼쪽 시 눈금, 가로·세로 격자선.
 * 칸은 모서리 없는 네모로 격자에 딱 맞게 붙이고, 왼쪽 위에 제목, 그 아래 시각·장소/메모.
 * 겹치는 시간대만 칸을 나란히 나눈다. 마감은 칸을 가리지 않는 얇은 빨간 선. 빈 곳을 길게 누르면 고정 일정 추가.
 */
@Composable
private fun TimeGrid(
    dates: List<LocalDate>,
    data: CalendarResponse,
    compact: Boolean,
    onBlock: (Long) -> Unit,
    onTask: (Long) -> Unit,
    onLongPress: (LocalDateTime) -> Unit,
    onDay: (LocalDate) -> Unit,
) {
    // 두 손가락으로 벌리고 오므리면 시간 높이가 바뀐다(보기마다 기기에 기억).
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("calendar", android.content.Context.MODE_PRIVATE) }
    val zoomKey = if (compact) "hour_dp_week" else "hour_dp_day"
    var hourDp by remember(compact) { mutableFloatStateOf(prefs.getFloat(zoomKey, if (compact) 72f else 88f)) }
    val hourHeight: Dp = hourDp.dp
    val labelWidth = 24.dp
    val density = LocalDensity.current
    val hourPx = with(density) { hourHeight.toPx() }
    val zone = ZoneId.systemDefault()
    // 주 보기는 월~금, 주말에 일정·마감이 있을 때만 토·일
    val shown = if (dates.size > 1) dates.filter { d ->
        d.dayOfWeek.value <= 5 || data.blocks.any { zoned(it.start).toLocalDate() == d } ||
            data.deadlines.any { zoned(it.at).toLocalDate() == d }
    } else dates
    val earliest = data.blocks.filter { b -> shown.any { zoned(b.start).toLocalDate() == it } }
        .minOfOrNull { zoned(it.start).hour } ?: 9
    val firstHour = minOf(9, earliest)
    val hours = 24 - firstHour
    val scroll = rememberScrollState()
    val today = LocalDate.now()
    val line = Color(0xFFE9ECEF)

    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp).pointerInput(compact) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            do {
                // 스크롤보다 먼저(Initial) 받아서 두 손가락일 때만 확대·축소하고 스크롤은 막는다.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    val zoom = event.calculateZoom()
                    if (zoom != 1f) {
                        hourDp = (hourDp * zoom).coerceIn(36f, 220f)
                        event.changes.forEach { it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
            prefs.edit().putFloat(zoomKey, hourDp).apply()
        }
    }) {
        Row(Modifier.fillMaxWidth().border(1.dp, line).background(Color.White)) {
            Spacer(Modifier.width(labelWidth))
            shown.forEach { date ->
                val isToday = date == today
                val dls = data.deadlines.filter { zoned(it.at).toLocalDate() == date }
                Column(
                    Modifier.weight(1f).border(0.5.dp, line).clickable { onDay(date) }.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(DAY_NAMES[date.dayOfWeek.value - 1].toString() + " " + date.dayOfMonth, fontSize = 12.sp,
                        color = if (isToday) CAL_PINK else Color(0xFF495057), fontWeight = FontWeight.Bold)
                    dls.take(2).forEach { dl ->
                        Text("⚑ " + dl.title, fontSize = 9.sp, color = FLAG, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { dl.task_id?.let(onTask) }.padding(horizontal = 2.dp))
                    }
                    if (dls.size > 2) Text("+${dls.size - 2}", fontSize = 9.sp, color = FLAG)
                }
            }
        }
        Row(Modifier.fillMaxSize().verticalScroll(scroll).border(1.dp, line).background(Color.White)) {
            Column(Modifier.width(labelWidth)) {
                for (h in firstHour until 24) {
                    Box(Modifier.height(hourHeight).fillMaxWidth().border(0.5.dp, line), contentAlignment = Alignment.TopEnd) {
                        Text("${if (h > 12) h - 12 else h}", fontSize = 10.sp, color = Color.Gray,
                            modifier = Modifier.padding(end = 3.dp, top = 2.dp))
                    }
                }
            }
            shown.forEach { date ->
                val dayStart = date.atStartOfDay(zone).plusHours(firstHour.toLong())
                val dayEnd = date.plusDays(1).atStartOfDay(zone)
                val dayBlocks = data.blocks.filter { zoned(it.start).isBefore(dayEnd) && shownEnd(it).isAfter(dayStart) }
                val placed = layoutDay(dayBlocks, dayStart, dayEnd, if (compact) 30 else 25)
                androidx.compose.foundation.layout.BoxWithConstraints(
                    Modifier.weight(1f).height(hourHeight * hours)
                        .pointerInput(date, firstHour) {
                            detectTapGestures(onLongPress = { offset ->
                                val minutes = ((offset.y / hourPx) * 60).toInt() / 30 * 30
                                onLongPress(date.atStartOfDay().plusHours(firstHour.toLong())
                                    .plusMinutes(minutes.toLong().coerceIn(0, hours * 60L - 30)))
                            })
                        },
                ) {
                    val colWidth = maxWidth
                    for (h in 0 until hours) {
                        Box(Modifier.offset(y = hourHeight * h).fillMaxWidth().height(1.dp).background(line))
                    }
                    Box(Modifier.fillMaxHeight().width(1.dp).background(line))
                    placed.forEach { p ->
                        val w = colWidth / p.cols
                        BlockCell(p.block, compact, Modifier
                            .offset(x = w * p.col, y = hourHeight * (p.top / 60f))
                            .width(w).height(hourHeight * ((p.bottom - p.top) / 60f))
                            .clickable { onBlock(p.block.id) })
                    }
                    data.deadlines.filter { zoned(it.at).toLocalDate() == date }.forEach { dl ->
                        val top = java.time.Duration.between(dayStart, zoned(dl.at)).toMinutes().coerceAtLeast(0)
                        Box(Modifier.offset(y = hourHeight * (top / 60f)).fillMaxWidth().height(2.dp).background(FLAG))
                    }
                    if (date == today) {
                        val nowMin = java.time.Duration.between(dayStart, ZonedDateTime.now(zone)).toMinutes()
                        if (nowMin in 0..(hours * 60L)) {
                            Box(Modifier.offset(y = hourHeight * (nowMin / 60f)).fillMaxWidth().height(2.dp)
                                .background(CAL_PINK.copy(alpha = 0.6f)))
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(firstHour, shown.firstOrNull()) {
        val nowHour = ZonedDateTime.now(zone).hour
        val target = if (shown.contains(today)) (nowHour - 1).coerceAtLeast(firstHour) else firstHour
        scroll.scrollTo(((target - firstHour) * hourPx).toInt())
    }
}

/** 칸 상태 표시: 끝난 칸은 반투명 + 결과 배지(완료·일부·놓침·지남), 지금 칸은 진행 중·알림 중. */
private data class CellState(val badge: String?, val badgeColor: Color, val finished: Boolean)

private fun cellState(b: CalendarBlock): CellState {
    val ended = runCatching { zoned(b.end).isBefore(ZonedDateTime.now()) }.getOrDefault(false)
    return when (b.status) {
        "done" -> CellState("✓ 완료", Color(0xFF2B8A3E), true)
        "partial" -> CellState("◐ 일부", Color(0xFFE67700), true)
        "missed" -> CellState("✕ 놓침", Color(0xFFC92A2A), true)
        "started" -> CellState("▶ 진행 중", Color(0xFF1C7ED6), false)
        "nudging" -> CellState("🔔 알림 중", Color(0xFFD6336C), false)
        // 아직 처리 전인데 끝 시각이 지난 칸(서버가 곧 놓침으로 바꾼다)
        else -> if (ended) CellState("지남", Color(0xFF868E96), true) else CellState(null, Color.Transparent, false)
    }
}

/**
 * 시간표 칸: 모서리 없는 네모, 얇은 흰 테두리로만 구분. 고정은 차분한 남색, 계획은 목표 색.
 * 끝난 칸은 연한 반투명 바탕 + 왼쪽 색 띠 + 회색 글자로 바꾸고 결과 배지를 단다(놓친 칸은 제목에 취소선).
 */
@Composable
private fun BlockCell(b: CalendarBlock, compact: Boolean, modifier: Modifier) {
    val fixed = b.kind == "fixed"
    val base = if (fixed) Color(0xFF7C8DA6) else goalColor(b.goal_color.ifBlank { null })
    val state = cellState(b)
    val textColor = if (state.finished) Color(0xFF868E96) else Color.White
    val subColor = if (state.finished) Color(0xFFADB5BD) else Color.White.copy(alpha = 0.9f)
    Row(
        modifier.background(if (state.finished) base.copy(alpha = 0.12f) else base.copy(alpha = 0.95f))
            .border(0.5.dp, Color.White),
    ) {
        if (state.finished) Box(Modifier.width(3.dp).fillMaxHeight().background(base.copy(alpha = 0.6f)))
        Column(Modifier.padding(horizontal = 3.dp, vertical = 2.dp)) {
            state.badge?.let { badge ->
                Text(
                    badge,
                    fontSize = if (compact) 8.sp else 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (state.finished) state.badgeColor else Color.White,
                    maxLines = 1,
                    modifier = if (state.finished) Modifier else Modifier.background(state.badgeColor).padding(horizontal = 2.dp),
                )
            }
            Text(
                (if (b.series_id != null) "🔁" else "") + b.title,
                fontSize = if (compact) 11.sp else 14.sp, fontWeight = FontWeight.Bold, color = textColor,
                maxLines = if (compact) 3 else 2, overflow = TextOverflow.Ellipsis, lineHeight = if (compact) 13.sp else 17.sp,
                textDecoration = if (b.status == "missed") TextDecoration.LineThrough else null,
            )
            Text(
                hm(b.start) + if (compact) "" else "–" + hm(b.end),
                fontSize = if (compact) 9.sp else 12.sp, color = subColor, maxLines = 1,
            )
            val sub = when {
                fixed && b.location != null -> b.location
                fixed && b.memo.isNotBlank() -> b.memo.lines().first()
                !fixed && b.linked_title != null -> "→ " + b.linked_title
                else -> null
            }
            if (!state.finished) sub?.let {
                Text(it, fontSize = if (compact) 9.sp else 12.sp, color = subColor,
                    maxLines = if (compact) 2 else 3, overflow = TextOverflow.Ellipsis)
            }
            if (fixed && !state.finished) {
                b.linked_prep.filter { it.status != "cancelled" }.take(if (compact) 1 else 2).forEach { p ->
                    Text("준비: ${mdHm(p.start)} ${p.title}", fontSize = if (compact) 8.sp else 11.sp,
                        color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 블록 상세 (§8.3)

@Composable
fun BlockDetailDialog(blockId: Long, onDismiss: () -> Unit, onChanged: () -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<BlockDetailDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var memo by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var askPlanned by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).block(blockId) } }
        r.onSuccess { detail = it; memo = if (it.series_id != null) it.once_memo else it.memo; error = null }
            .onFailure { error = ApiClient.errorMessage(it) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(detail?.title ?: "블록") },
        text = {
            val d = detail
            if (d == null) {
                if (error != null) {
                    Column {
                        Text("불러오지 못했어요", color = CAL_ERR)
                        OutlinedButton(onClick = { reload++ }) { Text("다시 시도") }
                    }
                } else {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                return@AlertDialog
            }
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val fixed = d.kind == "fixed"
                Text((if (fixed) "고정" else "계획") + (if (d.series != null) " · 🔁 " + d.series.rule_text else "") +
                    " · " + mdHm(d.start) + "–" + hm(d.end))
                if (d.goal_label.isNotBlank()) GoalChip(d.goal_label, d.goal_color)
                d.location?.let { Text("장소: $it") }
                if (fixed && d.lead_minutes > 0) Text("출발·준비 ${d.lead_minutes}분 전")
                if (!fixed) {
                    d.linked_title?.let { Text("연결된 고정 일정: $it") }
                    d.deadline?.let { Text("마감 " + mdHm(it), color = FLAG) }
                }
                if (fixed) {
                    if (d.series != null && d.series.memo.isNotBlank()) Text("매번 메모: " + d.series.memo)
                    OutlinedTextField(memo, { memo = it }, label = { Text(if (d.series != null) "이번만 메모" else "메모") },
                        modifier = Modifier.fillMaxWidth(), maxLines = 6)
                    Button(onClick = {
                        saving = true
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                runCatching { ApiClient.get(context).patchFixedBlock(blockId, FixedBlockRequest(memo = memo)) }
                            }
                            saving = false
                            r.onSuccess { onChanged(); reload++ }.onFailure { error = ApiClient.errorMessage(it) }
                        }
                    }, enabled = !saving) {
                        if (saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("메모 저장")
                    }
                    if (d.linked_prep.isNotEmpty()) {
                        Text("준비 블록", fontWeight = FontWeight.Bold)
                        d.linked_prep.forEach { p -> Text("· ${mdHm(p.start)} ${p.title} (${p.status})") }
                    }
                }
                // 결과(완료·일부·놓침)는 시작 시각이 지난 블록이면 사용자가 직접 바꿀 수 있다.
                val started = runCatching { !zoned(d.start).isAfter(ZonedDateTime.now()) }.getOrDefault(false)
                if (started && d.status != "cancelled") {
                    Text("결과", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("done" to "✓ 완료", "partial" to "◐ 일부", "missed" to "✕ 놓침").forEach { (key, label) ->
                            val selected = d.status == key
                            Text(
                                label,
                                color = if (selected) Color.White else CAL_PINK,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.background(if (selected) CAL_PINK else Color.Transparent)
                                    .border(1.dp, CAL_PINK)
                                    .clickable(enabled = !saving && !selected) {
                                        saving = true
                                        scope.launch {
                                            val r = withContext(Dispatchers.IO) {
                                                runCatching {
                                                    ApiClient.get(context).patchBlockResult(blockId,
                                                        JsonObject(mapOf("status" to JsonPrimitive(key))))
                                                }
                                            }
                                            saving = false
                                            r.onSuccess { onChanged(); reload++ }.onFailure { error = ApiClient.errorMessage(it) }
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                Text("기록", fontWeight = FontWeight.Bold)
                if (d.logs.isEmpty()) Text("아직 기록이 없어요", style = MaterialTheme.typography.bodySmall)
                d.logs.forEach { l ->
                    val icon = when (l.actor) {
                        "user" -> "🙋"
                        "assistant" -> "🤖"
                        else -> "⚙️"
                    }
                    Text("$icon ${mdHm(l.at)}  ${l.content}", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = CAL_ERR) }
            }
        },
        confirmButton = {
            val d = detail
            val editable = d != null && d.status in listOf("planned", "nudging")
            if (d != null && editable && d.kind == "fixed") {
                TextButton(onClick = onEdit) { Text("수정·삭제") }
            } else if (d != null && editable) {
                TextButton(onClick = { askPlanned = true }) { Text("비서에게 바꿔 달라기") }
            } else {
                TextButton(onClick = onDismiss) { Text("닫기") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
    if (askPlanned) {
        detail?.let { d ->
            PlannedBlockDialog(TimelineItem(kind = "planned_block", start = d.start, end = d.end, id = d.id, title = d.title),
                onDismiss = { askPlanned = false }, onSent = { askPlanned = false; onDismiss() })
        }
    }
}

// ------------------------------------------------------------------ 할 일 상세·마감 수정 (P1.3)

@Composable
fun TaskDetailDialog(taskId: Long, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var task by remember { mutableStateOf<TaskDetailDto?>(null) }
    var date by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(taskId) {
        val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).task(taskId) } }
        r.onSuccess { t ->
            task = t
            t.deadline?.let { dl ->
                date = zoned(dl).toLocalDate().toString()
                time = zoned(dl).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
            }
        }.onFailure { error = ApiClient.errorMessage(it) }
    }

    fun save(deadline: String?) {
        saving = true
        scope.launch {
            val body = JsonObject(mapOf("deadline" to (deadline?.let { JsonPrimitive(it) } ?: JsonNull)))
            val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).patchTaskDeadline(taskId, body) } }
            saving = false
            r.onSuccess { onChanged(); onDismiss() }.onFailure { error = ApiClient.errorMessage(it) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(task?.title ?: "할 일") },
        text = {
            val t = task
            if (t == null) {
                if (error != null) Text(error.orEmpty(), color = CAL_ERR)
                else Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@AlertDialog
            }
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("마감 " + (t.deadline?.let { mdHm(it) } ?: "없음") + (t.left_text?.let { " · $it" } ?: ""),
                    color = FLAG, fontWeight = FontWeight.Bold)
                Text("남은 작업 ${t.remaining_minutes}분")
                Text("이 마감을 위한 블록", fontWeight = FontWeight.Bold)
                if (t.blocks.isEmpty()) Text("없음", style = MaterialTheme.typography.bodySmall)
                t.blocks.forEach { b -> Text("· ${mdHm(b.start)}–${hm(b.end)} (${b.status})") }
                Text("마감 고치기", fontWeight = FontWeight.Bold)
                OutlinedTextField(date, { date = it }, label = { Text("날짜 (YYYY-MM-DD)") })
                OutlinedTextField(time, { time = it }, label = { Text("시각 (HH:MM)") })
                error?.let { Text(it, color = CAL_ERR) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && task != null, onClick = {
                val dl = runCatching { isoOf(LocalDateTime.of(LocalDate.parse(date.trim()), LocalTime.parse(time.trim()))) }.getOrNull()
                if (dl == null) {
                    error = "날짜·시각 형식을 확인하세요"
                } else {
                    save(dl)
                }
            }) { Text(if (saving) "저장 중…" else "마감 저장") }
        },
        dismissButton = {
            Row {
                TextButton(enabled = !saving && task?.deadline != null, onClick = { save(null) }) { Text("마감 없애기") }
                TextButton(onClick = onDismiss) { Text("닫기") }
            }
        },
    )
}

// ------------------------------------------------------------------ 고정 일정 추가·수정 (§8.2)

/**
 * 고정 일정 추가·수정. 반복 설정(안 함/매일/매주/격주/매월, 종료일)과 메모.
 * 반복 일정의 회차를 고치거나 지우면 "이 날만 / 이후 전부 / 전체"를 고른다. 메모는 '매번'과 '이번만' 두 칸.
 */
@Composable
fun FixedEditDialog(blockId: Long?, presetStart: LocalDateTime?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val defaultStart = presetStart ?: LocalDateTime.now().plusMinutes(30).withSecond(0).withNano(0)
    var loaded by remember { mutableStateOf(blockId == null) }
    var detail by remember { mutableStateOf<BlockDetailDto?>(null) }
    var title by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(defaultStart.toLocalDate().toString()) }
    var time by remember { mutableStateOf(defaultStart.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))) }
    var duration by remember { mutableStateOf("60") }
    var lead by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var firstAction by remember { mutableStateOf("") }
    var goalId by remember { mutableStateOf<Long?>(null) }
    var memo by remember { mutableStateOf("") }
    var onceMemo by remember { mutableStateOf("") }
    var freq by remember { mutableStateOf("none") }
    var interval by remember { mutableIntStateOf(1) }
    var byDays by remember { mutableStateOf(setOf<Int>()) }
    var until by remember { mutableStateOf("") }
    var goals by remember { mutableStateOf<List<GoalDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var askScope by remember { mutableStateOf<String?>(null) } // "save" | "delete"

    LaunchedEffect(Unit) {
        goals = withContext(Dispatchers.IO) {
            runCatching { ApiClient.get(context).goals().goals.filter { it.status == "active" } }.getOrDefault(emptyList())
        }
        if (blockId != null) {
            val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).block(blockId) } }
            r.onSuccess { d ->
                detail = d
                val s = zoned(d.start)
                title = d.title
                date = s.toLocalDate().toString()
                time = s.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
                duration = java.time.Duration.between(zoned(d.start), zoned(d.end)).toMinutes().toString()
                lead = if (d.lead_minutes > 0) d.lead_minutes.toString() else ""
                location = d.location ?: ""
                d.series?.let { sr ->
                    freq = sr.freq
                    interval = sr.interval
                    byDays = sr.by_days.toSet()
                    until = sr.until_date ?: ""
                    memo = sr.memo
                    onceMemo = d.once_memo
                    goalId = sr.goal_id
                } ?: run { memo = d.memo }
                loaded = true
            }.onFailure { error = ApiClient.errorMessage(it); loaded = true }
        } else {
            byDays = setOf(defaultStart.dayOfWeek.value)
        }
    }

    val isSeries = detail?.series != null
    val isNew = blockId == null

    fun startIso(): String? = runCatching {
        isoOf(LocalDateTime.of(LocalDate.parse(date.trim()), LocalTime.parse(time.trim())))
    }.getOrNull()

    fun seriesBody(): SeriesRequest = SeriesRequest(
        title = title.trim(), freq = freq, interval = interval,
        by_days = if (freq == "weekly") byDays.sorted().ifEmpty { listOf(LocalDate.parse(date.trim()).dayOfWeek.value) } else null,
        month_day = if (freq == "monthly") LocalDate.parse(date.trim()).dayOfMonth else null,
        start_time = time.trim(), duration_min = duration.trim().toIntOrNull(),
        start_date = date.trim(), until_date = until.trim().ifEmpty { null },
        lead_minutes = lead.trim().toIntOrNull(), location = location.trim().ifEmpty { null }, goal_id = goalId,
        first_action = firstAction.trim().ifEmpty { null }, memo = memo,
    )

    fun submit(block: suspend () -> Unit) {
        saving = true
        error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { block() } }
            saving = false
            r.onSuccess { onSaved() }.onFailure { error = ApiClient.errorMessage(it) }
        }
    }

    fun save(scopeChoice: String?) {
        val start = startIso()
        val minutes = duration.trim().toIntOrNull()
        val leadText = lead.trim()
        if (title.isBlank() || start == null || minutes == null || (leadText.isNotEmpty() && leadText.toIntOrNull() == null)) {
            error = "제목·날짜·시각·길이·출발 준비 형식을 확인하세요"
            return
        }
        val api = ApiClient.get(context)
        val fixedBody = FixedBlockRequest(
            title = title.trim(), start = start, duration_min = minutes, lead_minutes = leadText.toIntOrNull(),
            location = location.trim().ifEmpty { null }, goal_id = goalId,
            first_action = firstAction.trim().ifEmpty { null }, memo = if (isSeries) onceMemo else memo,
        )
        val d = detail
        when {
            isNew && freq != "none" -> submit { api.createSeries(seriesBody()) }
            isNew -> submit { api.createFixedBlock(fixedBody) }
            isSeries && d != null && scopeChoice == "one" -> submit {
                api.patchFixedBlock(d.id, fixedBody)
                if (memo != d.series!!.memo) api.patchSeries(d.series.id, SeriesRequest(memo = memo), "all")
            }
            isSeries && d != null -> submit {
                api.patchSeries(d.series!!.id, seriesBody(), scopeChoice ?: "all",
                    if (scopeChoice == "following") d.occurrence_date else null)
            }
            d != null -> submit { api.patchFixedBlock(d.id, fixedBody) }
        }
    }

    fun delete(scopeChoice: String?) {
        val api = ApiClient.get(context)
        val d = detail ?: return
        when {
            isSeries && scopeChoice != null && scopeChoice != "one" -> submit {
                api.deleteSeries(d.series!!.id, scopeChoice, if (scopeChoice == "following") d.occurrence_date else null)
            }
            else -> submit { api.deleteFixedBlock(d.id) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "고정 일정 추가" else "고정 일정 수정") },
        text = {
            if (!loaded) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@AlertDialog
            }
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("제목 (예: 치과, 헬스)") })
                OutlinedTextField(date, { date = it }, label = { Text("날짜 (YYYY-MM-DD)") })
                OutlinedTextField(time, { time = it }, label = { Text("시작 (HH:MM)") })
                OutlinedTextField(duration, { duration = it }, label = { Text("길이(분)") })
                OutlinedTextField(lead, { lead = it }, label = { Text("출발·준비 시간(분, 선택)") }, placeholder = { Text("비워 두면 비서가 정해요") })
                OutlinedTextField(location, { location = it }, label = { Text("장소(선택)") })
                if (isNew) OutlinedTextField(firstAction, { firstAction = it }, label = { Text("첫 행동(선택)") })
                if (isNew || isSeries) {
                    Text("반복", fontWeight = FontWeight.Bold)
                    val options = listOf("none" to 1, "daily" to 1, "weekly" to 1, "weekly" to 2, "monthly" to 1)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        options.forEach { (f, iv) ->
                            if (!isNew && f == "none") return@forEach
                            val selected = freq == f && (f != "weekly" || interval == iv)
                            Text(
                                if (f == "none") "안 함" else freqLabel(f, iv),
                                color = if (selected) Color.White else CAL_PINK,
                                fontSize = 13.sp,
                                modifier = Modifier.background(if (selected) CAL_PINK else Color.Transparent, RoundedCornerShape(50))
                                    .clickable { freq = f; interval = iv }.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                    if (freq == "weekly") {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            (1..7).forEach { d ->
                                val on = d in byDays
                                Text(
                                    DAY_NAMES[d - 1].toString(),
                                    color = if (on) Color.White else Color.DarkGray,
                                    modifier = Modifier.background(if (on) CAL_PINK else Color(0xFFF1F3F5), CircleShape)
                                        .clickable { byDays = if (on) byDays - d else byDays + d }.padding(8.dp),
                                )
                            }
                        }
                    }
                    if (freq == "monthly") Text("매월 ${runCatching { LocalDate.parse(date.trim()).dayOfMonth }.getOrDefault(1)}일(없는 달은 마지막 날)",
                        style = MaterialTheme.typography.bodySmall)
                    if (freq != "none") OutlinedTextField(until, { until = it }, label = { Text("종료일(선택, YYYY-MM-DD)") })
                }
                OutlinedTextField(memo, { memo = it }, label = { Text(if (isSeries || freq != "none") "메모 · 매번" else "메모") },
                    modifier = Modifier.fillMaxWidth(), maxLines = 6)
                if (isSeries) {
                    OutlinedTextField(onceMemo, { onceMemo = it }, label = { Text("메모 · 이번만") },
                        modifier = Modifier.fillMaxWidth(), maxLines = 4)
                }
                if (goals.isNotEmpty()) {
                    Text("목표 연결(선택)", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth().clickable { goalId = null }.padding(4.dp)) {
                        Text(if (goalId == null) "● 없음" else "○ 없음")
                    }
                    goals.forEach { g ->
                        Row(Modifier.fillMaxWidth().clickable { goalId = g.id }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (goalId == g.id) "● " else "○ ")
                            GoalChip(g.label, g.color)
                            Spacer(Modifier.width(6.dp))
                            Text(g.title)
                        }
                    }
                }
                error?.let { Text(it, color = CAL_ERR) }
            }
        },
        confirmButton = {
            TextButton(enabled = loaded && !saving, onClick = { if (isSeries) askScope = "save" else save(null) }) {
                if (saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("저장")
            }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(enabled = loaded && !saving, onClick = { if (isSeries) askScope = "delete" else delete(null) }) {
                        Text("삭제", color = CAL_ERR)
                    }
                }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        },
    )

    askScope?.let { action ->
        AlertDialog(
            onDismissRequest = { askScope = null },
            title = { Text(if (action == "save") "어디까지 바꿀까요?" else "어디까지 지울까요?") },
            text = { Text("반복 일정이에요. 메모 '이번만'은 이 날에만 적용돼요.") },
            confirmButton = {
                Column {
                    listOf("one" to "이 날만", "following" to "이후 전부", "all" to "전체").forEach { (key, label) ->
                        TextButton(onClick = {
                            askScope = null
                            if (action == "save") save(key) else delete(key)
                        }) { Text(label) }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { askScope = null }) { Text("취소") } },
        )
    }
}
