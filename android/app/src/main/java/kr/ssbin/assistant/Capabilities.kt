package kr.ssbin.assistant

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat

/** 권한 점검 결과. heartbeat 의 capabilities 로 서버에 보낸다. */
object Capabilities {

    fun notifications(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun fullscreenIntent(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 34) {
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        } else {
            true
        }

    fun exactAlarm(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        } else {
            true
        }

    fun batteryExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** v4 §3.1: L4 잠금·딴짓 감지(접근성 서비스). */
    fun accessibility(context: Context): Boolean = kr.ssbin.assistant.lock.Lock.accessibilityEnabled(context)

    fun all(context: Context): Map<String, Boolean> = mapOf(
        "notifications" to notifications(context),
        "fullscreen_intent" to fullscreenIntent(context),
        "exact_alarm" to exactAlarm(context),
        "battery_exempt" to batteryExempt(context),
        "accessibility" to accessibility(context),
    )

    /** 빨간 배너 기준(접근성은 선택이라 빼고 본다). */
    fun allGranted(context: Context): Boolean = all(context).filterKeys { it != "accessibility" }.values.all { it }
}
