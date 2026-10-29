package kr.ssbin.assistant.intervention

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** 지금 화면에 떠 있는 개입과, 떠 있는 화면에 보낼 신호. */
object InterventionState {

    sealed interface Event {
        /** 서버가 개입을 끝냈다(intervention.end). */
        data class Close(val blockId: Long) : Event

        /** 로컬 개입 화면이 서버 개입 id 로 바뀌었다. 화면은 새로 띄우지 않는다. */
        data class Replace(val blockId: Long, val newId: String) : Event

        /** 풀스크린 대화의 비서 답(chat.message 의 intervention_id, v3.1 §3.6-3). */
        data class Reply(val interventionId: String, val messageId: Long, val text: String) : Event
    }

    @Volatile
    var visibleId: String? = null

    @Volatile
    var visibleBlockId: Long? = null

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events

    fun emit(event: Event) {
        _events.tryEmit(event)
    }

    fun isShowingBlock(blockId: Long): Boolean = visibleBlockId == blockId
}
