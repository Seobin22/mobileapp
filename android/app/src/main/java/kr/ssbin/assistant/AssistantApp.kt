package kr.ssbin.assistant

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kr.ssbin.assistant.work.FirstUnlock
import kr.ssbin.assistant.chat.Chat
import kr.ssbin.assistant.data.Prefs
import kr.ssbin.assistant.intervention.Notifier
import kr.ssbin.assistant.work.Sync

class AssistantApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        Chat.createChannel(this)
        if (Prefs.get(this).configured) {
            Sync.schedulePeriodic(this)
        }
        // 화면 잠금 해제(USER_PRESENT)는 매니페스트 리시버로 받을 수 없어 앱 프로세스가 살아 있을 때 받는다.
        // 받지 못한 날은 서버가 기상 + fallback 시각에 아침 브리핑을 한다.
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                FirstUnlock.onUserPresent(context)
            }
        }, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
