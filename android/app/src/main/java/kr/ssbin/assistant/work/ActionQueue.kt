package kr.ssbin.assistant.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kr.ssbin.assistant.data.ApiClient
import kr.ssbin.assistant.data.AppDatabase
import kr.ssbin.assistant.data.AppJson
import kr.ssbin.assistant.data.PendingAction
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 사용자 액션(start/dismiss/local_fired)을 pending_actions 에 쌓고 바로 보낸다.
 * 실패하면 WorkManager(네트워크 연결 조건)로 다시 보낸다. 성공하면 지운다.
 */
object ActionQueue {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    fun enqueue(context: Context, type: String, interventionId: String?, blockId: Long?, blockVersion: Int? = null) {
        val app = context.applicationContext
        val key = UUID.randomUUID().toString()
        val fields = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
            "idempotency_key" to JsonPrimitive(key),
            "type" to JsonPrimitive(type),
            "client_at" to JsonPrimitive(OffsetDateTime.now().toString()),
        )
        if (interventionId != null) fields["intervention_id"] = JsonPrimitive(interventionId)
        if (blockId != null) fields["block_id"] = JsonPrimitive(blockId)
        if (blockVersion != null) fields["block_version"] = JsonPrimitive(blockVersion)
        val json = JsonObject(fields).toString()
        scope.launch {
            AppDatabase.dao(app).insertAction(PendingAction(key, json, System.currentTimeMillis()))
            if (!flush(app)) scheduleRetry(app)
        }
    }

    /** 쌓인 액션을 순서대로 보낸다. 하나라도 실패하면 false. */
    suspend fun flush(context: Context): Boolean = mutex.withLock {
        val dao = AppDatabase.dao(context)
        for (action in dao.pendingActions()) {
            try {
                val req = AppJson.parseToJsonElement(action.json) as JsonObject
                val res = ApiClient.get(context).action(req)
                dao.deleteAction(action.idempotencyKey)
                // 옛 백업 알람이면 서버가 ignored 를 돌려준다 → 띄운 화면을 닫는다.
                if ((res["status"] as? JsonPrimitive)?.content == "ignored") {
                    (req["block_id"] as? JsonPrimitive)?.content?.toLongOrNull()?.let {
                        kr.ssbin.assistant.intervention.InterventionController.onLocalIgnored(context, it)
                    }
                }
            } catch (e: retrofit2.HttpException) {
                if (e.code() in 400..499 && e.code() != 401) {
                    // 서버가 받지 않는 액션은 다시 보내도 같다. 버린다.
                    dao.deleteAction(action.idempotencyKey)
                } else {
                    return false
                }
            } catch (e: Exception) {
                return false
            }
        }
        true
    }

    fun scheduleRetry(context: Context) {
        val request = OneTimeWorkRequestBuilder<ActionWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("actions", ExistingWorkPolicy.KEEP, request)
    }
}

class ActionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (ActionQueue.flush(applicationContext)) Result.success() else Result.retry()
}
