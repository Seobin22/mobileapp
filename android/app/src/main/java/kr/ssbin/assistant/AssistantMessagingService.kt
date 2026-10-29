package kr.ssbin.assistant

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import kr.ssbin.assistant.chat.Chat
import kr.ssbin.assistant.data.Prefs
import kr.ssbin.assistant.intervention.InterventionController
import kr.ssbin.assistant.work.Sync

/** FCM data 메시지 수신. onMessageReceived 는 백그라운드 스레드에서 불린다. */
class AssistantMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        if (!Prefs.get(this).configured) return
        runCatching { runBlocking { Sync.register(applicationContext, token) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        when (data["type"]) {
            "intervention.level" -> InterventionController.onLevel(applicationContext, data)
            "intervention.end" -> InterventionController.onEnd(applicationContext, data)
            "plan.sync" -> InterventionController.onPlanSync(applicationContext, data)
            "chat.message" -> Chat.onChatPush(applicationContext, data)
            "confirmation" -> Chat.onConfirmationPush(applicationContext, data)
        }
    }
}
