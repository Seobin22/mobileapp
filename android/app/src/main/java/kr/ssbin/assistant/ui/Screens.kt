package kr.ssbin.assistant.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.ssbin.assistant.Capabilities
import kr.ssbin.assistant.data.ApiClient
import kr.ssbin.assistant.data.CreateTaskRequest
import kr.ssbin.assistant.data.Prefs
import kr.ssbin.assistant.data.TaskDto
import kr.ssbin.assistant.work.Sync
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val RED = Color(0xFFC92A2A)
private val GREEN = Color(0xFF2B8A3E)

// ------------------------------------------------------------------ 설정(최초 실행)

@Composable
fun SetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val prefs = Prefs.get(context)
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(prefs.serverUrl) }
    var token by remember { mutableStateOf(prefs.apiToken) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("실행 비서 연결", style = MaterialTheme.typography.headlineSmall)
        Text("서버 주소와 API 토큰을 넣으세요. 토큰은 서버의 /root/data/assistant/api_token 값입니다.")
        OutlinedTextField(url, { url = it }, label = { Text("서버 주소") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(token, { token = it }, label = { Text("API 토큰") }, modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = RED) }
        Button(enabled = !busy, onClick = {
            busy = true
            error = null
            prefs.serverUrl = url.trim()
            prefs.apiToken = token.trim()
            prefs.deviceId = -1
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { Sync.register(context.applicationContext) } }
                busy = false
                result.onSuccess { onDone() }.onFailure { error = "연결 실패: " + ApiClient.errorMessage(it) }
            }
        }) { Text(if (busy) "연결 중…" else "저장하고 연결") }
    }
}

// ------------------------------------------------------------------ 메인(할 일·블록 목록)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(tick: Int, navigate: (Screen) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf<List<TaskDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<TaskDto?>(null) }
    var adding by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<TaskDto?>(null) }
    val allGranted = remember(tick) { Capabilities.allGranted(context) }

    LaunchedEffect(tick, reload) {
        val result = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).tasks().tasks } }
        result.onSuccess { tasks = it; error = null }.onFailure { error = ApiClient.errorMessage(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("실행 비서") },
                actions = {
                    TextButton(onClick = { reload++ }) { Text("새로고침") }
                    TextButton(onClick = { navigate(Screen.Permissions) }) { Text("권한") }
                    TextButton(onClick = { navigate(Screen.Settings) }) { Text("설정") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) { Text("추가", Modifier.padding(horizontal = 16.dp)) }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!allGranted) {
                Text(
                    "권한이 빠져 있어 화면이 제때 안 뜰 수 있어요. 눌러서 확인",
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth().background(RED).clickable { navigate(Screen.Permissions) }
                        .padding(12.dp),
                )
            }
            error?.let { Text(it, color = RED, modifier = Modifier.padding(12.dp)) }
            if (tasks.isEmpty() && error == null) {
                Text("오늘부터 7일 안에 할 일이 없어요. [추가]로 등록하세요.", Modifier.padding(16.dp))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(tasks, key = { it.id }) { task ->
                    val block = task.blocks.lastOrNull { it.status != "cancelled" } ?: task.blocks.lastOrNull()
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                            .combinedClickable(onClick = {}, onLongClick = { menuFor = task })
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(task.title, fontWeight = FontWeight.Bold)
                            Text(task.first_action, style = MaterialTheme.typography.bodySmall)
                            if (block != null) {
                                Text("${shortTime(block.start)}–${hm(block.end)} · ${statusText(block.status)}")
                            }
                        }
                    }
                }
            }
        }
    }

    menuFor?.let { task ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(task.title) },
            text = { Text("수정하거나 삭제할 수 있어요.") },
            confirmButton = {
                TextButton(onClick = { editing = task; menuFor = null }, enabled = task.status == "active") {
                    Text("수정")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    menuFor = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).deleteTask(task.id) } }
                        r.onFailure { error = ApiClient.errorMessage(it) }
                        reload++
                    }
                }) { Text("삭제", color = RED) }
            },
        )
    }

    if (adding) {
        TaskDialog(initial = null, onDismiss = { adding = false }, onSaved = { adding = false; reload++ })
    }
    editing?.let { task ->
        TaskDialog(initial = task, onDismiss = { editing = null }, onSaved = { editing = null; reload++ })
    }
}

@Composable
private fun TaskDialog(initial: TaskDto?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val block = initial?.blocks?.lastOrNull { it.status != "cancelled" }
    val startAt = block?.let { OffsetDateTime.parse(it.start).atZoneSameInstant(ZoneId.systemDefault()) }
    val defaultStart = LocalDateTime.now().plusMinutes(2).withSecond(0).withNano(0)
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var firstAction by remember { mutableStateOf(initial?.first_action ?: "") }
    var date by remember { mutableStateOf((startAt?.toLocalDate() ?: defaultStart.toLocalDate()).toString()) }
    var time by remember {
        mutableStateOf((startAt?.toLocalTime() ?: defaultStart.toLocalTime()).format(DateTimeFormatter.ofPattern("HH:mm")))
    }
    var duration by remember {
        mutableStateOf(
            (block?.let { java.time.Duration.between(OffsetDateTime.parse(it.start), OffsetDateTime.parse(it.end)).toMinutes() }
                ?: 30).toString()
        )
    }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "할 일 추가" else "할 일 수정") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("제목") })
                OutlinedTextField(firstAction, { firstAction = it }, label = { Text("첫 행동") })
                OutlinedTextField(date, { date = it }, label = { Text("날짜 (YYYY-MM-DD)") })
                OutlinedTextField(time, { time = it }, label = { Text("시각 (HH:MM)") })
                OutlinedTextField(duration, { duration = it }, label = { Text("길이(분)") })
                error?.let { Text(it, color = RED) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val start = runCatching {
                    LocalDateTime.of(LocalDate.parse(date.trim()), LocalTime.parse(time.trim()))
                        .atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
                }.getOrNull()
                val minutes = duration.trim().toIntOrNull()
                if (start == null || minutes == null) {
                    error = "날짜·시각·길이 형식을 확인하세요"
                    return@TextButton
                }
                scope.launch {
                    val api = ApiClient.get(context)
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            if (initial == null) {
                                api.createTask(CreateTaskRequest(title.trim(), firstAction.trim(), start, minutes))
                            } else {
                                api.patchTask(
                                    initial.id,
                                    JsonObject(
                                        mapOf(
                                            "title" to JsonPrimitive(title.trim()),
                                            "first_action" to JsonPrimitive(firstAction.trim()),
                                            "start" to JsonPrimitive(start),
                                            "duration_min" to JsonPrimitive(minutes),
                                        )
                                    )
                                )
                            }
                        }
                    }
                    // 422 등 검증 실패는 서버 사유를 그대로 보여준다.
                    result.onSuccess { onSaved() }.onFailure { error = ApiClient.errorMessage(it) }
                }
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

// ------------------------------------------------------------------ 권한 점검

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionScreen(tick: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    var localTick by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { localTick++ }
    val caps = remember(tick, localTick) { Capabilities.all(context) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("권한 점검") }, navigationIcon = { TextButton(onClick = onBack) { Text("뒤로") } })
    }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("모두 초록색이어야 정해진 시각에 화면이 확실히 뜹니다.")
            PermissionRow("알림 허용", caps["notifications"] == true) {
                if (Build.VERSION.SDK_INT >= 33) {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    open(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
            }
            PermissionRow("풀스크린 알림", caps["fullscreen_intent"] == true) {
                if (Build.VERSION.SDK_INT >= 34) {
                    open(context, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri(context)))
                }
            }
            PermissionRow("정확한 알람", caps["exact_alarm"] == true) {
                if (Build.VERSION.SDK_INT >= 31) {
                    open(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(context)))
                }
            }
            PermissionRow("배터리 최적화 예외", caps["battery_exempt"] == true) {
                open(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context)))
            }
            OutlinedButton(onClick = { localTick++ }) { Text("다시 확인") }
        }
    }
}

@Composable
private fun PermissionRow(name: String, granted: Boolean, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Text(if (granted) "●" else "●", color = if (granted) GREEN else RED)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.Bold)
            Text(if (granted) "허용됨" else "필요함", color = if (granted) GREEN else RED)
        }
        if (!granted) Button(onClick = onOpen) { Text("설정 열기") }
    }
}

private fun packageUri(context: Context): Uri = Uri.parse("package:" + context.packageName)

private fun open(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

// ------------------------------------------------------------------ 생활 설정(기상·취침·방해 금지)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onReconfigure: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var wake by remember { mutableStateOf("") }
    var sleep by remember { mutableStateOf("") }
    var dnd by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).settings().settings } }
        r.onSuccess { s ->
            wake = s["life.wake"]?.jsonPrimitive?.content ?: ""
            sleep = s["life.sleep"]?.jsonPrimitive?.content ?: ""
            dnd = s["dnd.windows"]?.let { dndToText(it) } ?: ""
        }.onFailure { message = ApiClient.errorMessage(it) }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("설정") }, navigationIcon = { TextButton(onClick = onBack) { Text("뒤로") } })
    }) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(wake, { wake = it }, label = { Text("기상 (HH:MM)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(sleep, { sleep = it }, label = { Text("취침 (HH:MM, 자정 넘김 가능)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                dnd, { dnd = it },
                label = { Text("방해 금지 (한 줄에 하나: 요일 시작-끝)") },
                placeholder = { Text("1,2,3,4,5 13:00-14:00") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("요일은 월=1 … 일=7", style = MaterialTheme.typography.bodySmall)
            message?.let { Text(it) }
            Button(onClick = {
                val windows = runCatching { textToDnd(dnd) }.getOrNull()
                if (windows == null) {
                    message = "방해 금지 형식을 확인하세요. 예: 1,2,3,4,5 13:00-14:00"
                    return@Button
                }
                scope.launch {
                    val api = ApiClient.get(context)
                    val r = withContext(Dispatchers.IO) {
                        runCatching {
                            api.putSetting(setting("life.wake", JsonPrimitive(wake.trim())))
                            api.putSetting(setting("life.sleep", JsonPrimitive(sleep.trim())))
                            api.putSetting(setting("dnd.windows", windows))
                            Sync.runOnce(context.applicationContext)
                        }
                    }
                    message = r.fold({ "저장했습니다" }, { ApiClient.errorMessage(it) })
                }
            }) { Text("저장") }
            Spacer(Modifier.width(1.dp))
            OutlinedButton(onClick = onReconfigure) { Text("서버 주소·토큰 다시 입력") }
        }
    }
}

private fun setting(key: String, value: JsonElement) =
    JsonObject(mapOf("key" to JsonPrimitive(key), "value" to value))

private fun dndToText(value: JsonElement): String = value.jsonArray.joinToString("\n") { w ->
    val o = w.jsonObject
    val days = o["days"]?.jsonArray?.joinToString(",") { it.jsonPrimitive.int.toString() } ?: ""
    "$days ${o["start"]?.jsonPrimitive?.content}-${o["end"]?.jsonPrimitive?.content}"
}

private fun textToDnd(text: String): JsonArray = JsonArray(
    text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
        val (daysPart, range) = line.split(Regex("\\s+"), limit = 2)
        val days = daysPart.split(",").map { it.trim().toInt().also { d -> require(d in 1..7) } }
        val (start, end) = range.split("-").map { LocalTime.parse(it.trim()).format(DateTimeFormatter.ofPattern("HH:mm")) }
        JsonObject(
            mapOf(
                "days" to JsonArray(days.map { JsonPrimitive(it) }),
                "start" to JsonPrimitive(start),
                "end" to JsonPrimitive(end),
            )
        )
    }
)

// ------------------------------------------------------------------ 표시 도우미

private fun shortTime(iso: String): String =
    OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M/d(E) HH:mm"))

private fun hm(iso: String): String =
    OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

private fun statusText(status: String): String = when (status) {
    "planned" -> "예정"
    "nudging" -> "화면 띄우는 중"
    "started" -> "시작함"
    "done" -> "완료"
    "partial" -> "일부"
    "missed" -> "놓침"
    "cancelled" -> "취소"
    else -> status
}
