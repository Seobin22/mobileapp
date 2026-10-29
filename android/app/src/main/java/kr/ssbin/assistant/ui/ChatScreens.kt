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
import kr.ssbin.assistant.work.Sync
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val PINK = Color(0xFFD6336C)
private val ERROR = Color(0xFFC92A2A)
private val WORK_PRODUCT_LINK = Regex("작업물\\s?#(\\d+)")

// ------------------------------------------------------------------ 채팅(메인 탭)

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
            r.onSuccess { input = ""; reload++ }.onFailure { error = ApiClient.errorMessage(it) }
        }
    }

    // 음성: 인식된 텍스트를 바로 보낸다(source=voice)
    val voice = remember {
        Voice(context, resultCallback = { text -> listening = false; send(text, "voice") },
            errorCallback = { msg -> listening = false; error = msg })
    }
    DisposableEffect(Unit) {
        ChatVisibility.visible = true
        Chat.clearNotification(context)
        onDispose {
            ChatVisibility.visible = false
            voice.destroy()
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
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    // 미루기 협상 중 배너(v3 §8.1). 남은 분은 15초마다 다시 계산한다.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }
    val negotiationUntil = remember(now, reload) { Prefs.get(context).negotiationUntil }

    Column(modifier.fillMaxSize()) {
        if (negotiationUntil > now) {
            val left = ((negotiationUntil - now + 59_999) / 60_000).coerceAtLeast(1)
            Text(
                "협상 중 · 답이 없으면 ${left}분 뒤 다시 알려요",
                color = Color.White,
                modifier = Modifier.fillMaxWidth().background(PINK).padding(10.dp),
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp), state = listState) {
            if (messages.isEmpty()) {
                item { Text("말을 걸어 보세요. 예: \"금요일까지 보고서 써야 해\"", Modifier.padding(16.dp)) }
            }
            items(messages, key = { it.id }) { m ->
                MessageBubble(m,
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
            Button(onClick = { send(input, "chat") }, enabled = !sending && input.isNotBlank()) { Text("전송") }
        }
    }
}

@Composable
private fun MessageBubble(m: MessageDto, onOption: (String) -> Unit, onConfirm: (Boolean) -> Unit) {
    val mine = m.role == "user"
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 300.dp)
                .background(if (mine) PINK else Color.White, RoundedCornerShape(12.dp))
                .padding(10.dp)
        ) {
            Text(m.content, color = if (mine) Color.White else Color.Unspecified)
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
            Text(time(m.created_at) + if (m.silent) " · 🔕" else "", style = MaterialTheme.typography.labelSmall,
                color = if (mine) Color(0xFFFFE0EA) else Color.Gray)
        }
    }
}

// ------------------------------------------------------------------ 시간표 탭

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TimelineScreen(tick: Int, modifier: Modifier = Modifier, onAdd: () -> Unit, onEditTask: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var days by remember { mutableStateOf<List<TimelineDay>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var menuTask by remember { mutableStateOf<Pair<Long, String>?>(null) }

    LaunchedEffect(Unit) { Chat.refresh.collect { reload++ } }
    LaunchedEffect(tick, reload) {
        val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).timeline().days } }
        r.onSuccess { days = it; error = null }.onFailure { error = ApiClient.errorMessage(it) }
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { reload++ }) { Text("새로고침") }
            Button(onClick = onAdd) { Text("+ 직접 추가") }
        }
        error?.let { Text(it, color = ERROR, modifier = Modifier.padding(12.dp)) }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            items(days, key = { it.date }) { day ->
                Text(dayLabel(day.date), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                val items = day.items.filter { it.kind != "free" }
                if (items.isEmpty()) Text("일정 없음", style = MaterialTheme.typography.bodySmall)
                items.forEach { item ->
                    val label = when (item.kind) {
                        "event" -> "일정"
                        "block" -> "할 일"
                        "quiet" -> "방해 금지"
                        "meal" -> "식사"
                        else -> item.kind
                    }
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).combinedClickable(
                            onClick = {},
                            onLongClick = {
                                if (item.kind == "block" && item.task_id != null) menuTask = item.task_id to (item.title ?: "")
                            },
                        )
                    ) {
                        Row(Modifier.padding(10.dp)) {
                            Text("${time(item.start)}–${time(item.end)}", modifier = Modifier.widthIn(min = 96.dp))
                            Column {
                                Text("$label ${item.title.orEmpty()}", fontWeight = if (item.kind == "block") FontWeight.Bold else null)
                                item.status?.let { Text(statusLabel(it), style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        }
    }

    menuTask?.let { (taskId, title) ->
        AlertDialog(
            onDismissRequest = { menuTask = null },
            title = { Text(title) },
            text = { Text("이 할 일을 수정하거나 삭제할 수 있어요.") },
            confirmButton = { TextButton(onClick = { menuTask = null; onEditTask(taskId) }) { Text("수정") } },
            dismissButton = {
                TextButton(onClick = {
                    menuTask = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).deleteTask(taskId) } }
                        r.onFailure { error = ApiClient.errorMessage(it) }
                        reload++
                    }
                }) { Text("삭제", color = ERROR) }
            },
        )
    }
}

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
