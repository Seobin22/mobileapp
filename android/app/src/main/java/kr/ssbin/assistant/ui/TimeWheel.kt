package kr.ssbin.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime
import kotlin.math.abs

private val ROW = 40.dp
private const val VISIBLE = 5 // 가운데 1줄 + 위아래 2줄씩

/** 휠 한 칸(오전/오후, 시, 분). 가운데 줄이 선택값이다. 멈추면 onSelected. */
@Composable
private fun WheelColumn(items: List<String>, selected: Int, onSelected: (Int) -> Unit, width: Dp) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selected.coerceIn(0, items.lastIndex))
    val fling = rememberSnapFlingBehavior(lazyListState = state)
    val rowPx = with(LocalDensity.current) { ROW.toPx() }
    val center by remember {
        derivedStateOf {
            val i = state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > rowPx / 2) 1 else 0
            i.coerceIn(0, items.lastIndex)
        }
    }
    // 멈췄을 때 값을 알린다(최신 콜백으로).
    val report by rememberUpdatedState(onSelected)
    LaunchedEffect(state, items) {
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) report(center)
        }
    }
    // 밖에서 값이 바뀌면(예: 시작을 옮겨 끝이 따라감) 휠도 맞춘다.
    LaunchedEffect(selected, items.size) {
        if (!state.isScrollInProgress && center != selected) state.scrollToItem(selected.coerceIn(0, items.lastIndex))
    }
    LazyColumn(
        state = state,
        flingBehavior = fling,
        contentPadding = PaddingValues(vertical = ROW * (VISIBLE / 2)),
        modifier = Modifier.width(width).height(ROW * VISIBLE),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(items) { i, label ->
            val d = abs(i - center)
            Box(Modifier.height(ROW).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    label,
                    fontSize = if (d == 0) 24.sp else 21.sp,
                    fontWeight = if (d == 0) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.alpha(
                        when (d) {
                            0 -> 1f
                            1 -> 0.35f
                            else -> 0.15f
                        }
                    ),
                )
            }
        }
    }
}

/**
 * 오전/오후 · 시(1~12) · 분(5분 단위) 휠. 지금 값이 5분 단위가 아니면(비서가 정한 10:02 등) 그 분도 목록에 넣어
 * 건드리지 않으면 그대로 저장되게 한다.
 */
@Composable
fun TimeWheelPicker(time: LocalTime, onChange: (LocalTime) -> Unit) {
    val minutes = remember(time.minute) {
        ((0 until 60 step 5).toList() + if (time.minute % 5 != 0) listOf(time.minute) else emptyList()).sorted()
    }
    val period = if (time.hour < 12) 0 else 1
    val hour12 = if (time.hour % 12 == 0) 12 else time.hour % 12
    fun compose(p: Int, h12: Int, m: Int): LocalTime = LocalTime.of((h12 % 12) + if (p == 1) 12 else 0, m)

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // 선택 표시(가운데 줄)
        Box(
            Modifier.fillMaxWidth().height(ROW + 4.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            WheelColumn(listOf("오전", "오후"), period, { p ->
                if (p != period) onChange(compose(p, hour12, time.minute))
            }, 64.dp)
            WheelColumn((1..12).map { it.toString() }, hour12 - 1, { i ->
                if (i + 1 != hour12) onChange(compose(period, i + 1, time.minute))
            }, 56.dp)
            WheelColumn(minutes.map { "%02d".format(it) }, minutes.indexOf(time.minute).coerceAtLeast(0), { i ->
                val m = minutes[i]
                if (m != time.minute) onChange(compose(period, hour12, m))
            }, 56.dp)
        }
    }
}

fun timeText(t: LocalTime): String =
    (if (t.hour < 12) "오전 " else "오후 ") + (if (t.hour % 12 == 0) 12 else t.hour % 12) + ":" + "%02d".format(t.minute)

/** 시작→끝 분. 끝이 시작보다 이르면 다음 날로 본다. 같으면 0. */
fun minutesBetween(start: LocalTime, end: LocalTime): Int {
    val d = (end.toSecondOfDay() - start.toSecondOfDay()) / 60
    return if (d < 0) d + 24 * 60 else d
}

private fun lengthText(min: Int): String = when {
    min <= 0 -> "길이 0분"
    min % 60 == 0 -> "${min / 60}시간"
    min > 60 -> "${min / 60}시간 ${min % 60}분"
    else -> "${min}분"
}

/**
 * 시작·끝 시각 입력. 줄을 누르면 그 휠이 열린다. 시작을 바꾸면 길이를 유지한 채 끝이 따라간다.
 * time = "HH:mm", duration = 분(문자열) — 기존 저장 코드를 그대로 쓰도록 같은 모양으로 주고받는다.
 */
@Composable
fun StartEndTimeField(
    time: String,
    duration: String,
    onTime: (String) -> Unit,
    onDuration: (String) -> Unit,
    startEnabled: Boolean = true,
) {
    val start = runCatching { LocalTime.parse(time.trim()) }.getOrNull() ?: LocalTime.of(9, 0)
    val length = duration.trim().toIntOrNull()?.coerceIn(0, 24 * 60 - 1) ?: 60
    val end = start.plusMinutes(length.toLong())
    var open by remember { mutableStateOf(if (startEnabled) "start" else "end") }
    val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TimeRow("시작", timeText(start), open == "start", startEnabled) { open = if (open == "start") "" else "start" }
        if (open == "start" && startEnabled) {
            TimeWheelPicker(start) { onTime(it.format(fmt)) }
        }
        val nextDay = length > 0 && end.toSecondOfDay() <= start.toSecondOfDay()
        TimeRow("끝", timeText(end) + (if (nextDay) " (다음 날)" else "") + " · " + lengthText(length), open == "end", true) {
            open = if (open == "end") "" else "end"
        }
        if (open == "end") {
            TimeWheelPicker(end) { onDuration(minutesBetween(start, it).toString()) }
        }
    }
}

@Composable
private fun TimeRow(label: String, value: String, opened: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(
                if (opened) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(10.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(40.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        )
    }
}
