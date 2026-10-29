package kr.ssbin.assistant.chat

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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kr.ssbin.assistant.R
import kr.ssbin.assistant.data.ApiClient
import kr.ssbin.assistant.data.MessageDto
import kr.ssbin.assistant.data.Prefs
import kr.ssbin.assistant.data.PostMessageRequest
import kr.ssbin.assistant.data.PostMessageResponse
import kr.ssbin.assistant.ui.MainActivity
import java.util.UUID

/** 채팅 메시지 전송·알림·화면 갱신 신호(빌드문서 v2 §11.1). */
object Chat {

    const val CHANNEL_CHAT = "chat"
    const val CHANNEL_CHECKIN = "checkin"
    private const val NOTIFICATION_ID = 3001
    private const val CHECKIN_NOTIFICATION_ID = 3002

    private val _refresh = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /** chat.message·confirmation 푸시를 받으면 채팅 화면이 목록을 다시 읽는다. */
    val refresh: SharedFlow<Unit> = _refresh

    fun requestRefresh() {
        _refresh.tryEmit(Unit)
    }

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CHAT, "비서 메시지", NotificationManager.IMPORTANCE_DEFAULT)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CHECKIN, "작업 중 체크인", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "작업 중에 진행을 묻는 메시지"
            }
        )
    }

    /** source: chat | voice | widget */
    suspend fun send(
        context: Context,
        text: String,
        source: String,
        answeredQuestionId: Long? = null,
    ): PostMessageResponse {
        val response = ApiClient.get(context).postMessage(
            PostMessageRequest(UUID.randomUUID().toString(), text, source, answeredQuestionId)
        )
        requestRefresh()
        return response
    }

    /** chat.message 푸시. silent=true 면 알림 없이 채팅에만 쌓인다(서버가 정함, v3 §4.5). */
    fun onChatPush(context: Context, data: Map<String, String>) {
        requestRefresh()
        if (data["silent"] == "true") return
        if (ChatVisibility.visible) return // 채팅 화면을 보고 있으면 알림을 띄우지 않는다
        if (data["channel"] == CHANNEL_CHECKIN) {
            notify(context, "진행 확인", data["preview"].orEmpty(), CHANNEL_CHECKIN, CHECKIN_NOTIFICATION_ID)
        } else {
            notify(context, "실행 비서", data["preview"].orEmpty())
        }
    }

    fun onConfirmationPush(context: Context, data: Map<String, String>) {
        requestRefresh()
        if (ChatVisibility.visible) return
        notify(context, "확인이 필요해요", data["text"].orEmpty())
    }

    /**
     * FCM 이 없는 기기(임시 토큰)는 동기화 때 새 비서 메시지를 직접 확인해 알림을 띄운다.
     * 처음 한 번은 기준 id 만 기록한다(이전 메시지를 한꺼번에 알리지 않도록).
     */
    fun notifyUnseen(context: Context, messages: List<MessageDto>) {
        val prefs = Prefs.get(context)
        if (prefs.fallbackToken == null) return
        val assistant = messages.filter { it.role == "assistant" }
        val maxId = assistant.maxOfOrNull { it.id } ?: return
        val last = prefs.lastNotifiedMessageId
        prefs.lastNotifiedMessageId = maxOf(last, maxId)
        if (last < 0 || ChatVisibility.visible) return
        val fresh = assistant.filter { it.id > last && !it.read && !it.silent }
        val latest = fresh.lastOrNull() ?: return
        requestRefresh()
        if (latest.kind == "checkin") {
            notify(context, "진행 확인", latest.content, CHANNEL_CHECKIN, CHECKIN_NOTIFICATION_ID)
        } else {
            notify(context, if (fresh.size > 1) "실행 비서 (${fresh.size})" else "실행 비서", latest.content)
        }
    }

    private fun notify(
        context: Context,
        title: String,
        text: String,
        channel: String = CHANNEL_CHAT,
        notificationId: Int = NOTIFICATION_ID,
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val pending = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    fun clearNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        NotificationManagerCompat.from(context).cancel(CHECKIN_NOTIFICATION_ID)
    }
}

object ChatVisibility {
    @Volatile
    var visible: Boolean = false
}
