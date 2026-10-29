package kr.ssbin.assistant.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.fillMaxSize as glanceFillMaxSize
import androidx.glance.layout.Box as GlanceBox
import androidx.glance.layout.Alignment as GlanceAlignment
import androidx.glance.text.FontWeight
import androidx.glance.text.Text as GlanceText
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.ssbin.assistant.data.ApiClient
import kr.ssbin.assistant.data.Prefs

/** 홈 화면 위젯(Glance): 버튼 하나 → 빠른 입력 화면. */
class QuickInputWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceBox(
                modifier = GlanceModifier.glanceFillMaxSize()
                    .background(Color(0xFFD6336C))
                    .clickable(actionStartActivity<QuickInputActivity>()),
                contentAlignment = GlanceAlignment.Center,
            ) {
                GlanceText(
                    "비서에게 말하기",
                    style = TextStyle(color = ColorProvider(Color.White), fontWeight = FontWeight.Bold),
                )
            }
        }
    }
}

class QuickInputWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickInputWidget()
}

/** 투명 배경의 빠른 입력(입력창 + 마이크). 보내면(source=widget) 닫힌다. */
class QuickInputActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Prefs.get(this).configured) {
            Toast.makeText(this, "먼저 앱에서 서버를 연결해 주세요", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFFD6336C))) {
                var text by remember { mutableStateOf("") }
                var busy by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }

                fun send(value: String) {
                    if (value.isBlank() || busy) return
                    busy = true
                    lifecycleScope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching { Chat.send(applicationContext, value.trim(), "widget") }
                        }
                        busy = false
                        r.onSuccess {
                            Toast.makeText(this@QuickInputActivity, "보냈어요", Toast.LENGTH_SHORT).show()
                            finish()
                        }.onFailure { error = ApiClient.errorMessage(it) }
                    }
                }

                val voice = remember {
                    Voice(this@QuickInputActivity, resultCallback = { send(it) }, errorCallback = { error = it })
                }
                DisposableEffect(Unit) { onDispose { voice.destroy() } }
                val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    if (it) voice.start() else error = "마이크 권한이 필요해요"
                }

                Box(
                    Modifier.fillMaxSize().background(Color(0x66000000)).clickable { finish() },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(12.dp)
                            .background(Color.White, RoundedCornerShape(16.dp))
                            .clickable(enabled = false) {}
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("비서에게 말하기", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("예: 내일 3시 치과") })
                        error?.let { Text(it, color = Color(0xFFC92A2A)) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                if (ContextCompat.checkSelfPermission(this@QuickInputActivity, Manifest.permission.RECORD_AUDIO)
                                    == PackageManager.PERMISSION_GRANTED
                                ) voice.start() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }, enabled = !busy) { Text("🎤 말하기") }
                            Button(onClick = { send(text) }, enabled = !busy && text.isNotBlank()) {
                                Text(if (busy) "보내는 중…" else "보내기")
                            }
                        }
                    }
                }
            }
        }
    }
}
