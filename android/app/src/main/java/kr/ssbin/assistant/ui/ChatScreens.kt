package kr.ssbin.assistant.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.ssbin.assistant.chat.Chat
import kr.ssbin.assistant.chat.ChatVisibility
import kr.ssbin.assistant.chat.Voice
import kr.ssbin.assistant.data.ApiClient
import kr.ssbin.assistant.data.ConfirmRequest
import kr.ssbin.assistant.data.MessageDto
import kr.ssbin.assistant.data.Prefs
import kr.ssbin.assistant.data.TimelineDay
import kr.ssbin.assistant.data.TimelineItem
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import kr.ssbin.assistant.work.Sync
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val PINK = Color(0xFFD6336C)
private val ERROR = Color(0xFFC92A2A)
private val WORK_PRODUCT_LINK = Regex("작업물\\s?#(\\d+)")

// ------------------------------------------------------------------ 채팅(메인 탭)

/** 보낸 메시지의 처리 상태(v3.2 P3.2). turn 이 끝나면 목록에서 빠진다. */
private data class PendingTurn(
    val turnId: Long,
    val messageId: Long,
    val text: String,
    val stage: String = "queued",
    val stageText: String = "앞의 작업을 마치는 중…",
    val elapsedSec: Long = 0,
)

@Composable
fun ChatScreen(tick: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<MessageDto>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var listening by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<List<PendingTurn>>(emptyList()) }
    var resumed by remember { mutableStateOf(true) }
    var firstLoad by remember { mutableStateOf(true) }
    var unseen by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    fun send(text: String, source: String, questionId: Long? = null) {
        if (text.isBlank()) return
        sending = true
        error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { Chat.send(context.applicationContext, text.trim(), source, questionId) }
            }
            sending = false
            r.onSuccess { resp ->
                input = ""
                // 처리 중에도 입력은 막지 않는다. 보낸 순서대로 서버 대기열과 같이 보인다.
                resp.turn_id?.let { pending = pending + PendingTurn(it, resp.message_id, text.trim()) }
                reload++
            }.onFailure { error = ApiClient.errorMessage(it) }
        }
    }

    // 음성: 인식된 텍스트를 바로 보낸다(source=voice)
    val voice = remember {
        Voice(context, resultCallback = { text -> listening = false; send(text, "voice") },
            errorCallback = { msg -> listening = false; error = msg })
    }
    DisposableEffect(Unit) {
        onDispose { voice.destroy() }
    }
    // '채팅을 보는 중'은 앱이 화면에 있을 때만. 홈 버튼으로 나가면 이 화면은 남아 있어도 알림이 떠야 한다.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    ChatVisibility.visible = true
                    resumed = true
                    Chat.clearNotification(context)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    ChatVisibility.visible = false
                    resumed = false
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            ChatVisibility.visible = false
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            listening = true
            voice.start()
        } else {
            error = "마이크 권한이 필요해요"
        }
    }

    LaunchedEffect(Unit) {
        Chat.refresh.collect { reload++ }
    }
    // FCM 이 없는 기기(임시 토큰)는 푸시로 새 메시지를 알 수 없으므로, 채팅 화면이 보일 때만 5초마다 다시 읽는다.
    LaunchedEffect(Unit) {
        if (Prefs.get(context).fallbackToken != null) {
            while (true) {
                delay(5_000)
                reload++
            }
        }
    }
    // v3.2 P3.2: 처리 중인 turn 이 있고 화면이 보이는 동안 1.5초마다 진행 상태를 읽는다. 화면을 벗어나면 멈춘다.
    val hasActive = pending.any { it.stage != "failed" }
    LaunchedEffect(hasActive, resumed) {
        while (hasActive && resumed) {
            val updated = withContext(Dispatchers.IO) {
                pending.map { p ->
                    if (p.stage == "failed") p
                    else runCatching { ApiClient.get(context).turnStatus(p.turnId) }.getOrNull()
                        ?.let { s -> p.copy(stage = s.stage, stageText = s.stage_text, elapsedSec = s.elapsed_sec) } ?: p
                }
            }
            if (updated.any { it.stage == "done" }) reload++
            pending = updated.filter { it.stage != "done" }
            if (pending.none { it.stage != "failed" }) break
            delay(1_500)
        }
    }
    LaunchedEffect(tick, reload) {
        val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).messages().messages } }
        r.onSuccess { list ->
            // 새 비서 답장이 왔으면 계획이 바뀌었을 수 있으니 바로 동기화해 로컬 알람을 맞춘다(FCM 없을 때 대비).
            val prefs = Prefs.get(context)
            val newestAssistant = list.filter { it.role == "assistant" }.maxOfOrNull { it.id } ?: -1
            if (newestAssistant > prefs.lastNotifiedMessageId) {
                prefs.lastNotifiedMessageId = newestAssistant
                Sync.runSoon(context.applicationContext)
            }
            messages = list
            // 화면에 보인 비서 메시지는 읽음 보고
            withContext(Dispatchers.IO) {
                list.filter { it.role == "assistant" && !it.read }.forEach {
                    runCatching { ApiClient.get(context).markRead(it.id) }
                }
            }
        }.onFailure { error = ApiClient.errorMessage(it) }
    }

    // v3.2 P3.1: 열면 항상 최신 위치. 맨 아래에 있으면 새 메시지를 따라가고, 위를 보고 있으면 [↓ n] 버튼만 띄운다.
    val pendingBubbles = pending.count { it.stage == "failed" } + if (pending.any { it.stage != "failed" }) 1 else 0
    val totalItems = messages.size + pendingBubbles
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount == 0 || last >= info.totalItemsCount - 2
        }
    }
    var lastCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(totalItems) {
        if (totalItems == 0) return@LaunchedEffect
        if (firstLoad) {
            listState.scrollToItem(totalItems - 1)
            firstLoad = false
        } else if (atBottom || lastCount == 0) {
            listState.animateScrollToItem(totalItems - 1)
            unseen = 0
        } else if (totalItems > lastCount) {
            unseen += totalItems - lastCount
        }
        lastCount = totalItems
    }
    LaunchedEffect(atBottom) { if (atBottom) unseen = 0 }

    // 미루기 협상 중 배너(v3 §8.1). 남은 분은 15초마다 다시 계산한다.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }
    val negotiationUntil = remember(now, reload) { Prefs.get(context).negotiationUntil }
    val firstActiveTurn = pending.firstOrNull { it.stage != "failed" }?.turnId

    // v3.3 §4.2: 채팅 [첨부]. 목표 없이 올라가고 비서가 어느 목표 자료인지 판단한다. 입력 중인 글은 함께 보낸다.
    var attaching by remember { mutableStateOf(false) }
    val attachPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        attaching = true
        val text = input
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { uploadDocument(context, uri, null, true, text) } }
            attaching = false
            r.onSuccess { input = ""; reload++ }.onFailure { error = ApiClient.errorMessage(it) }
        }
    }

    Column(modifier.fillMaxSize()) {
        if (negotiationUntil > now) {
            val left = ((negotiationUntil - now + 59_999) / 60_000).coerceAtLeast(1)
            Text(
                "시작 화면에서 대화 중 · 답이 없으면 ${left}분 뒤 다시 알려요",
                color = Color.White,
                modifier = Modifier.fillMaxWidth().background(PINK).padding(10.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp), state = listState) {
                if (messages.isEmpty()) {
                    item { Text("말을 걸어 보세요. 예: \"금요일까지 보고서 써야 해\"", Modifier.padding(16.dp)) }
                }
                items(messages, key = { it.id }) { m ->
                    val waiting = pending.firstOrNull { it.messageId == m.id }
                        ?.let { it.stage == "queued" && it.turnId != firstActiveTurn } == true
                    MessageBubble(m, waiting = waiting,
                        onOption = { option -> send(option, "chat", m.question_id) },
                        onConfirm = { accept ->
                            scope.launch {
                                val r = withContext(Dispatchers.IO) {
                                    runCatching { ApiClient.get(context).confirm(m.confirmation_id!!, ConfirmRequest(accept)) }
                                }
                                r.onFailure { error = ApiClient.errorMessage(it) }
                                reload++
                            }
                        })
                }
                pending.firstOrNull { it.stage != "failed" }?.let { p ->
                    item(key = "typing") {
                        TypingBubble(p.stageText.ifBlank { "생각하는 중…" } +
                            if (p.elapsedSec > 20) " (조금 오래 걸리고 있어요)" else "")
                    }
                }
                items(pending.filter { it.stage == "failed" }, key = { "failed-" + it.turnId }) { p ->
                    FailedBubble(onRetry = {
                        pending = pending.filter { it.turnId != p.turnId }
                        send(p.text, "chat")
                    })
                }
            }
            if (!atBottom && totalItems > 0) {
                Button(
                    onClick = {
                        scope.launch { listState.animateScrollToItem(totalItems - 1) }
                        unseen = 0
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                ) { Text(if (unseen > 0) "↓ $unseen" else "↓") }
            }
        }
        error?.let { Text(it, color = ERROR, modifier = Modifier.padding(horizontal = 12.dp)) }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text(if (listening) "듣는 중…" else "메시지") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            Spacer(Modifier.padding(2.dp))
            OutlinedButton(onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    listening = true
                    voice.start()
                } else {
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }, enabled = !sending && !listening) { Text("🎤") }
            Spacer(Modifier.padding(2.dp))
            OutlinedButton(onClick = { attachPicker.launch(DOCUMENT_MIME_TYPES) }, enabled = !sending && !attaching) {
                Text(if (attaching) "…" else "📎")
            }
            Spacer(Modifier.padding(2.dp))
            Button(onClick = { send(input, "chat") }, enabled = !sending && input.isNotBlank()) { Text("전송") }
        }
    }
}

/** 비서 쪽 말풍선 자리 표시: 점 세 개 애니메이션 + 서버 진행 문구. */
@Composable
private fun TypingBubble(text: String) {
    var dots by remember { mutableIntStateOf(1) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            dots = dots % 3 + 1
        }
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.CenterStart) {
        Text(
            "●".repeat(dots) + "○".repeat(3 - dots) + "  " + text,
            color = Color.Gray,
            modifier = Modifier.background(Color.White, RoundedCornerShape(12.dp)).padding(10.dp),
        )
    }
}

@Composable
private fun FailedBubble(onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.CenterStart) {
        Column(Modifier.background(Color.White, RoundedCornerShape(12.dp)).padding(10.dp)) {
            Text("처리하지 못했어요", color = ERROR)
            OutlinedButton(onClick = onRetry) { Text("다시 시도") }
        }
    }
}

@Composable
private fun MessageBubble(m: MessageDto, waiting: Boolean = false, onOption: (String) -> Unit, onConfirm: (Boolean) -> Unit) {
    val mine = m.role == "user"
    // v3.2 P1.2: 코드가 덧붙인 '저장됨' 줄(kind=system)은 작은 회색 띠로 보여준다.
    if (m.kind == "system") {
        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(m.content, style = MaterialTheme.typography.labelMedium, color = Color(0xFF495057),
                modifier = Modifier.background(Color(0xFFE9ECEF), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
            // v3.6 §3.6: 음성으로 승인한 카드 [되돌리기]
            val card = m.action?.removePrefix("undo_card:")?.toLongOrNull()
            if (card != null) {
                val context = LocalContext.current
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                var done by remember(m.id) { mutableStateOf<String?>(null) }
                if (done == null) {
                    TextButton(onClick = {
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).undoVoiceCard(card) } }
                            done = if (r.isSuccess) "되돌렸어요" else ApiClient.errorMessage(r.exceptionOrNull()!!)
                        }
                    }) { Text("되돌리기") }
                } else {
                    Text(done!!, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        return
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = if (m.card != null) 360.dp else 300.dp)
                .background(if (mine) PINK else Color.White, RoundedCornerShape(12.dp))
                .padding(10.dp)
        ) {
            Text(m.content, color = if (mine) Color.White else Color.Unspecified,
                fontWeight = if (m.card != null) FontWeight.Bold else null)
            m.document_id?.let { DocumentCard(it, mine) }
            // v3.5 §10.1: 정리 카드·아침 카드
            m.card?.let { c -> if (c.kind == "day") DayCardView(c) else OutcomeCardView(c) }
            // "작업물#12" 를 누르면 작업물 뷰어(v3 §8.2)
            if (!mine) {
                val context = LocalContext.current
                WORK_PRODUCT_LINK.findAll(m.content).map { it.groupValues[1].toLong() }.distinct().forEach { id ->
                    TextButton(onClick = { context.startActivity(WorkProductActivity.intent(context, id)) }) {
                        Text("📄 작업물#$id 열기")
                    }
                }
            }
            if (m.kind == "question" && !m.options.isNullOrEmpty()) {
                if (m.question_status == "open") {
                    m.options.forEach { option ->
                        OutlinedButton(onClick = { onOption(option) }, modifier = Modifier.fillMaxWidth()) { Text(option) }
                    }
                } else {
                    Text("답함", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (m.kind.startsWith("goal_")) {
                GoalPromptButtons(m, onChanged = { Chat.requestRefresh() })
            }
            if (!mine && m.action == "open_settings") {
                OutlinedButton(onClick = { MainActivity.openSettings.tryEmit(Unit) }) { Text("설정 열기") }
            }
            if (m.kind == "confirmation") {
                if (m.confirmation_status == "pending") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onConfirm(true) }) { Text("예") }
                        OutlinedButton(onClick = { onConfirm(false) }) { Text("아니오") }
                    }
                } else {
                    Text(
                        when (m.confirmation_status) {
                            "accepted" -> "수락함"
                            "rejected" -> "거절함"
                            "expired" -> "만료됨"
                            else -> m.confirmation_status.orEmpty()
                        },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text(time(m.created_at) + (if (m.intervention_id != null) " · 📱 시작 화면에서" else "") +
                (if (m.channel == "voice") " · 🎤 음성" else "") +
                (if (m.silent) " · 🔕" else "") + (if (waiting) " · 대기 중" else ""), style = MaterialTheme.typography.labelSmall,
                color = if (mine) Color(0xFFFFE0EA) else Color.Gray)
        }
    }
}

// ------------------------------------------------------------------ 시간표 탭

/**
 * 시간표(빌드문서 v3.1 §4.1). 모든 칸은 블록이고 모두 알람이 간다.
 * 왼쪽 색 띠·이름표 = 목표, '고정' = 사용자가 시각을 정한 블록(출발 시각 표시).
 * 길게 누르면: 고정 블록은 수정·삭제(바로 반영), 계획 블록은 비서에게 바꿔 달라고 말한다.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TimelineScreen(tick: Int, modifier: Modifier = Modifier, onAdd: () -> Unit, onEditFixed: (TimelineItem) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var days by remember { mutableStateOf<List<TimelineDay>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var menuFixed by remember { mutableStateOf<TimelineItem?>(null) }
    var menuPlanned by remember { mutableStateOf<TimelineItem?>(null) }

    LaunchedEffect(Unit) { Chat.refresh.collect { reload++ } }
    LaunchedEffect(tick, reload) {
        val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).timeline().days } }
        r.onSuccess { days = it; error = null }.onFailure { error = ApiClient.errorMessage(it) }
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { reload++ }) { Text("새로고침") }
            Button(onClick = onAdd) { Text("+ 고정 블록") }
        }
        error?.let { Text(it, color = ERROR, modifier = Modifier.padding(12.dp)) }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            items(days, key = { it.date }) { day ->
                Text(dayLabel(day.date), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                val items = day.items.filter { it.kind != "free" }
                if (items.isEmpty()) Text("일정 없음", style = MaterialTheme.typography.bodySmall)
                items.forEach { item ->
                    val isBlock = item.kind == "fixed_block" || item.kind == "planned_block"
                    val editable = item.status == "planned" || item.status == "nudging"
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).combinedClickable(
                            onClick = {},
                            onLongClick = {
                                if (editable && item.kind == "fixed_block") menuFixed = item
                                if (editable && item.kind == "planned_block") menuPlanned = item
                            },
                        )
                    ) {
                        Row(Modifier.height(IntrinsicSize.Min)) {
                            Box(Modifier.width(5.dp).fillMaxHeight()
                                .background(if (isBlock && !item.goal_color.isNullOrBlank()) goalColor(item.goal_color) else Color.Transparent))
                            Row(Modifier.padding(10.dp)) {
                                Text("${time(item.start)}–${time(item.end)}", modifier = Modifier.widthIn(min = 96.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (item.kind == "fixed_block") {
                                            Text("고정 ", color = PINK, fontWeight = FontWeight.Bold)
                                        }
                                        GoalChip(item.goal_label, item.goal_color)
                                        if (!item.goal_label.isNullOrBlank()) Spacer(Modifier.width(4.dp))
                                        Text(
                                            when (item.kind) {
                                                "quiet" -> "방해 금지"
                                                else -> item.title.orEmpty()
                                            },
                                            fontWeight = if (isBlock) FontWeight.Bold else null,
                                        )
                                    }
                                    if (item.kind == "fixed_block" && item.lead_minutes > 0) {
                                        Text("출발 ${leaveTime(item)}" + (item.location?.let { " · $it" } ?: ""),
                                            style = MaterialTheme.typography.bodySmall)
                                    } else if (item.kind == "fixed_block" && item.location != null) {
                                        Text(item.location, style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (isBlock) item.status?.let { Text(statusLabel(it), style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    menuFixed?.let { item ->
        AlertDialog(
            onDismissRequest = { menuFixed = null },
            title = { Text(item.title.orEmpty()) },
            text = { Text("고정 블록은 바로 수정·삭제돼요. 겹친 계획 블록은 비서가 다시 배치해요.") },
            confirmButton = { TextButton(onClick = { menuFixed = null; onEditFixed(item) }) { Text("수정") } },
            dismissButton = {
                TextButton(onClick = {
                    menuFixed = null
                    val id = item.id ?: return@TextButton
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).deleteFixedBlock(id) } }
                        r.onFailure { error = ApiClient.errorMessage(it) }
                        reload++
                    }
                }) { Text("삭제", color = ERROR) }
            },
        )
    }
    menuPlanned?.let { item ->
        PlannedBlockDialog(item, onDismiss = { menuPlanned = null }, onSent = { menuPlanned = null; reload++ })
    }
}

/** 출발(개입) 시각 = start − lead. */
private fun leaveTime(item: TimelineItem): String = runCatching {
    OffsetDateTime.parse(item.start).minusMinutes(item.lead_minutes.toLong())
        .atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
}.getOrDefault("")

private fun time(iso: String): String = runCatching {
    OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
}.getOrDefault("")

private fun dayLabel(date: String): String = runCatching {
    LocalDate.parse(date).format(DateTimeFormatter.ofPattern("M/d(E)"))
}.getOrDefault(date)

private fun statusLabel(status: String): String = when (status) {
    "planned" -> "예정"
    "nudging" -> "화면 띄우는 중"
    "started" -> "시작함"
    "done" -> "완료"
    "partial" -> "일부"
    "missed" -> "놓침"
    else -> status
}
