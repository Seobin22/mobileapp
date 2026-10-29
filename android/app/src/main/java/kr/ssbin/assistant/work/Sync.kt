package kr.ssbin.assistant.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import kr.ssbin.assistant.BuildConfig
import kr.ssbin.assistant.Capabilities
import kr.ssbin.assistant.chat.Chat
import kr.ssbin.assistant.data.ApiClient
import kr.ssbin.assistant.data.HeartbeatRequest
import kr.ssbin.assistant.data.Prefs
import kr.ssbin.assistant.data.RegisterRequest
import kr.ssbin.assistant.intervention.InterventionController
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 기기 등록·하트비트·계획 동기화.
 * 앱 시작 시 + 15분마다 실행한다. FCM 이 없을 때도 계획을 받아 로컬 백업 알람을 맞추기 위해 매번 GET /v1/sync 한다.
 */
object Sync {

    private const val TAG = "AssistantSync"

    suspend fun runOnce(context: Context): Boolean {
        val prefs = Prefs.get(context)
        if (!prefs.configured) return false
        return try {
            val api = ApiClient.get(context)
            if (prefs.deviceId < 0) register(context)
            try {
                val unlock = prefs.pendingFirstUnlock
                val events = if (unlock == null) emptyList() else listOf(
                    JsonObject(mapOf("type" to JsonPrimitive("first_unlock"), "at" to JsonPrimitive(unlock)))
                )
                api.heartbeat(HeartbeatRequest(prefs.deviceId, Capabilities.all(context), events))
                if (unlock != null && prefs.pendingFirstUnlock == unlock) prefs.pendingFirstUnlock = null
            } catch (e: retrofit2.HttpException) {
                if (e.code() != 404) throw e
                register(context) // 서버가 기기를 모르면 다시 등록한다.
            }
            InterventionController.onPlanSync(context, api.sync())
            ActionQueue.flush(context)
            if (prefs.fallbackToken != null) {
                runCatching { Chat.notifyUnseen(context, api.messages().messages) }
                    .onFailure { Log.w(TAG, "message check failed", it) }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "sync failed", e)
            false
        }
    }

    suspend fun register(context: Context, fcmToken: String? = null) {
        val prefs = Prefs.get(context)
        val token = fcmToken ?: fcmToken(context)
        val response = ApiClient.get(context)
            .register(RegisterRequest(token, BuildConfig.VERSION_NAME, Capabilities.all(context)))
        prefs.deviceId = response.device_id
    }

    /** Firebase 가 설정되지 않았으면(google-services.json 없음) 기기별 임시 토큰을 쓴다. */
    private suspend fun fcmToken(context: Context): String {
        val token = try {
            suspendCancellableCoroutine<String?> { cont ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    cont.resume(if (task.isSuccessful) task.result else null)
                }
            }
        } catch (e: Exception) {
            null
        }
        if (token != null) return token
        val prefs = Prefs.get(context)
        return prefs.fallbackToken ?: ("nofcm-" + UUID.randomUUID()).also { prefs.fallbackToken = it }
    }

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork("heartbeat", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun runSoon(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("sync-now", ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * 앱을 나갈 때: 비서가 아직 답(계획 변경)을 만드는 중일 수 있어 1분·3분 뒤 한 번씩 더 받는다.
     * FCM 이 없으면 15분 주기만으로는 "10분 뒤 블록"의 로컬 알람을 못 맞춘다.
     */
    fun runAfterLeaving(context: Context) {
        for (delayMinutes in listOf(1L, 3L)) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("sync-after-$delayMinutes", ExistingWorkPolicy.REPLACE, request)
        }
    }
}

/** 하루 첫 잠금 해제를 기록해 하트비트로 보낸다(서버가 아침 브리핑 여부를 판단). */
object FirstUnlock {
    fun onUserPresent(context: Context) {
        val prefs = Prefs.get(context)
        if (!prefs.configured) return
        val today = java.time.LocalDate.now().toString()
        if (prefs.firstUnlockDay == today) return
        // 기상 전(새벽) 확인은 서버가 무시한다. 그 경우 그날 다음 잠금 해제를 다시 보낼 수 있도록 날짜만 기록하지 않는다.
        prefs.pendingFirstUnlock = java.time.OffsetDateTime.now().withNano(0).toString()
        if (java.time.LocalTime.now().hour >= 5) prefs.firstUnlockDay = today
        Sync.runSoon(context)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (Sync.runOnce(applicationContext)) Result.success() else Result.retry()
}
