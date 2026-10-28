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
import kr.ssbin.assistant.data.ApiClient
import kr.ssbin.assistant.data.HeartbeatRequest
import kr.ssbin.assistant.data.Prefs
import kr.ssbin.assistant.data.RegisterRequest
import kr.ssbin.assistant.intervention.InterventionController
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
                api.heartbeat(HeartbeatRequest(prefs.deviceId, Capabilities.all(context)))
            } catch (e: retrofit2.HttpException) {
                if (e.code() != 404) throw e
                register(context) // 서버가 기기를 모르면 다시 등록한다.
            }
            InterventionController.onPlanSync(context, api.sync())
            ActionQueue.flush(context)
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
            suspendCancellableCoroutine { cont ->
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
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (Sync.runOnce(applicationContext)) Result.success() else Result.retry()
}
