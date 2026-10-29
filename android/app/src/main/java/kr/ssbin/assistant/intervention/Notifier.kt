package kr.ssbin.assistant.intervention

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kr.ssbin.assistant.R
import kr.ssbin.assistant.data.InterventionEntity

/** 개입 풀스크린 알림. 개입은 한 번에 하나라 알림 id 도 하나다. */
object Notifier {

    const val CHANNEL_INTERVENTION = "intervention"
    private const val NOTIFICATION_ID = 1001

    fun createChannels(context: Context) {
        val channel = NotificationChannel(CHANNEL_INTERVENTION, "개입 화면", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "정해진 시각에 할 일 화면을 띄웁니다"
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun showFullScreen(context: Context, entity: InterventionEntity) {
        val intent = Intent(context, InterventionActivity::class.java)
            .putExtra(InterventionActivity.EXTRA_ID, entity.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_INTERVENTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(entity.taskTitle)
            .setContentText(entity.screenText)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
        // 만료 시각이 지나면 intervention.end 를 못 받아도 알림이 스스로 사라진다.
        entity.expireAt?.let {
            val left = java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() - System.currentTimeMillis()
            if (left > 0) builder.setTimeoutAfter(left)
        }
        val notification = builder.build()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // 알림 권한이 없으면 풀스크린 알림을 못 띄운다. 권한 화면이 빨간 배너로 알려준다.
            return
        }
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
