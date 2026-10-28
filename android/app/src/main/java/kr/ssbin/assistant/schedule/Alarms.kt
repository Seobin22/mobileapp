package kr.ssbin.assistant.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build

object Alarms {
    /** 정확한 알람 권한이 없으면 부정확한 알람으로 대신한다(권한 화면이 빨간 배너로 알린다). */
    fun setExact(context: Context, atMillis: Long, pending: PendingIntent) {
        val am = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        }
    }
}
