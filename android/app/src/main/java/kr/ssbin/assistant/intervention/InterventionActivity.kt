package kr.ssbin.assistant.intervention

import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.ssbin.assistant.data.AppDatabase
import kr.ssbin.assistant.data.InterventionEntity
import java.util.Locale

/**
 * 잠금화면 위에 뜨는 개입 화면. 큰 제목, 한 문장, [시작] 버튼 하나(v1 에는 미루기 없음).
 * [시작]이 아닌 방법(뒤로가기·홈·화면 꺼짐)으로 나가면 dismiss 를 보내고 reshow_minutes 뒤 다시 띄운다.
 */
class InterventionActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ID = "intervention_id"
    }

    private val entity = mutableStateOf<InterventionEntity?>(null)
    private var tts: TextToSpeech? = null
    private var spokenFor: String? = null
    private var handled = false

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

        setContent {
            MaterialTheme {
                val current = entity.value
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF1F1A1C))
                        .padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = current?.taskTitle ?: "",
                        color = Color.White,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        lineHeight = 48.sp,
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = current?.screenText ?: "",
                        color = Color(0xFFFFD8E4),
                        fontSize = 22.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 30.sp,
                    )
                    Spacer(Modifier.height(56.dp))
                    Button(
                        onClick = { onStartPressed() },
                        enabled = current != null,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD6336C)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp),
                    ) {
                        Text("시작", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

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
                            entity.value = replaced
                            InterventionState.visibleId = replaced.id
                        }
                    }
                }
            }
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
            entity.value = loaded
            InterventionState.visibleId = loaded.id
            InterventionState.visibleBlockId = loaded.blockId
            speakIfNeeded()
        }
    }

    private fun speakIfNeeded() {
        val current = entity.value ?: return
        val engine = tts ?: return
        if (spokenFor == current.id) return
        spokenFor = current.id
        engine.speak(current.ttsText, TextToSpeech.QUEUE_FLUSH, null, "intervention-${current.id}")
    }

    private fun onStartPressed() {
        val current = entity.value ?: return
        handled = true
        val app = applicationContext
        Thread { InterventionController.onUserStart(app, current) }.start()
        finish()
    }

    override fun onResume() {
        super.onResume()
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
        super.onDestroy()
    }
}
