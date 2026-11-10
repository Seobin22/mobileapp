package kr.ssbin.assistant.ui

import android.Manifest
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.ssbin.assistant.data.ApiClient
import kr.ssbin.assistant.data.ConfirmRequest
import kr.ssbin.assistant.data.Prefs
import kr.ssbin.assistant.intervention.ActiveAlarm
import kr.ssbin.assistant.intervention.WindowGuard
import kr.ssbin.assistant.voice.VoiceEngine

/** 음성 탭(빌드문서 v3.6 §4.1). [시작] → 직접 끝낼 때까지 짧게 주고받는다. 대화 글은 채팅 탭에도 🎤 로 남는다. */
@Composable
fun VoiceScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val phase by VoiceEngine.phase.collectAsState()
    val lines by VoiceEngine.lines.collectAsState()
    var hint by remember { mutableStateOf(!Prefs.get(context).voiceHeadsetHintShown) }
    val decided = remember { mutableStateMapOf<String, String>() }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) VoiceEngine.launch(context)
    }
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex) }

    Column(modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (phase == VoiceEngine.Phase.Idle) {
            if (hint) {
                Text("이어폰을 쓰면 비서 목소리가 마이크로 다시 들어가지 않아 더 잘 알아들어요.",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                TextButton(onClick = { Prefs.get(context).voiceHeadsetHintShown = true; hint = false }) { Text("알겠어요") }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 23 && androidx.core.content.ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) mic.launch(Manifest.permission.RECORD_AUDIO) else VoiceEngine.launch(context)
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD6336C)),
                    modifier = Modifier.size(180.dp),
                ) { Text("시작", fontSize = 34.sp, fontWeight = FontWeight.Bold) }
            }
            Text("끝낼 때는 [종료], 상단 알림의 [종료], 볼륨 버튼 길게(1초), 이어폰 버튼.",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            return@Column
        }
        Text(
            when (phase) {
                VoiceEngine.Phase.Listening -> "🎤 듣는 중"
                VoiceEngine.Phase.Thinking -> "··· 생각 중"
                VoiceEngine.Phase.Speaking -> "🔊 말하는 중"
                VoiceEngine.Phase.PausedForCall -> "📞 통화 중이라 잠깐 멈춤"
                else -> ""
            },
            fontSize = 20.sp, fontWeight = FontWeight.Bold,
        )
        // 마이크 소리 크기: 말할 때 막대가 분홍 선(기준)을 넘어야 '말'로 알아듣는다
        val meter by VoiceEngine.meter.collectAsState()
        Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Box(Modifier.fillMaxWidth().height(10.dp).background(Color(0xFFE9ECEF), RoundedCornerShape(5.dp))) {
                Box(Modifier.fillMaxWidth(meter.level).height(10.dp)
                    .background(if (meter.level >= meter.threshold) Color(0xFF2F9E44) else Color(0xFFADB5BD), RoundedCornerShape(5.dp)))
                Box(Modifier.fillMaxWidth(meter.threshold.coerceAtLeast(0.01f)).height(10.dp), contentAlignment = Alignment.CenterEnd) {
                    Box(Modifier.size(width = 2.dp, height = 10.dp).background(Color(0xFFD6336C)))
                }
            }
            Text(meter.mic + " · 보낸 말 " + meter.sent + "개 · 말할 때 막대가 분홍 선을 넘어야 알아들어요",
                style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), state = listState) {
            items(lines, key = { it.key }) { line ->
                when (line.role) {
                    "user", "assistant" -> Box(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        contentAlignment = if (line.role == "user") Alignment.CenterEnd else Alignment.CenterStart) {
                        Text(line.text, color = if (line.role == "user") Color.White else Color.Unspecified,
                            modifier = Modifier.widthIn(max = 300.dp)
                                .background(if (line.role == "user") Color(0xFFD6336C) else Color.White, RoundedCornerShape(12.dp))
                                .padding(10.dp))
                    }
                    "saved" -> Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(line.text, style = MaterialTheme.typography.labelMedium, color = Color(0xFF495057),
                            modifier = Modifier.background(Color(0xFFE9ECEF), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
                        val card = line.undoCard
                        if (card != null && decided[line.key] == null) {
                            TextButton(onClick = {
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).undoVoiceCard(card) } }
                                    decided[line.key] = if (r.isSuccess) "되돌렸어요" else ApiClient.errorMessage(r.exceptionOrNull()!!)
                                }
                            }) { Text("되돌리기") }
                        }
                        decided[line.key]?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    }
                    "card" -> Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .background(Color(0xFFFFF3BF), RoundedCornerShape(10.dp)).padding(10.dp)) {
                        Text("확인 카드: " + line.text, fontWeight = FontWeight.SemiBold)
                        val id = line.confirmationId
                        val done = decided[line.key]
                        if (id != null && done == null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    scope.launch {
                                        val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).confirm(id, ConfirmRequest(true)) } }
                                        decided[line.key] = if (r.isSuccess) "수락함" else ApiClient.errorMessage(r.exceptionOrNull()!!)
                                    }
                                }) { Text("예") }
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        val r = withContext(Dispatchers.IO) { runCatching { ApiClient.get(context).confirm(id, ConfirmRequest(false)) } }
                                        decided[line.key] = if (r.isSuccess) "거절함" else ApiClient.errorMessage(r.exceptionOrNull()!!)
                                    }
                                }) { Text("아니오") }
                            }
                        } else if (done != null) {
                            Text(done, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    else -> Text(line.text, color = Color(0xFFC92A2A), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (WindowGuard.active(context) != null) {
            TextButton(onClick = { ActiveAlarm.pause(context) }) { Text("10분만 조용히") }
        }
        // 비서가 말하는 동안은 마이크로 끊지 않는다. 끊고 싶으면 이 버튼.
        if (phase == VoiceEngine.Phase.Speaking || phase == VoiceEngine.Phase.Thinking) {
            OutlinedButton(onClick = { VoiceEngine.stopSpeaking() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (phase == VoiceEngine.Phase.Speaking) "그만 말하기" else "그만 생각하기", fontSize = 18.sp)
            }
        }
        Button(
            onClick = { VoiceEngine.stop(context, "user") },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF495057)),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("종료", fontSize = 20.sp) }
    }
}

/** 설정 → 음성(§4.4): 목소리(미리 듣기)·말 속도·말 끝 침묵·STT 방식·음성 모델, 적극 시간대 자동 음성 대화(§5). */
@Composable
fun VoiceSettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var voices by remember { mutableStateOf<List<Triple<String, String, String>>>(emptyList()) }
    var voiceId by remember { mutableStateOf("") }
    var speed by remember { mutableStateOf(1.0f) }
    var silence by remember { mutableStateOf(800f) }
    var mode by remember { mutableStateOf("batch") }
    var model by remember { mutableStateOf("") }
    var autoVoice by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<MediaPlayer?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { ApiClient.get(context).settings().settings }.onSuccess { s ->
                voiceId = s["voice.voice_id"]?.jsonPrimitive?.content.orEmpty()
                speed = s["voice.speed"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 1f
                silence = s["voice.end_silence_ms"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 800f
                mode = s["voice.stt_mode"]?.jsonPrimitive?.content ?: "batch"
                model = s["voice.model"]?.jsonPrimitive?.content.orEmpty()
                autoVoice = s["active.auto_voice"]?.jsonPrimitive?.content == "true"
            }
            runCatching { ApiClient.get(context).voices().voices }.onSuccess { list ->
                voices = list.map { Triple(it.id, it.name, it.preview_url) }
            }.onFailure { message = "목소리 목록을 못 불러왔어요(" + ApiClient.errorMessage(it) + ")" }
        }
    }

    fun put(key: String, value: kotlinx.serialization.json.JsonElement) {
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { ApiClient.get(context).putSetting(buildJsonObject { put("key", JsonPrimitive(key)); put("value", value) }) }
            }
            message = r.fold({ "저장했습니다" }, { ApiClient.errorMessage(it) })
            if (key == "voice.end_silence_ms") Prefs.get(context).voiceEndSilenceMs = silence.toInt()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("음성", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("목소리", fontWeight = FontWeight.SemiBold)
        if (voices.isEmpty()) Text("관리 화면 '음성 연결'에 ElevenLabs 키를 넣으면 목록이 보여요.", style = MaterialTheme.typography.bodySmall)
        voices.forEach { (id, name, url) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = id == voiceId || (voiceId.isBlank() && id == voices.first().first), onClick = {
                    voiceId = id
                    put("voice.voice_id", JsonPrimitive(id))
                }, label = { Text(name) })
                if (url.isNotBlank()) TextButton(onClick = {
                    preview?.release()
                    preview = MediaPlayer().apply {
                        setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                        runCatching { setDataSource(url); setOnPreparedListener { it.start() }; prepareAsync() }
                    }
                }) { Text("미리 듣기") }
            }
        }
        Text("말 속도 %.2f".format(speed))
        Slider(value = speed, onValueChange = { speed = (it * 20).toInt() / 20f }, valueRange = 0.7f..1.2f,
            onValueChangeFinished = { put("voice.speed", JsonPrimitive(speed.toDouble())) })
        // 2026-11-10: 음성 알림 끄기(적극 개입 시간대 목소리보다 우선). 깨우기 알람 소리·진동은 그대로.
        var mute by remember { mutableStateOf(Prefs.get(context).voiceMuteUntil) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("음성 알림")
            val now = System.currentTimeMillis()
            val endOfDay = java.time.LocalDate.now().plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            listOf(0L to "켜기", endOfDay to "오늘만 끄기", Long.MAX_VALUE to "끄기").forEach { (v, label) ->
                val selected = when (v) {
                    0L -> mute <= now
                    Long.MAX_VALUE -> mute == Long.MAX_VALUE
                    else -> mute in (now + 1) until Long.MAX_VALUE
                }
                FilterChip(selected = selected, onClick = { mute = v; Prefs.get(context).voiceMuteUntil = v }, label = { Text(label) })
            }
        }
        Text("끄면 적극 개입 시간대에도 비서가 소리로 말하지 않고 알림으로만 보내요(깨우기 알람 소리는 울려요).",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        var sens by remember { mutableStateOf(Prefs.get(context).voiceSensitivity) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("마이크 민감도")
            listOf(0.8f to "낮게", 1.3f to "보통", 2.0f to "높게").forEach { (v, label) ->
                FilterChip(selected = kotlin.math.abs(sens - v) < 0.05f, onClick = {
                    sens = v
                    Prefs.get(context).voiceSensitivity = v
                }, label = { Text(label) })
            }
        }
        Text("작게 말해도 못 알아들으면 '높게', 주변 소리에 자꾸 반응하면 '낮게'.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Text("말 끝 침묵 ${silence.toInt()}ms (짧으면 빨리 답하고, 길면 말 중간에 안 끊겨요)")
        Slider(value = silence, onValueChange = { silence = (it / 50).toInt() * 50f }, valueRange = 500f..2000f,
            onValueChangeFinished = { put("voice.end_silence_ms", JsonPrimitive(silence.toInt())) })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("받아쓰기")
            listOf("batch" to "한 덩어리씩", "realtime" to "실시간").forEach { (k, label) ->
                FilterChip(selected = mode == k, onClick = { mode = k; put("voice.stt_mode", JsonPrimitive(k)) }, label = { Text(label) })
            }
        }
        if (mode == "realtime") Text("실시간 받아쓰기는 아직 '한 덩어리씩'과 같게 동작해요.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        OutlinedTextField(model, { model = it }, label = { Text("음성 모델(비우면 Haiku 계열)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { put("voice.model", JsonPrimitive(model.trim())) }) { Text("모델 저장") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("적극 개입 시간에 자동 음성 대화", fontWeight = FontWeight.SemiBold)
                Text("개입 화면이 떠 있는 동안 음성 대화를 자동으로 시작해요.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Switch(checked = autoVoice, onCheckedChange = { autoVoice = it; put("active.auto_voice", JsonPrimitive(it)) })
        }
        Text("전원(측면) 버튼은 다른 앱이 쓸 수 없어서 음성 대화 끝내기에 쓰지 않아요.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
