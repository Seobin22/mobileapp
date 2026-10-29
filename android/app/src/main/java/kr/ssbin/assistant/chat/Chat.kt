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
import kr.ssbin.assistant.data.PostMessageRequest
import kr.ssbin.assistant.data.PostMessageResponse
import kr.ssbin.assistant.ui.MainActivity
import java.util.UUID

/** 채팅 메시지 전송·알림·화면 갱신 신호(빌드문서 v2 §11.1). */
object Chat {

    const val CHANNEL_CHAT = "chat"
    private const val NOTIFICATION_ID = 3001

    private val _refresh = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /** chat.message·confirmation 푸시를 받으면 채팅 화면이 목록을 다시 읽는다. */
    val refresh: SharedFlow<Unit> = _refresh

    fun requestRefresh() {
        _refresh.tryEmit(Unit)
    }

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_CHAT, "비서 메시지", NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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

    /** chat.message 푸시. silent=true 면 알림 없이 채팅에만 쌓인다(v2 는 항상 false). */
    fun onChatPush(context: Context, data: Map<String, String>) {
        requestRefresh()
        if (data["silent"] == "true") return
        if (ChatVisibility.visible) return // 채팅 화면을 보고 있으면 알림을 띄우지 않는다
        notify(context, "실행 비서", data["preview"].orEmpty())
    }

    fun onConfirmationPush(context: Context, data: Map<String, String>) {
        requestRefresh()
        if (ChatVisibility.visible) return
        notify(context, "확인이 필요해요", data["text"].orEmpty())
    }

    private fun notify(context: Context, title: String, text: String) {
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
        val notification = NotificationCompat.Builder(context, CHANNEL_CHAT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    fun clearNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}

object ChatVisibility {
    @Volatile
    var visible: Boolean = false
}
