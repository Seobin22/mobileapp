package kr.ssbin.assistant

import android.app.Application
import kr.ssbin.assistant.data.Prefs
import kr.ssbin.assistant.intervention.Notifier
import kr.ssbin.assistant.work.Sync

class AssistantApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        if (Prefs.get(this).configured) {
            Sync.schedulePeriodic(this)
        }
    }
}
