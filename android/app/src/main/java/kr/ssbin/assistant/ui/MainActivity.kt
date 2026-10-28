package kr.ssbin.assistant.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kr.ssbin.assistant.data.Prefs
import kr.ssbin.assistant.work.Sync

enum class Screen { Setup, Main, Permissions, Settings }

class MainActivity : ComponentActivity() {

    /** onResume 마다 증가. 권한 상태·목록을 다시 읽는 신호. */
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs.get(this)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFFD6336C),
                    background = Color(0xFFFFF4F7),
                    surface = Color(0xFFFFF4F7),
                )
            ) {
                var screen by remember { mutableStateOf(if (prefs.configured) Screen.Main else Screen.Setup) }
                val tick = resumeTick.intValue
                when (screen) {
                    Screen.Setup -> SetupScreen(onDone = {
                        syncNow()
                        screen = Screen.Permissions
                    })
                    Screen.Main -> MainScreen(tick = tick, navigate = { screen = it })
                    Screen.Permissions -> PermissionScreen(tick = tick, onBack = { screen = Screen.Main })
                    Screen.Settings -> SettingsScreen(
                        onBack = { screen = Screen.Main },
                        onReconfigure = { screen = Screen.Setup },
                    )
                }
            }
        }
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
