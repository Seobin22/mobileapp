package kr.ssbin.assistant.intervention

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kr.ssbin.assistant.data.AppDatabase
import kr.ssbin.assistant.schedule.Alarms

/** 화면을 닫았을 때 reshow_minutes 뒤 다시 띄우는 로컬 타이머. 서버 reshow 푸시가 먼저 오면 그걸로 대체된다. */
object Reshow {

    private const val REQUEST_CODE = 2000

    fun schedule(context: Context, interventionId: String, minutes: Int, extraSeconds: Int = 0) {
        val at = System.currentTimeMillis() + minutes * 60_000L + extraSeconds * 1000L
        Alarms.setExact(context, at, pending(context, interventionId))
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, ""))
    }

    private fun pending(context: Context, interventionId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, ReshowReceiver::class.java).putExtra(InterventionActivity.EXTRA_ID, interventionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

class ReshowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(InterventionActivity.EXTRA_ID) ?: return
        val pending = goAsync()
        Thread {
            try {
                val entity = AppDatabase.dao(context).intervention(id)
                if (entity?.status == "negotiating") {
                    InterventionController.onNegotiationCheck(context, entity)
                } else if (entity != null && InterventionState.visibleId == null &&
                    InterventionController.stillActive(context, entity)
                ) {
                    Notifier.showFullScreen(context, entity)
                    // 서버 reshow 푸시가 오면 이 타이머는 취소된다. FCM 이 없을 때는 이 반복이 서버 reshow 를 대신한다.
                    Reshow.schedule(context, entity.id, entity.reshowMinutes)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
