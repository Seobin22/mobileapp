package kr.ssbin.assistant.intervention

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kr.ssbin.assistant.R
import kr.ssbin.assistant.data.ApiClient
import kr.ssbin.assistant.data.AppJson
import kr.ssbin.assistant.data.Prefs
import kr.ssbin.assistant.ui.MainActivity
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** plan.sync 의 time_windows 항목(앞으로 24시간). */
@Serializable
data class WindowOcc(val kind: String, val name: String = "", val from: String, val to: String)

/**
 * 시간대 이중 확인(빌드문서 v3.6 §2.5). 소리·진동·TTS·화면 넘김 직전에 로컬 시간대를 다시 본다.
 * 방해 금지면 서버가 시켜도 하지 않고, 종류와 시각만 로컬 기록에 남긴다(내용 없음). 서버와 끊겨도 마지막 시간대로 동작한다.
 */
object WindowGuard {

    private const val MAX_LOG = 100

    fun save(context: Context, timeWindows: String?, pausedUntil: String?) {
        val prefs = Prefs.get(context)
        if (timeWindows != null) prefs.timeWindows = timeWindows
        prefs.activePausedUntil = pausedUntil.orEmpty()
    }

    private fun occs(context: Context): List<WindowOcc> =
        runCatching { AppJson.decodeFromString<List<WindowOcc>>(Prefs.get(context).timeWindows) }.getOrDefault(emptyList())

    private fun at(context: Context, kind: String, now: Instant): WindowOcc? = occs(context).firstOrNull {
        it.kind == kind && !now.isBefore(OffsetDateTime.parse(it.from).toInstant()) &&
            now.isBefore(OffsetDateTime.parse(it.to).toInstant())
    }

    fun dnd(context: Context, now: Instant = Instant.now()): WindowOcc? = at(context, "dnd", now)

    fun paused(context: Context, now: Instant = Instant.now()): Boolean {
        val until = Prefs.get(context).activePausedUntil
        if (until.isBlank()) return false
        return runCatching { now.isBefore(OffsetDateTime.parse(until).toInstant()) }.getOrDefault(false)
    }

    /** 적극 개입 시간대(방해 금지가 이긴다, [10분만 조용히] 중이면 아님). */
    fun active(context: Context, now: Instant = Instant.now()): WindowOcc? {
        if (dnd(context, now) != null || paused(context, now)) return null
        return at(context, "active", now)
    }

    /** 막아야 하면 true 를 돌려주고 기록한다. kind: fullscreen·notification·tts·alarm */
    fun block(context: Context, kind: String): Boolean {
        if (dnd(context) == null) return false
        log(context, kind)
        return true
    }

    private fun log(context: Context, kind: String) {
        val prefs = Prefs.get(context)
        val line = kind + " " + Instant.now().atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))
        prefs.guardLog = (listOf(line) + prefs.guardLog).take(MAX_LOG)
    }

    /**
     * §2.4 소리로 말하기 조건: 방해 금지·멈춤 아님 + 적극 개입 시간대, 무음·진동 모드가 아니거나 이어폰 연결, 통화 중 아님.
     */
    fun canSpeak(context: Context): Boolean {
        if (block(context, "tts")) return false
        if (active(context) == null) return false
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        if (audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION ||
            audio.mode == AudioManager.MODE_RINGTONE
        ) return false
        // 2026-11-10(사용자 요청): 적극 개입 시간대에는 무음·진동 모드여도 푸시 대신 목소리로 말한다(깨우기 질문처럼 알람 음량).
        return true
    }

    /** 적극 개입 시간대 말하기는 알람 음량으로(무음·진동 모드에서도 들린다). */
    val SPEECH_ATTRIBUTES: android.media.AudioAttributes = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()

    fun headset(audio: AudioManager): Boolean = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type in setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_USB_HEADSET,
        ) || Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }
}

/** 비서가 먼저 거는 말을 소리로 읽는다(§2.4, 기기 음성). 조건은 매번 WindowGuard.canSpeak 로 다시 확인한다. */
object Speaker {

    @Volatile
    private var tts: TextToSpeech? = null
    private var pending: String? = null

    /**
     * v3.6 §5: 비서 목소리(ElevenLabs, 서버를 거침)를 먼저 쓰고, 안 되면 기기 음성. 음성 대화 중이면 읽지 않는다(그쪽이 말한다).
     */
    /** 말하면 true(그러면 진동 알림을 띄우지 않는다). */
    fun speak(context: Context, text: String, messageId: Long? = null): Boolean {
        if (text.isBlank() || !WindowGuard.canSpeak(context) || kr.ssbin.assistant.voice.VoiceEngine.active) return false
        val app = context.applicationContext
        if (messageId != null) {
            Thread {
                val ok = runCatching {
                    val bytes = kotlinx.coroutines.runBlocking { ApiClient.get(app).messageAudio(messageId).bytes() }
                    val f = java.io.File.createTempFile("say", ".mp3", app.cacheDir)
                    f.writeBytes(bytes)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (!WindowGuard.canSpeak(app)) { f.delete(); return@post }
                        val p = android.media.MediaPlayer()
                        runCatching {
                            p.setAudioAttributes(WindowGuard.SPEECH_ATTRIBUTES)
                            p.setDataSource(f.path)
                            p.setOnCompletionListener { it.release(); f.delete() }
                            p.prepare()
                            p.start()
                        }.onFailure { p.release(); f.delete() }
                    }
                }.isSuccess
                if (!ok) deviceSpeak(app, text)
            }.start()
            return true
        }
        deviceSpeak(app, text)
        return true
    }

    /** 깨우기 질문: 알람 음량(USAGE_ALARM)으로 기기 음성. 무음 모드에서도 들린다. */
    fun speakAlarm(context: Context, text: String) {
        val app = context.applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            var engine: TextToSpeech? = null
            engine = TextToSpeech(app) init@{ status ->
                val e = engine ?: return@init
                if (status == TextToSpeech.SUCCESS) {
                    e.language = Locale.KOREAN
                    e.setAudioAttributes(android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    e.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) { e.shutdown() }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { e.shutdown() }
                    })
                    e.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wake-${System.currentTimeMillis()}")
                } else {
                    e.shutdown()
                }
            }
        }, 4000) // 알람 소리가 먼저 울린 뒤
    }

    private fun deviceSpeak(context: Context, text: String) {
        if (!WindowGuard.canSpeak(context)) return
        val engine = tts
        if (engine != null) {
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, "proactive-${System.currentTimeMillis()}")
            return
        }
        pending = text
        val app = context.applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            tts = TextToSpeech(app) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.KOREAN
                    tts?.setAudioAttributes(WindowGuard.SPEECH_ATTRIBUTES)
                    pending?.let { tts?.speak(it, TextToSpeech.QUEUE_ADD, null, "proactive-first") }
                }
                pending = null
            }
        }
    }
}

/** 적극 개입 시간대 알람(서버 active.alarm 푸시가 반응할 때까지 반복해서 온다). */
object ActiveAlarm {

    /** 2026-11-07: 알람 소리(무음 모드에서도 울림)가 나는 새 채널. 채널 소리는 만든 뒤 바꿀 수 없어 이름을 바꿨다. */
    const val CHANNEL = "active_alarm_wake"
    private const val WAKE_TEXT = "자고 있어요? 일어났으면 확인을 눌러 주세요."
    private const val NOTIFICATION_ID = 4001
    const val ACTION_ACK = "kr.ssbin.assistant.ACTIVE_ACK"
    const val ACTION_PAUSE = "kr.ssbin.assistant.ACTIVE_PAUSE"

    fun createChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        runCatching { nm.deleteNotificationChannel("active_alarm") } // 소리 없던 옛 채널
        val channel = NotificationChannel(CHANNEL, "적극 개입 시간 알람(깨우기)", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "적극 개입 시간대에 확인을 누를 때까지 3분마다 울립니다(무음 모드에서도 알람 음량으로)"
        channel.enableVibration(true)
        channel.setSound(
            android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM),
            android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
        )
        nm.createNotificationChannel(channel)
    }

    fun show(context: Context, data: Map<String, String>) {
        if (WindowGuard.block(context, "alarm")) return
        if (WindowGuard.paused(context)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        // 알림을 직접 누르면 반응(ack). 전체 화면은 화면이 꺼져 있으면 저절로 뜨므로 반응으로 치지 않는다
        // (2026-11-07 버그: 저절로 뜬 화면이 곧바로 '일어남'으로 처리돼 반복 알람이 멈췄다).
        val open = PendingIntent.getActivity(
            context, 41,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ACTIVE_ACK, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val wakeScreen = PendingIntent.getActivity(
            context, 44,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(data["text"] ?: "적극 개입 시간이에요")
            .setContentText(WAKE_TEXT)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setFullScreenIntent(wakeScreen, true)
            .addAction(0, "확인", broadcast(context, ACTION_ACK, 42))
            .addAction(0, "10분만 조용히", broadcast(context, ACTION_PAUSE, 43))
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        // 깨우기: 알람 음량으로 소리 내 묻는다(무음 모드에서도 들리게, 방해 금지 시간대는 위에서 막았다)
        Speaker.speakAlarm(context, WAKE_TEXT)
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun broadcast(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context, code, Intent(context, ActiveAlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 알람 [확인]·알림 탭: 서버가 비서에게 먼저 말 걸게 한다. */
    fun ack(context: Context) {
        cancel(context)
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch { runCatching { ApiClient.get(app).ackActiveWindow() } }
    }

    /** [10분만 조용히] */
    fun pause(context: Context, done: (String?) -> Unit = {}) {
        cancel(context)
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val r = runCatching { ApiClient.get(app).pauseActiveWindow() }
            r.onSuccess { body ->
                val until = (body["paused_until"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                Prefs.get(app).activePausedUntil = until.orEmpty()
            }
            done(r.exceptionOrNull()?.let { ApiClient.errorMessage(it) })
        }
    }
}

class ActiveAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ActiveAlarm.ACTION_ACK -> ActiveAlarm.ack(context)
            ActiveAlarm.ACTION_PAUSE -> ActiveAlarm.pause(context)
        }
    }
}
