package kr.ssbin.assistant.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.runtime.collectAsState
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.ssbin.assistant.Capabilities
import kr.ssbin.assistant.data.ApiClient
import kr.ssbin.assistant.data.Prefs
import kr.ssbin.assistant.data.TimelineItem
import kr.ssbin.assistant.work.Sync

enum class Screen { Setup, Home, Permissions, Settings, AboutMe }

class MainActivity : ComponentActivity() {

    companion object {
        /** 개입 화면 [미루기 요청] 뒤 채팅 탭을 연다(v3 §8.1). */
        const val EXTRA_OPEN_CHAT = "open_chat"
        /** v3.6 적극 개입 알람을 눌러 열었다(반응). */
        const val EXTRA_ACTIVE_ACK = "active_ack"
        /** 음성 대화 알림을 눌러 열었다 → 음성 탭. */
        const val EXTRA_OPEN_VOICE = "open_voice"
        /** 알림의 [음성으로 대답]: 음성 탭을 열고 바로 시작(음성 탭 [시작]과 같다). */
        const val EXTRA_START_VOICE = "start_voice"
        val openVoice = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)

        /** 채팅의 [설정 열기] 버튼(v3.6 §1.2) → 설정 화면. */
        val openSettings = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    }

    /** onResume 마다 증가. 권한 상태·목록을 다시 읽는 신호. */
    private val resumeTick = mutableIntStateOf(0)

    /** 채팅 탭으로 가라는 신호. */
    private val openChat = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs.get(this)
        if (intent.getBooleanExtra(EXTRA_OPEN_CHAT, false)) openChat.intValue++
        if (intent.getBooleanExtra(EXTRA_ACTIVE_ACK, false)) kr.ssbin.assistant.intervention.ActiveAlarm.ack(this)
        if (intent.getBooleanExtra(EXTRA_OPEN_VOICE, false)) openVoice.tryEmit(Unit)
        startVoiceIfAsked(intent)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFFD6336C),
                    background = Color(0xFFFFF4F7),
                    surface = Color(0xFFFFF4F7),
                )
            ) {
                var screen by remember { mutableStateOf(if (prefs.configured) Screen.Home else Screen.Setup) }
                val tick = resumeTick.intValue
                val chatSignal = openChat.intValue
                LaunchedEffect(chatSignal) {
                    if (chatSignal > 0 && prefs.configured) screen = Screen.Home
                }
                LaunchedEffect(Unit) { openSettings.collect { if (prefs.configured) screen = Screen.Settings } }
                when (screen) {
                    Screen.Setup -> SetupScreen(onDone = {
                        syncNow()
                        screen = Screen.Permissions
                    })
                    Screen.Home -> HomeScreen(tick = tick, openChat = chatSignal, navigate = { screen = it })
                    Screen.Permissions -> PermissionScreen(tick = tick, onBack = { screen = Screen.Home })
                    Screen.AboutMe -> AboutMeScreen(onBack = { screen = Screen.Home })
                    Screen.Settings -> SettingsScreen(
                        onBack = { screen = Screen.Home },
                        onReconfigure = { screen = Screen.Setup },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_CHAT, false)) openChat.intValue++
        if (intent.getBooleanExtra(EXTRA_ACTIVE_ACK, false)) kr.ssbin.assistant.intervention.ActiveAlarm.ack(this)
        if (intent.getBooleanExtra(EXTRA_OPEN_VOICE, false)) openVoice.tryEmit(Unit)
        startVoiceIfAsked(intent)
    }

    private fun startVoiceIfAsked(intent: android.content.Intent) {
        if (!intent.getBooleanExtra(EXTRA_START_VOICE, false)) return
        intent.removeExtra(EXTRA_START_VOICE)
        openVoice.tryEmit(Unit)
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED && !kr.ssbin.assistant.voice.VoiceEngine.active
        ) {
            kr.ssbin.assistant.voice.VoiceEngine.launch(this) // 앱이 앞에 있으니 마이크를 켤 수 있다
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
        syncNow()
    }

    override fun onStop() {
        super.onStop()
        if (Prefs.get(this).configured) Sync.runAfterLeaving(applicationContext)
    }

    private fun syncNow() {
        val app = applicationContext
        if (!Prefs.get(app).configured) return
        Sync.schedulePeriodic(app)
        lifecycleScope.launch(Dispatchers.IO) { Sync.runOnce(app) }
    }
}

/** 채팅(메인) · 달력 · 목표 세 탭(v3.2 §8.1, 시간표 탭을 달력으로 바꿈). 달력의 [+]·길게 누르기는 고정 일정 만들기. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(tick: Int, openChat: Int = 0, navigate: (Screen) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var localTick by remember { mutableIntStateOf(0) }
    val allGranted = remember(tick) { Capabilities.allGranted(context) }
    LaunchedEffect(openChat) { if (openChat > 0) tab = 0 }
    LaunchedEffect(Unit) { MainActivity.openVoice.collect { tab = 4; MainActivity.openVoice.resetReplayCache() } }
    // 앱 안 업데이트: 화면에 돌아올 때마다 서버의 최신 버전을 확인한다.
    var update by remember { mutableStateOf<kr.ssbin.assistant.data.AppLatestDto?>(null) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tick) {
        update = withContext(Dispatchers.IO) { kr.ssbin.assistant.work.Updater.check(context.applicationContext) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(when (tab) { 0 -> "실행 비서"; 1 -> "달력"; 2 -> "목표"; 4 -> "음성"; else -> "장소" }) },
                actions = {
                    TextButton(onClick = { navigate(Screen.AboutMe) }) { Text("나") }
                    TextButton(onClick = { navigate(Screen.Permissions) }) { Text("권한") }
                    TextButton(onClick = { navigate(Screen.Settings) }) { Text("설정") }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("💬") }, label = { Text("채팅") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("📅") }, label = { Text("달력") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Text("🎯") }, label = { Text("목표") })
                NavigationBarItem(selected = tab == 4, onClick = { tab = 4 }, icon = { Text("🎤") }, label = { Text("음성") })
                NavigationBarItem(selected = tab == 3, onClick = { tab = 3 }, icon = { Text("📍") }, label = { Text("장소") })
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // 2026-11-10: 음성 대화 중이면 어느 탭에서든 맨 위에 표시(누르면 음성 탭, [종료]로 바로 끝)
            val voicePhase by kr.ssbin.assistant.voice.VoiceEngine.phase.collectAsState()
            if (voicePhase != kr.ssbin.assistant.voice.VoiceEngine.Phase.Idle) {
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFFE03131)).clickable { tab = 4 }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("● REC  음성 대화 중", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { kr.ssbin.assistant.voice.VoiceEngine.stop(context, "user") }) {
                        Text("종료", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (!allGranted) {
                Text(
                    "권한이 빠져 있어 화면이 제때 안 뜰 수 있어요. 눌러서 확인",
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFC92A2A))
                        .clickable { navigate(Screen.Permissions) }.padding(12.dp),
                )
            }
            update?.let { u ->
                Text(
                    updateStatus ?: "새 버전 ${u.version_name}이 있어요 · 눌러서 업데이트",
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF1C7ED6)).clickable {
                        if (updateStatus != null) return@clickable
                        val app = context.applicationContext
                        if (!kr.ssbin.assistant.work.Updater.canInstall(app)) {
                            updateStatus = null
                            kr.ssbin.assistant.work.Updater.openInstallPermission(context)
                            return@clickable
                        }
                        updateStatus = "받는 중… 0%"
                        scope.launch {
                            val file = withContext(Dispatchers.IO) {
                                runCatching {
                                    kr.ssbin.assistant.work.Updater.download(app) { pct ->
                                        scope.launch { updateStatus = "받는 중… $pct%" }
                                    }
                                }
                            }
                            file.onSuccess {
                                updateStatus = null
                                kr.ssbin.assistant.work.Updater.install(context, it)
                            }.onFailure { updateStatus = "받지 못했어요(" + ApiClient.errorMessage(it) + ") · 다시 눌러 주세요"; kotlinx.coroutines.delay(3000); updateStatus = null }
                        }
                    }.padding(12.dp),
                )
            }
            when (tab) {
                0 -> ChatScreen(tick = tick + localTick)
                1 -> CalendarScreen(tick = tick + localTick)
                2 -> GoalsScreen(tick = tick + localTick)
                4 -> VoiceScreen()
                else -> PlacesScreen(tick = tick + localTick)
            }
        }
    }
}
