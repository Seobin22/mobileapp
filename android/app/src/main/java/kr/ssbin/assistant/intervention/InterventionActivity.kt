package kr.ssbin.assistant.intervention

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.ssbin.assistant.chat.Voice
import kr.ssbin.assistant.data.ApiClient
import kr.ssbin.assistant.data.AppDatabase
import kr.ssbin.assistant.data.InterventionEntity
import kr.ssbin.assistant.data.PostMessageRequest
import kr.ssbin.assistant.ui.WorkProductActivity
import kr.ssbin.assistant.ui.goalColor
import java.util.Locale
import java.util.UUID

/**
 * 잠금화면 위에 뜨는 개입 화면(빌드문서 v3.1 §4.3).
 * 위→아래: 이름표·색, 제목, 한 문장 / [시작](크게) / 이 개입에서 오간 대화 / 입력줄(텍스트 + 🎤).
 * 말하면 서버가 대화 중(talking)으로 바꾸고 비서가 이 화면에 답한다(TTS 로 읽음). [시작]은 언제나 동작한다.
 * [시작]이 아닌 방법(뒤로가기·홈·화면 꺼짐)으로 나가면 dismiss 를 보내고 reshow_minutes 뒤 다시 띄운다.
 */
class InterventionActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ID = "intervention_id"
        private val BG = Color(0xFF1F1A1C)
        private val PINK = Color(0xFFD6336C)
    }

    private data class Line(val id: Long, val mine: Boolean, val text: String)

    private val entity = mutableStateOf<InterventionEntity?>(null)
    private val lines = mutableStateListOf<Line>()
    private val input = mutableStateOf("")
    private val thinking = mutableStateOf(false)
    /** v3.2 P3.2: 서버가 정한 진행 문구("일정 짜는 중…"). */
    private val stageText = mutableStateOf("생각하는 중…")
    private val listening = mutableStateOf(false)
    private val error = mutableStateOf<String?>(null)
    private var tts: TextToSpeech? = null
    private var spokenFor: String? = null
    private var handled = false
    private var voice: Voice? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else error.value = "마이크 권한이 필요해요"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
                speakIfNeeded()
            }
        }
        voice = Voice(this,
            resultCallback = { text -> listening.value = false; send(text, "fullscreen_voice") },
            errorCallback = { msg -> listening.value = false; error.value = msg })

        // 키보드가 올라오면 화면을 줄여 입력란이 키보드 위에 보이게 한다(edge-to-edge 라야 imePadding 이 동작).
        enableEdgeToEdge()
        setContent { MaterialTheme { Screen() } }

        load(intent)

        lifecycleScope.launch {
            InterventionState.events.collect { event ->
                val current = entity.value ?: return@collect
                when (event) {
                    is InterventionState.Event.Close -> if (event.blockId == current.blockId) {
                        handled = true
                        finish()
                    }
                    is InterventionState.Event.Replace -> if (event.blockId == current.blockId) {
                        val replaced = withContext(Dispatchers.IO) {
                            AppDatabase.dao(applicationContext).intervention(event.newId)
                        }
                        if (replaced != null) {
                            // 대화 무응답 뒤 다시 왔으면 같은 화면·대화를 유지한 채 알림음만 낸다(§4.3).
                            if (current.status == "talking" || lines.isNotEmpty()) chime()
                            entity.value = replaced
                            InterventionState.visibleId = replaced.id
                        }
                    }
                    is InterventionState.Event.Reply -> if (event.interventionId == current.id) addReply(event.messageId, event.text)
                }
            }
        }
    }

    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    @androidx.compose.runtime.Composable
    private fun Screen() {
        val current = entity.value
        // 키보드가 떠 있으면 위쪽 안내를 줄여 대화와 입력란이 보이게 한다.
        val imeVisible = WindowInsets.isImeVisible
        val listState = rememberLazyListState()
        LaunchedEffect(lines.size, thinking.value) {
            val count = lines.size + if (thinking.value) 1 else 0
            if (count > 0) listState.animateScrollToItem(count - 1)
        }
        Column(
            modifier = Modifier.fillMaxSize().background(BG).systemBarsPadding().imePadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(if (imeVisible) 4.dp else if (lines.isEmpty()) 56.dp else 12.dp))
            if (!current?.goalLabel.isNullOrBlank()) {
                Text(
                    current!!.goalLabel,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(goalColor(current.goalColor), RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
                Spacer(Modifier.height(10.dp))
            }
            Text(
                text = current?.taskTitle ?: "",
                color = Color.White,
                fontSize = if (lines.isEmpty()) 40.sp else 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                lineHeight = if (lines.isEmpty()) 48.sp else 34.sp,
            )
            if (!imeVisible) Spacer(Modifier.height(14.dp))
            if (!imeVisible) Text(
                text = current?.screenText ?: "",
                color = Color(0xFFFFD8E4),
                fontSize = if (lines.isEmpty()) 22.sp else 17.sp,
                textAlign = TextAlign.Center,
                lineHeight = if (lines.isEmpty()) 30.sp else 24.sp,
            )
            // v3.2 §3.3: 고정 블록이면 사용자가 쓴 메모를 그대로 보여준다.
            if (!imeVisible && !current?.memo.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "📝 " + current!!.memo,
                    color = Color(0xFFE9DDE2),
                    fontSize = if (lines.isEmpty()) 17.sp else 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.background(Color(0xFF3A3135), RoundedCornerShape(10.dp)).padding(10.dp),
                )
            }
            Spacer(Modifier.height(if (imeVisible) 8.dp else if (lines.isEmpty()) 40.dp else 16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onStartPressed(false) },
                    enabled = current != null,
                    colors = ButtonDefaults.buttonColors(containerColor = PINK),
                    modifier = Modifier.weight(1f).height(if (imeVisible) 48.dp else 72.dp),
                ) {
                    Text("시작", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { onStartPressed(true) },
                    enabled = current != null,
                    modifier = Modifier.weight(1f).height(if (imeVisible) 48.dp else 72.dp),
                ) {
                    Text("이미 하고 있음", fontSize = if (imeVisible) 15.sp else 18.sp, fontWeight = FontWeight.Bold,
                        color = Color.White, textAlign = TextAlign.Center)
                }
            }
            // 2026-11-10: 알림 전에 이미 끝냈으면 [이미 완료함], 오늘은 못 하면 [지금 할 수 없음](하루 이상 뒤 빈 시간으로)
            if (!imeVisible) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { onDonePressed() },
                        enabled = current != null,
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) {
                        Text("이미 완료함", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    OutlinedButton(
                        onClick = { onCannotPressed() },
                        enabled = current != null,
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) {
                        Text("지금 할 수 없음", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
            // v3.6 §2.3: 적극 개입 시간대에는 [10분만 조용히](그 시간대를 잠깐 기본 동작으로)
            val activeNow = androidx.compose.runtime.remember(current?.id) {
                androidx.compose.runtime.mutableStateOf(WindowGuard.active(this@InterventionActivity) != null)
            }
            if (activeNow.value && !imeVisible) {
                androidx.compose.material3.TextButton(onClick = {
                    ActiveAlarm.pause(applicationContext)
                    activeNow.value = false
                }) { Text("10분만 조용히", color = Color(0xFFBFA8B0)) }
            }
            // v4 §2.4: L4(앱 잠금) 중에는 [비상 해제] — 확인 없이 바로 모두 풀린다.
            val locked = androidx.compose.runtime.remember(current?.id) {
                kr.ssbin.assistant.data.Prefs.get(this@InterventionActivity).lockActive
            }
            if (locked && current != null && !imeVisible) {
                androidx.compose.material3.TextButton(onClick = {
                    InterventionController.onEmergency(applicationContext, current!!.id, current!!.blockId)
                    finish()
                }) { Text("비상 해제", color = Color(0xFFFF8787)) }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
                items(lines, key = { it.id }) { line -> Bubble(line.mine, line.text) }
                if (thinking.value) {
                    item(key = "thinking") { Bubble(false, "··· " + stageText.value) }
                }
            }
            error.value?.let { Text(it, color = Color(0xFFFF8787), fontSize = 13.sp) }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input.value,
                    onValueChange = { input.value = it },
                    placeholder = {
                        Text(if (listening.value) "듣는 중…" else "말로 하거나 적어 보세요", color = Color(0xFFBFA8B0))
                    },
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFBFA8B0), unfocusedBorderColor = Color(0xFF6B5A61),
                    ),
                    enabled = current != null,
                )
                Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = { onMicPressed() }, enabled = current != null && !listening.value) { Text("🎤") }
                Spacer(Modifier.width(4.dp))
                OutlinedButton(
                    onClick = { send(input.value, "fullscreen") },
                    enabled = current != null && input.value.isNotBlank(),
                ) { Text("전송", color = Color.White) }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Bubble(mine: Boolean, text: String) {
        Box(
            Modifier.fillMaxWidth().padding(vertical = 3.dp),
            contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Text(
                text,
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier.widthIn(max = 300.dp)
                    .background(if (mine) PINK else Color(0xFF3A3135), RoundedCornerShape(12.dp))
                    .padding(10.dp),
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        load(intent)
    }

    private fun load(intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return finish()
        handled = false
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                AppDatabase.dao(applicationContext).intervention(id)
                    ?.takeIf { InterventionController.stillActive(applicationContext, it) }
            }
            if (loaded == null) {
                handled = true
                finish()
                return@launch
            }
            val sameIntervention = entity.value?.id == loaded.id
            entity.value = loaded
            InterventionState.visibleId = loaded.id
            InterventionState.visibleBlockId = loaded.blockId
            if (!sameIntervention) {
                lines.clear()
                loadConversation(loaded.id)
            }
            speakIfNeeded()
        }
    }

    /** 이 개입에서 이미 오간 말(서버 messages.intervention_id). */
    private suspend fun loadConversation(interventionId: String) {
        val numeric = interventionId.toLongOrNull() ?: return
        val messages = withContext(Dispatchers.IO) {
            runCatching { ApiClient.get(applicationContext).messages().messages }.getOrDefault(emptyList())
        }
        messages.filter { it.intervention_id == numeric }.forEach { m ->
            if (lines.none { it.id == m.id }) lines.add(Line(m.id, m.role == "user", m.content))
        }
    }

    private fun onMicPressed() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening() {
        error.value = null
        tts?.stop()
        listening.value = true
        voice?.start()
    }

    /** 첫 메시지에서 서버가 talking 으로 바꾼다. 앱도 재표시 타이머를 멈춘다(§4.3). */
    private fun send(raw: String, source: String) {
        val text = raw.trim()
        val current = entity.value ?: return
        if (text.isEmpty()) return
        input.value = ""
        error.value = null
        val localId = -System.nanoTime()
        lines.add(Line(localId, true, text))
        thinking.value = true
        stageText.value = "생각하는 중…"
        val app = applicationContext
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    InterventionController.onUserTalk(app, current)
                    ApiClient.get(app).postMessage(
                        PostMessageRequest(UUID.randomUUID().toString(), text, source,
                            intervention_id = current.id.toLongOrNull())
                    )
                }
            }
            result.onSuccess { r ->
                val i = lines.indexOfFirst { it.id == localId }
                if (i >= 0) lines[i] = Line(r.message_id, true, text)
                r.turn_id?.let { turnId -> launch { pollStage(turnId) } }
                pollReplies(current.id, r.message_id)
            }.onFailure {
                thinking.value = false
                error.value = "보내지 못했어요: " + ApiClient.errorMessage(it)
            }
        }
    }

    /** 답을 기다리는 동안 몇 초마다 확인한다(FCM 이 늦거나 없어도 답이 보이도록). */
    private suspend fun pollReplies(interventionId: String, afterId: Long) {
        val numeric = interventionId.toLongOrNull() ?: run { thinking.value = false; return }
        repeat(30) {
            delay(3_000)
            if (!thinking.value || entity.value?.id != interventionId) return
            val replies = withContext(Dispatchers.IO) {
                runCatching { ApiClient.get(applicationContext).messages(afterId).messages }.getOrDefault(emptyList())
            }.filter { it.role == "assistant" && it.intervention_id == numeric }
            replies.forEach { addReply(it.id, it.content) }
        }
        thinking.value = false
    }

    /** 답을 기다리는 동안 1.5초마다 진행 문구를 갱신한다(P3.2). 20초가 넘으면 오래 걸린다고 붙인다. */
    private suspend fun pollStage(turnId: Long) {
        while (thinking.value) {
            val status = withContext(Dispatchers.IO) {
                runCatching { ApiClient.get(applicationContext).turnStatus(turnId) }.getOrNull()
            }
            if (status != null) {
                when (status.stage) {
                    "failed" -> {
                        thinking.value = false
                        error.value = "처리하지 못했어요. 다시 말해 주세요"
                        return
                    }
                    "done" -> return
                    else -> stageText.value = status.stage_text.ifBlank { "생각하는 중…" } +
                        if (status.elapsed_sec > 20) " (조금 오래 걸리고 있어요)" else ""
                }
            }
            delay(1_500)
        }
    }

    private fun addReply(messageId: Long, text: String) {
        if (text.isBlank() || (messageId > 0 && lines.any { it.id == messageId })) return
        lines.add(Line(if (messageId > 0) messageId else -System.nanoTime(), false, text))
        thinking.value = false
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "reply-$messageId")
    }

    private fun chime() {
        runCatching {
            RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play()
        }
    }

    private fun speakIfNeeded() {
        val current = entity.value ?: return
        val engine = tts ?: return
        // 적극 개입 시간대에는 알림이 이미 비서 목소리로 말했다(겹치지 않게 화면에서는 다시 읽지 않는다)
        if (spokenFor == current.id || lines.isNotEmpty() || WindowGuard.active(this) != null) return
        spokenFor = current.id
        engine.speak(current.ttsText, TextToSpeech.QUEUE_FLUSH, null, "intervention-${current.id}")
    }

    private fun onCannotPressed() {
        val current = entity.value ?: return
        handled = true
        val app = applicationContext
        Thread { InterventionController.onUserAction(app, current, "cannot_now") }.start()
        finish()
    }

    private fun onDonePressed() {
        val current = entity.value ?: return
        handled = true
        val app = applicationContext
        Thread { InterventionController.onUserDone(app, current) }.start()
        finish()
    }

    private fun onStartPressed(already: Boolean) {
        val current = entity.value ?: return
        handled = true
        val app = applicationContext
        Thread { InterventionController.onUserStart(app, current, already) }.start()
        // 준비된 작업물이 있으면 바로 연다
        current.workProductId?.let { startActivity(WorkProductActivity.intent(this, it)) }
        finish()
    }

    override fun onResume() {
        super.onResume()
        // v3.6 §5 자동 음성 대화: 적극 개입 시간대에 개입 화면이 떠 있는 동안(사용자가 설정에서 켰을 때만)
        if (kr.ssbin.assistant.data.Prefs.get(this).activeAutoVoice && WindowGuard.active(this) != null &&
            !kr.ssbin.assistant.voice.VoiceEngine.active &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            kr.ssbin.assistant.voice.VoiceEngine.launch(this)
        }
        entity.value?.let {
            InterventionState.visibleId = it.id
            InterventionState.visibleBlockId = it.blockId
        }
    }

    override fun onStop() {
        super.onStop()
        InterventionState.visibleId = null
        InterventionState.visibleBlockId = null
        val current = entity.value
        if (!handled && !isChangingConfigurations && current != null) {
            handled = true
            val app = applicationContext
            Thread { InterventionController.onUserLeft(app, current) }.start()
            finish()
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        voice?.destroy()
        super.onDestroy()
    }
}
