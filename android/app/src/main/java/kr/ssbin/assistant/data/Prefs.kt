package kr.ssbin.assistant.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** 서버 주소·API 토큰·기기 id. EncryptedSharedPreferences 에 저장한다. */
class Prefs private constructor(private val prefs: SharedPreferences) {

    var serverUrl: String
        get() = prefs.getString("server_url", DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(value) = prefs.edit().putString("server_url", value).apply()

    var apiToken: String
        get() = prefs.getString("api_token", "") ?: ""
        set(value) = prefs.edit().putString("api_token", value).apply()

    var deviceId: Long
        get() = prefs.getLong("device_id", -1)
        set(value) = prefs.edit().putLong("device_id", value).apply()

    /** Firebase 가 없을 때 쓰는 기기 식별 토큰. */
    var fallbackToken: String?
        get() = prefs.getString("fallback_token", null)
        set(value) = prefs.edit().putString("fallback_token", value).apply()

    /** LocalScheduler 가 예약해 둔 block_id 목록(취소용). */
    var scheduledBlocks: Set<String>
        get() = prefs.getStringSet("scheduled_blocks", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("scheduled_blocks", value).apply()

    /** 미루기 협상이 끝나는 시각(epoch ms). 채팅 상단 배너용. 0 이면 협상 중 아님. */
    var negotiationUntil: Long
        get() = prefs.getLong("negotiation_until", 0)
        set(value) = prefs.edit().putLong("negotiation_until", value).apply()

    /** 아직 서버에 보내지 않은 오늘 첫 잠금 해제 시각(ISO). 하트비트 events 로 보낸다(v3 §5.2). */
    /** FCM 없는 기기가 마지막으로 알림을 띄운(또는 화면에서 본) 비서 메시지 id. */
    var lastNotifiedMessageId: Long
        get() = prefs.getLong("last_notified_message_id", -1)
        set(value) = prefs.edit().putLong("last_notified_message_id", value).apply()

    var pendingFirstUnlock: String?
        get() = prefs.getString("pending_first_unlock", null)
        set(value) = prefs.edit().putString("pending_first_unlock", value).apply()

    /** 마지막으로 첫 잠금 해제를 기록한 날짜(yyyy-MM-dd). */
    var firstUnlockDay: String?
        get() = prefs.getString("first_unlock_day", null)
        set(value) = prefs.edit().putString("first_unlock_day", value).apply()

    /** v3.6 plan.sync 의 time_windows(앞으로 24시간, JSON). 서버와 끊겨도 이 값으로 막는다. */
    var timeWindows: String
        get() = prefs.getString("time_windows", "[]") ?: "[]"
        set(value) = prefs.edit().putString("time_windows", value).apply()

    /** v3.6 [10분만 조용히] 끝 시각(ISO). 비면 멈춤 아님. */
    var activePausedUntil: String
        get() = prefs.getString("active_paused_until", "") ?: ""
        set(value) = prefs.edit().putString("active_paused_until", value).apply()

    /** v3.6 §2.5 방해 금지라 막은 일(종류·시각만, 최근 것이 앞). */
    var guardLog: List<String>
        get() = (prefs.getString("guard_log", "") ?: "").split('\n').filter { it.isNotBlank() }
        set(value) = prefs.edit().putString("guard_log", value.joinToString("\n")).apply()

    // v3.6 음성 탭
    var voiceHeadsetHintShown: Boolean
        get() = prefs.getBoolean("voice_headset_hint", false)
        set(value) = prefs.edit().putBoolean("voice_headset_hint", value).apply()

    var voiceEndSilenceMs: Int
        get() = prefs.getInt("voice_end_silence_ms", 800)
        set(value) = prefs.edit().putInt("voice_end_silence_ms", value).apply()

    /** 음성 알림 끄기: 이 시각(ms)까지 끔. 0 = 켜짐, Long.MAX_VALUE = 계속 끔. 이 기기에만 저장. */
    var voiceMuteUntil: Long
        get() = prefs.getLong("voice_mute_until", 0)
        set(value) = prefs.edit().putLong("voice_mute_until", value).apply()

    /** 마이크 민감도(0.7 낮게 · 1.0 보통 · 1.6 높게). 이 기기에만 저장. */
    var voiceSensitivity: Float
        get() = prefs.getFloat("voice_sensitivity", 1.3f)
        set(value) = prefs.edit().putFloat("voice_sensitivity", value).apply()

    /** §5 적극 개입 시간대에 개입 화면이 뜨면 음성 대화 자동 시작(사용자가 설정에서 켬). */
    var activeAutoVoice: Boolean
        get() = prefs.getBoolean("active_auto_voice", false)
        set(value) = prefs.edit().putBoolean("active_auto_voice", value).apply()

    // v4 §5 딴짓 감지(plan.sync 로 받는다)
    var monitorWindows: String
        get() = prefs.getString("monitor_windows", "[]") ?: "[]"
        set(value) = prefs.edit().putString("monitor_windows", value).apply()

    var appRules: String
        get() = prefs.getString("app_rules", "[]") ?: "[]"
        set(value) = prefs.edit().putString("app_rules", value).apply()

    var distractTriggerMin: Int
        get() = prefs.getInt("distract_trigger_min", 3)
        set(value) = prefs.edit().putInt("distract_trigger_min", value).apply()

    /** 서버에 못 보낸 딴짓 기록(JSON 한 줄씩). */
    var pendingDistractions: List<String>
        get() = (prefs.getString("pending_distractions", "") ?: "").split('\n').filter { it.isNotBlank() }
        set(value) = prefs.edit().putString("pending_distractions", value.joinToString("\n")).apply()

    // v4 §3.2 L4 잠금 상태
    var lockActive: Boolean
        get() = prefs.getBoolean("lock_active", false)
        set(value) = prefs.edit().putBoolean("lock_active", value).apply()

    var lockInterventionId: String
        get() = prefs.getString("lock_intervention_id", "") ?: ""
        set(value) = prefs.edit().putString("lock_intervention_id", value).apply()

    var lockBlockId: Long
        get() = prefs.getLong("lock_block_id", -1)
        set(value) = prefs.edit().putLong("lock_block_id", value).apply()

    var lockTitle: String
        get() = prefs.getString("lock_title", "") ?: ""
        set(value) = prefs.edit().putString("lock_title", value).apply()

    /** 잠금 화면의 비서 한 줄(마지막 개입 메시지). */
    var lockLine: String
        get() = prefs.getString("lock_line", "") ?: ""
        set(value) = prefs.edit().putString("lock_line", value).apply()

    val configured: Boolean get() = apiToken.isNotBlank() && serverUrl.isNotBlank()

    companion object {
        const val DEFAULT_SERVER = "https://test.seobin.page/api/assistant/"

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs = instance ?: synchronized(this) {
            instance ?: Prefs(open(context.applicationContext)).also { instance = it }
        }

        private fun open(context: Context): SharedPreferences = try {
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                context,
                "assistant_secure",
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // 일부 기기에서 키스토어 오류가 나면 일반 저장소로 대신한다.
            context.getSharedPreferences("assistant_plain", Context.MODE_PRIVATE)
        }
    }
}
