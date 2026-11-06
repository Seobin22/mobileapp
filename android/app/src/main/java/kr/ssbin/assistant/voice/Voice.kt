package kr.ssbin.assistant.voice

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.VolumeProvider
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kr.ssbin.assistant.R
import kr.ssbin.assistant.chat.Chat
import kr.ssbin.assistant.data.AppJson
import kr.ssbin.assistant.data.Prefs
import kr.ssbin.assistant.intervention.WindowGuard
import kr.ssbin.assistant.ui.MainActivity
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 음성 탭 엔진(빌드문서 v3.6 §4). 폰 안에서 말소리를 감지해(에너지 기반 VAD) 말이 있을 때만 서버로 보낸다.
 * 서버 응답(NDJSON)을 받아 글은 화면에, 문장별 mp3 는 차례로 재생한다. 비서가 말하는 중에 말소리가 나면 바로 멈춘다(끊고 들어오기).
 * 사용자가 끝낼 때까지 계속한다(조용하다고 끝내지 않는다).
 */
object VoiceEngine {

    enum class Phase { Idle, Listening, Thinking, Speaking, PausedForCall }

    data class Line(
        val key: String,
        val role: String, // user | assistant | saved | card | error
        val text: String,
        val messageId: Long? = null,
        val confirmationId: Long? = null,
        val undoCard: Long? = null,
    )

    private const val RATE = 16000
    private const val FRAME = 320 // 20ms
    private const val PREROLL_FRAMES = 15
    private const val MAX_UTTERANCE_MS = 30_000

    private val _phase = MutableStateFlow(Phase.Idle)
    val phase: StateFlow<Phase> = _phase
    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines

    /** 화면 표시용: 마이크 소리 크기(0~1), 말로 보는 기준(0~1), 보낸 말 수, 마이크 상태 글. */
    data class Meter(val level: Float = 0f, val threshold: Float = 0f, val sent: Int = 0, val mic: String = "")

    private val _meter = MutableStateFlow(Meter())
    val meter: StateFlow<Meter> = _meter
    private var sentCount = 0

    /** rms → 0~1 (로그 눈금, 30~8000). */
    private fun scale(rms: Double): Float =
        (Math.log10(rms.coerceAtLeast(30.0) / 30.0) / Math.log10(8000.0 / 30.0)).toFloat().coerceIn(0f, 1f)

    @Volatile
    var sessionId: Long? = null
        private set

    @Volatile
    private var running = false
    private var recordThread: Thread? = null
    private val net = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context

    @Volatile
    private var currentCall: Call? = null

    @Volatile
    private var streamDone = true

    // 재생
    private data class Clip(val messageId: Long, val text: String, val file: File)

    private val queue = ArrayDeque<Clip>()
    private var player: MediaPlayer? = null

    @Volatile
    private var currentMessage: Long? = null
    private val spoken = StringBuilder()

    val active: Boolean get() = running

    private fun http(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun url(path: String): String = Prefs.get(app).serverUrl.trimEnd('/') + "/" + path

    private fun request(path: String): Request.Builder =
        Request.Builder().url(url(path)).header("Authorization", "Bearer " + Prefs.get(app).apiToken)

    private fun postJson(path: String, body: JsonObject = JsonObject(emptyMap())): JsonObject? = runCatching {
        http().newCall(request(path).post(body.toString().toRequestBody("application/json".toMediaType())).build())
            .execute().use { r -> r.body?.string()?.let { AppJson.parseToJsonElement(it).jsonObject } }
    }.getOrNull()

    // ------------------------------------------------------------------ 시작·끝

    fun start(context: Context) {
        if (running) return
        app = context.applicationContext
        running = true
        sentCount = 0
        _meter.value = Meter()
        _lines.value = emptyList()
        _phase.value = Phase.Listening
        net.execute {
            val r = postJson("v1/voice/sessions")
            sessionId = r?.get("session_id")?.jsonPrimitive?.longOrNull
            if (sessionId == null) {
                add(Line("err-start", "error", "음성 대화를 시작하지 못했어요(서버 연결)"))
                main.post { stop(app, "start_failed") }
                return@execute
            }
            if (r?.get("configured")?.jsonPrimitive?.content == "false") {
                add(Line("err-key", "error", "아직 ElevenLabs 키가 없어요. 관리 화면 '음성 연결'에 넣어 주세요."))
            }
        }
        recordThread = Thread({ recordLoop() }, "voice-record").also { it.start() }
    }

    /** 끝내기(§4.3): 화면·알림 [종료], 볼륨 길게, 이어폰 버튼. reason 은 서버 end_reason. */
    fun stop(context: Context, reason: String) {
        if (!running) return
        running = false
        currentCall?.cancel()
        stopPlayback()
        _phase.value = Phase.Idle
        val id = sessionId
        sessionId = null
        if (id != null) {
            net.execute { postJson("v1/voice/sessions/$id/end", buildJsonObject { put("reason", reason) }) }
        }
        context.applicationContext.stopService(Intent(context.applicationContext, VoiceForegroundService::class.java))
        Chat.requestRefresh()
    }

    private fun add(line: Line) {
        _lines.value = (_lines.value + line).takeLast(80)
    }

    // ------------------------------------------------------------------ 듣기(VAD)

    @SuppressLint("MissingPermission")
    private fun recordLoop() {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            add(Line("err-mic", "error", "마이크 권한이 필요해요"))
            main.post { stop(app, "no_mic_permission") }
            return
        }
        // 2026-11-07: 일부 기기는 VOICE_COMMUNICATION 입력이 비어 있거나(소리 0) 초기화에 실패한다 → MIC 로 바꿔 다시 연다.
        var source = MediaRecorder.AudioSource.VOICE_COMMUNICATION
        var opened = openRecorder(source)
        if (opened == null) {
            source = MediaRecorder.AudioSource.MIC
            opened = openRecorder(source)
        }
        if (opened == null) {
            add(Line("err-rec", "error", "마이크를 열지 못했어요(다른 앱이 마이크를 쓰는 중일 수 있어요)"))
            main.post { stop(app, "mic_open_failed") }
            return
        }
        _meter.value = _meter.value.copy(mic = if (source == MediaRecorder.AudioSource.MIC) "마이크(기본)" else "마이크(통화용)")
        var rec: AudioRecord = opened
        var aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } else null
        var ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } else null
        val audio = app.getSystemService(AudioManager::class.java)
        val frame = ShortArray(FRAME)
        val preroll = ArrayDeque<ShortArray>()
        var floor = 300.0
        var speechRun = 0
        var inUtterance = false
        var silenceMs = 0
        var voicedFrames = 0
        var utterMs = 0
        val utter = ByteArrayOutputStream()
        rec.startRecording()
        var quietFrames = 0
        var readErrors = 0
        var switched = source == MediaRecorder.AudioSource.MIC
        var meterAt = 0L
        try {
            while (running) {
                val n = rec.read(frame, 0, FRAME)
                if (n <= 0) {
                    readErrors++
                    if (readErrors == 50) add(Line("err-read", "error", "마이크에서 소리를 읽지 못해요(코드 $n)"))
                    Thread.sleep(20)
                    continue
                }
                readErrors = 0
                // 통화 중이면 멈췄다가 끝나면 이어서 듣는다(§4.3)
                if (audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_RINGTONE) {
                    if (_phase.value != Phase.PausedForCall) {
                        currentCall?.cancel()
                        stopPlayback()
                        _phase.value = Phase.PausedForCall
                    }
                    inUtterance = false
                    utter.reset()
                    continue
                } else if (_phase.value == Phase.PausedForCall) {
                    _phase.value = Phase.Listening
                }
                var sum = 0.0
                for (i in 0 until n) sum += frame[i].toDouble() * frame[i]
                val rms = sqrt(sum / n)
                // 소리가 거의 0 인 채로 4초가 지나면(입력이 비어 있는 기기) 기본 마이크로 바꿔 다시 연다
                quietFrames = if (rms < 15) quietFrames + 1 else 0
                if (quietFrames >= 200 && !switched) {
                    switched = true
                    quietFrames = 0
                    runCatching { rec.stop() }
                    rec.release()
                    runCatching { aec?.release() }
                    runCatching { ns?.release() }
                    aec = null
                    ns = null
                    val alt = openRecorder(MediaRecorder.AudioSource.MIC)
                    if (alt != null) {
                        rec = alt
                        rec.startRecording()
                        _meter.value = _meter.value.copy(mic = "마이크(기본)")
                        add(Line("info-mic", "error", "마이크 입력이 비어 있어 기본 마이크로 바꿨어요"))
                    }
                    continue
                }
                val outLoud = _phase.value == Phase.Speaking && !WindowGuard.headset(audio)
                // 기준을 낮췄다(통화용 입력은 소리가 작게 들어오는 기기가 있다): 소음 바닥의 2.5배, 최소 200
                val threshold = max(floor * 2.5, 200.0) * (if (outLoud) 2.5 else 1.0)
                val speech = rms > threshold
                val nowMs = System.currentTimeMillis()
                if (nowMs - meterAt > 100) {
                    meterAt = nowMs
                    _meter.value = _meter.value.copy(level = scale(rms), threshold = scale(threshold))
                }
                if (!speech) floor = floor * 0.97 + rms * 0.03
                val copy = frame.copyOf(n)
                if (!inUtterance) {
                    preroll.addLast(copy)
                    if (preroll.size > PREROLL_FRAMES) preroll.removeFirst()
                    speechRun = if (speech) speechRun + 1 else 0
                    if (speechRun >= 3) {
                        inUtterance = true
                        silenceMs = 0
                        voicedFrames = 0
                        utterMs = 0
                        utter.reset()
                        preroll.forEach { writePcm(utter, it) }
                        preroll.clear()
                        // 비서가 말하거나 생각하는 중에 말소리가 나면 끊고 들어온다(§4.2)
                        if (_phase.value == Phase.Speaking || _phase.value == Phase.Thinking) bargeIn()
                    }
                } else {
                    writePcm(utter, copy)
                    utterMs += 20
                    if (speech) {
                        silenceMs = 0
                        voicedFrames++
                    } else {
                        silenceMs += 20
                    }
                    val endSilence = Prefs.get(app).voiceEndSilenceMs
                    if (silenceMs >= endSilence || utterMs >= MAX_UTTERANCE_MS) {
                        inUtterance = false
                        speechRun = 0
                        if (voicedFrames >= 8) send(utter.toByteArray())
                        utter.reset()
                    }
                }
            }
        } finally {
            runCatching { rec.stop() }
            runCatching { rec.release() }
            runCatching { aec?.release() }
            runCatching { ns?.release() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(source: Int): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = runCatching {
            AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, FRAME * 2 * 20))
        }.getOrNull() ?: return null
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return null
        }
        return r
    }

    private fun writePcm(out: ByteArrayOutputStream, s: ShortArray) {
        val b = ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        s.forEach { b.putShort(it) }
        out.write(b.array())
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1); h.putInt(RATE); h.putInt(RATE * 2)
        h.putShort(2); h.putShort(16); h.put("data".toByteArray()); h.putInt(pcm.size)
        return h.array() + pcm
    }

    // ------------------------------------------------------------------ 보내기·받기

    private fun send(pcm: ByteArray) {
        val id = sessionId
        if (id == null) {
            add(Line("err-nosession", "error", "아직 서버와 연결되지 않았어요. 잠시 뒤 다시 말해 주세요."))
            return
        }
        sentCount++
        _meter.value = _meter.value.copy(sent = sentCount)
        _phase.value = Phase.Thinking
        streamDone = false
        net.execute {
            val body = wav(pcm).toRequestBody("audio/wav".toMediaType())
            val call = http().newCall(request("v1/voice/sessions/$id/turn").post(body).build())
            currentCall = call
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        add(Line("err-${System.nanoTime()}", "error", "서버 오류(${resp.code})"))
                        return@use
                    }
                    val source = resp.body?.source() ?: return@use
                    while (running) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isBlank()) continue
                        onEvent(AppJson.parseToJsonElement(line).jsonObject)
                    }
                }
            } catch (e: Exception) {
                if (!call.isCanceled() && running) add(Line("err-${System.nanoTime()}", "error", "연결이 끊겼어요"))
            } finally {
                currentCall = null
                streamDone = true
                main.post { if (player == null && queue.isEmpty() && running && _phase.value == Phase.Thinking) _phase.value = Phase.Listening }
                Chat.requestRefresh()
            }
        }
    }

    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty()
    private fun num(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.longOrNull

    private fun onEvent(e: JsonObject) {
        val text = str(e, "text")
        when (str(e, "type")) {
            "user_text" -> add(Line("u${num(e, "message_id")}", "user", text, num(e, "message_id")))
            "assistant_text" -> add(Line("a${num(e, "message_id")}", "assistant", text, num(e, "message_id")))
            "saved" -> {
                val undo = str(e, "action").removePrefix("undo_card:").toLongOrNull()
                add(Line("s${num(e, "message_id")}", "saved", text, num(e, "message_id"), undoCard = undo))
            }
            "card" -> add(Line("c${num(e, "confirmation_id")}", "card", text, confirmationId = num(e, "confirmation_id")))
            "error" -> add(Line("e${System.nanoTime()}", "error", text))
            "audio" -> {
                val bytes = Base64.decode(str(e, "b64"), Base64.DEFAULT)
                val f = File.createTempFile("voice", ".mp3", app.cacheDir)
                f.writeBytes(bytes)
                val clip = Clip(num(e, "message_id") ?: 0, text, f)
                main.post { enqueue(clip) }
            }
        }
    }

    // ------------------------------------------------------------------ 재생

    private fun enqueue(clip: Clip) {
        if (!running) {
            clip.file.delete()
            return
        }
        queue.addLast(clip)
        if (player == null) playNext()
    }

    private fun playNext() {
        val clip = queue.pollFirst()
        if (clip == null) {
            player = null
            if (running && streamDone && _phase.value == Phase.Speaking) _phase.value = Phase.Listening
            return
        }
        if (currentMessage != clip.messageId) {
            currentMessage = clip.messageId
            spoken.setLength(0)
        }
        _phase.value = Phase.Speaking
        val p = MediaPlayer()
        player = p
        runCatching {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            p.setDataSource(clip.file.path)
            p.setOnCompletionListener {
                spoken.append(clip.text).append(' ')
                it.release()
                clip.file.delete()
                playNext()
            }
            p.prepare()
            p.start()
        }.onFailure {
            p.release()
            clip.file.delete()
            playNext()
        }
    }

    private fun stopPlayback() {
        main.post {
            player?.runCatching { stop(); release() }
            player = null
            while (queue.isNotEmpty()) queue.pollFirst()?.file?.delete()
        }
    }

    /** 끊고 들어오기(§3.3): 재생·받기를 멈추고, 그때까지 말한 부분을 서버에 알린다. */
    private fun bargeIn() {
        val id = sessionId ?: return
        val messageId = currentMessage
        val said = spoken.toString().trim()
        currentCall?.cancel()
        stopPlayback()
        _phase.value = Phase.Listening
        net.execute {
            postJson("v1/voice/sessions/$id/interrupt", buildJsonObject {
                if (messageId != null) put("message_id", messageId)
                put("spoken", said)
            })
        }
    }

    /** 음성 탭에서 바로 시작(앱이 보이는 상태). 마이크 포그라운드 서비스를 띄운다. */
    fun launch(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, VoiceForegroundService::class.java))
    }
}

/**
 * 마이크 포그라운드 서비스(§4.2). 상단 알림 "음성 대화 중 · 종료". 화면을 끄거나 다른 앱으로 가도 계속한다.
 * MediaSession 으로 이어폰 버튼(재생/일시정지)과 볼륨 버튼 길게(1초)를 받아 끝낸다. 짧게 누르면 원래 볼륨 조절.
 */
class VoiceForegroundService : Service() {

    companion object {
        const val CHANNEL = "voice"
        private const val NOTIFICATION_ID = 5001
        const val ACTION_STOP = "kr.ssbin.assistant.VOICE_STOP"
        private const val LONG_PRESS_MS = 1000L
        private const val BURST_GAP_MS = 400L
    }

    private var session: MediaSession? = null
    private var burstStart = 0L
    private var lastAdjust = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            VoiceEngine.stop(this, intent.getStringExtra("reason") ?: "notification")
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "음성 대화", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(),
            if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        setupMediaSession()
        VoiceEngine.start(this)
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 51, Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_VOICE, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 52, Intent(this, VoiceForegroundService::class.java)
            .setAction(ACTION_STOP).putExtra("reason", "notification"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("음성 대화 중")
            .setContentText("종료하려면 [종료] · 볼륨 버튼 길게 · 이어폰 버튼")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "종료", stop)
            .build()
    }

    private fun setupMediaSession() {
        if (session != null) return
        val audio = getSystemService(AudioManager::class.java)
        val s = MediaSession(this, "assistant-voice")
        s.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val ev: KeyEvent? = if (Build.VERSION.SDK_INT >= 33) {
                    mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION") mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                }
                if (ev != null && ev.action == KeyEvent.ACTION_UP && ev.keyCode in setOf(
                        KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE)
                ) {
                    VoiceEngine.stop(this@VoiceForegroundService, "headset_button")
                    stopSelf()
                }
                return true
            }
        })
        s.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
            .setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
        // 볼륨 버튼: 짧게 = 원래 볼륨 조절, 1초 이상 누르고 있으면(키 반복) 끝내기
        s.setPlaybackToRemote(object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 15, 7) {
            override fun onAdjustVolume(direction: Int) {
                val now = System.currentTimeMillis()
                if (now - lastAdjust > BURST_GAP_MS) {
                    burstStart = now
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
                }
                lastAdjust = now
                if (now - burstStart >= LONG_PRESS_MS) {
                    VoiceEngine.stop(this@VoiceForegroundService, "volume_long_press")
                    stopSelf()
                }
            }
        })
        s.isActive = true
        session = s
    }

    override fun onDestroy() {
        session?.release()
        session = null
        if (VoiceEngine.active) VoiceEngine.stop(this, "service_destroyed")
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 앱이 종료되면 세션도 끝난다(§4.3)
        VoiceEngine.stop(this, "app_closed")
        stopSelf()
    }
}
