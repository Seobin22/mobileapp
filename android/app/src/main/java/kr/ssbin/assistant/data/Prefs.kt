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
    var pendingFirstUnlock: String?
        get() = prefs.getString("pending_first_unlock", null)
        set(value) = prefs.edit().putString("pending_first_unlock", value).apply()

    /** 마지막으로 첫 잠금 해제를 기록한 날짜(yyyy-MM-dd). */
    var firstUnlockDay: String?
        get() = prefs.getString("first_unlock_day", null)
        set(value) = prefs.edit().putString("first_unlock_day", value).apply()

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
