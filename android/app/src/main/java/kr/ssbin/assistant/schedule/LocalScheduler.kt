package kr.ssbin.assistant.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kr.ssbin.assistant.data.AppDatabase
import kr.ssbin.assistant.data.Prefs
import kr.ssbin.assistant.intervention.InterventionController
import kr.ssbin.assistant.work.Sync
import java.time.OffsetDateTime

/**
 * 로컬 백업 알람(빌드문서 v1 §8.9). 서버 푸시가 안 와도 블록 시작 + grace_seconds 에 화면을 띄운다.
 * plan.sync 수신, 앱 시작(GET /v1/sync), 재부팅 때 전부 다시 예약한다.
 */
object LocalScheduler {

    private const val EXTRA_BLOCK_ID = "block_id"

    fun rescheduleAll(context: Context) {
        val prefs = Prefs.get(context)
        prefs.scheduledBlocks.mapNotNull { it.toLongOrNull() }.forEach { cancelBlock(context, it) }

        val plan = AppDatabase.dao(context).plan() ?: run {
            prefs.scheduledBlocks = emptySet()
            return
        }
        val now = System.currentTimeMillis()
        val scheduled = mutableSetOf<String>()
        for (item in InterventionController.upcoming(plan)) {
            // v3.1: 개입 시각(alarm_at = start − lead)에 맞춘다
            val at = OffsetDateTime.parse(item.alarm_at ?: item.start).toInstant().toEpochMilli() + plan.graceSeconds * 1000L
            val end = OffsetDateTime.parse(item.end).toInstant().toEpochMilli()
            if (end <= now) continue
            Alarms.setExact(context, maxOf(at, now + 1000), pending(context, item.block_id))
            scheduled += item.block_id.toString()
        }
        prefs.scheduledBlocks = scheduled
    }

    fun cancelBlock(context: Context, blockId: Long) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, blockId))
    }

    private fun pending(context: Context, blockId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            blockId.toInt(),
            Intent(context, LocalBackupReceiver::class.java)
                .setAction("kr.ssbin.assistant.LOCAL_BACKUP.$blockId")
                .putExtra(EXTRA_BLOCK_ID, blockId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    internal fun blockId(intent: Intent): Long = intent.getLongExtra(EXTRA_BLOCK_ID, -1)
}

class LocalBackupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val blockId = LocalScheduler.blockId(intent)
        if (blockId < 0) return
        val pending = goAsync()
        Thread {
            try {
                InterventionController.onLocalBackup(context, blockId)
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/** 재부팅·앱 업데이트 후 로컬 백업 알람을 다시 맞추고 서버와 동기화한다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                LocalScheduler.rescheduleAll(context)
                Sync.schedulePeriodic(context)
                Sync.runSoon(context)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
