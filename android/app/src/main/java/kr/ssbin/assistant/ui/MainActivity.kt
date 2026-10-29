package kr.ssbin.assistant.ui

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
import kr.ssbin.assistant.data.TaskDto
import kr.ssbin.assistant.work.Sync

enum class Screen { Setup, Home, Permissions, Settings }

class MainActivity : ComponentActivity() {

    companion object {
        /** 개입 화면 [미루기 요청] 뒤 채팅 탭을 연다(v3 §8.1). */
        const val EXTRA_OPEN_CHAT = "open_chat"
    }

    /** onResume 마다 증가. 권한 상태·목록을 다시 읽는 신호. */
    private val resumeTick = mutableIntStateOf(0)

    /** 채팅 탭으로 가라는 신호. */
    private val openChat = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs.get(this)
        if (intent.getBooleanExtra(EXTRA_OPEN_CHAT, false)) openChat.intValue++
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
                when (screen) {
                    Screen.Setup -> SetupScreen(onDone = {
                        syncNow()
                        screen = Screen.Permissions
                    })
                    Screen.Home -> HomeScreen(tick = tick, openChat = chatSignal, navigate = { screen = it })
                    Screen.Permissions -> PermissionScreen(tick = tick, onBack = { screen = Screen.Home })
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
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
        syncNow()
    }

    private fun syncNow() {
        val app = applicationContext
        if (!Prefs.get(app).configured) return
        Sync.schedulePeriodic(app)
        lifecycleScope.launch(Dispatchers.IO) { Sync.runOnce(app) }
    }
}

/** 채팅(메인) · 시간표 두 탭. 시간표의 [+] 는 v1 수동 추가 화면. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(tick: Int, openChat: Int = 0, navigate: (Screen) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TaskDto?>(null) }
    var localTick by remember { mutableIntStateOf(0) }
    val allGranted = remember(tick) { Capabilities.allGranted(context) }
    LaunchedEffect(openChat) { if (openChat > 0) tab = 0 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (tab == 0) "실행 비서" else "시간표") },
                actions = {
                    TextButton(onClick = { navigate(Screen.Permissions) }) { Text("권한") }
                    TextButton(onClick = { navigate(Screen.Settings) }) { Text("설정") }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("💬") }, label = { Text("채팅") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("📅") }, label = { Text("시간표") })
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!allGranted) {
                Text(
                    "권한이 빠져 있어 화면이 제때 안 뜰 수 있어요. 눌러서 확인",
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFC92A2A))
                        .clickable { navigate(Screen.Permissions) }.padding(12.dp),
                )
            }
            if (tab == 0) {
                ChatScreen(tick = tick + localTick)
            } else {
                TimelineScreen(
                    tick = tick + localTick,
                    onAdd = { adding = true },
                    onEditTask = { taskId ->
                        scope.launch {
                            val task = withContext(Dispatchers.IO) {
                                runCatching { ApiClient.get(context).tasks().tasks.firstOrNull { it.id == taskId } }.getOrNull()
                            }
                            editing = task
                        }
                    },
                )
            }
        }
    }

    if (adding) {
        TaskDialog(initial = null, onDismiss = { adding = false }, onSaved = { adding = false; localTick++ })
    }
    editing?.let { task ->
        TaskDialog(initial = task, onDismiss = { editing = null }, onSaved = { editing = null; localTick++ })
    }
}
