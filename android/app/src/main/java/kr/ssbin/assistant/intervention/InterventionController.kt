package kr.ssbin.assistant.intervention

import android.content.Context
import kr.ssbin.assistant.data.AppDatabase
import kr.ssbin.assistant.data.AppJson
import kr.ssbin.assistant.data.InterventionEntity
import kr.ssbin.assistant.data.PlanSnapshot
import kr.ssbin.assistant.data.QuietWindow
import kr.ssbin.assistant.data.UpcomingItem
import kr.ssbin.assistant.schedule.LocalScheduler
import kr.ssbin.assistant.work.ActionQueue
import java.time.Instant
import java.time.OffsetDateTime

/**
 * 푸시·로컬 알람·사용자 조작을 개입 상태로 바꾸는 곳(빌드문서 v1 §8.6~8.9).
 * 메인 스레드가 아닌 곳에서 부른다(Room 을 직접 쓴다).
 */
object InterventionController {

    private const val DEFAULT_RESHOW_MINUTES = 3

    /** FCM intervention.level */
    fun onLevel(context: Context, data: Map<String, String>) {
        val id = data["intervention_id"] ?: return
        val blockId = data["block_id"]?.toLongOrNull() ?: return
        val dao = AppDatabase.dao(context)
        val entity = InterventionEntity(
            id = id,
            blockId = blockId,
            level = data["level"]?.toIntOrNull() ?: 3,
            taskTitle = data["task_title"].orEmpty(),
            screenText = data["screen_text"].orEmpty(),
            ttsText = data["tts_text"] ?: data["screen_text"].orEmpty(),
            reshowMinutes = data["reshow_minutes"]?.toIntOrNull() ?: DEFAULT_RESHOW_MINUTES,
            blockEnd = data["block_end"],
            expireAt = data["expire_at"],
            status = "running",
            fromServer = true,
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsertIntervention(entity)
        dao.deleteIntervention(localId(blockId))
        LocalScheduler.cancelBlock(context, blockId)
        Reshow.cancel(context)

        if (InterventionState.isShowingBlock(blockId)) {
            // 이미 이 블록 화면이 떠 있다(서버 reshow 또는 로컬 백업이 먼저 띄움). id 만 바꾼다.
            InterventionState.emit(InterventionState.Event.Replace(blockId, id))
        } else {
            Notifier.showFullScreen(context, entity)
        }
    }

    /** FCM intervention.end */
    fun onEnd(context: Context, data: Map<String, String>) {
        val id = data["intervention_id"] ?: return
        val dao = AppDatabase.dao(context)
        val blockId = data["block_id"]?.toLongOrNull() ?: dao.intervention(id)?.blockId
        dao.endIntervention(id, blockId?.let { localId(it) } ?: id, System.currentTimeMillis())
        Notifier.cancel(context)
        Reshow.cancel(context)
        if (blockId != null) InterventionState.emit(InterventionState.Event.Close(blockId))
    }

    /** FCM plan.sync 또는 GET /v1/sync 결과. */
    fun onPlanSync(context: Context, data: Map<String, String>) {
        val snapshot = PlanSnapshot(
            planVersion = data["plan_version"]?.toIntOrNull() ?: 0,
            quietWindows = data["quiet_windows"] ?: "[]",
            upcoming = data["upcoming"] ?: "[]",
            graceSeconds = data["local_backup_grace_seconds"]?.toIntOrNull() ?: 90,
            expireGraceMinutes = data["expire_grace_minutes"]?.toIntOrNull() ?: 15,
            reshowMinutes = data["reshow_minutes"]?.toIntOrNull() ?: DEFAULT_RESHOW_MINUTES,
        )
        AppDatabase.dao(context).savePlan(snapshot)
        LocalScheduler.rescheduleAll(context)
    }

    /** 로컬 백업 알람 발화(블록 시작 + grace). 서버 개입을 못 받았을 때만 화면을 띄운다. */
    fun onLocalBackup(context: Context, blockId: Long) {
        val dao = AppDatabase.dao(context)
        if (dao.serverInterventionForBlock(blockId) != null) return
        val plan = dao.plan() ?: return
        val item = upcoming(plan).firstOrNull { it.block_id == blockId } ?: return
        val now = Instant.now()
        if (!now.isBefore(OffsetDateTime.parse(item.end).toInstant())) return
        if (inQuiet(plan, now)) return

        val entity = InterventionEntity(
            id = localId(blockId),
            blockId = blockId,
            level = 3,
            taskTitle = item.task_title,
            screenText = item.screen_text,
            ttsText = item.screen_text,
            reshowMinutes = plan.reshowMinutes,
            blockEnd = item.end,
            expireAt = OffsetDateTime.parse(item.end).plusMinutes(plan.expireGraceMinutes.toLong()).toString(),
            status = "running",
            fromServer = false,
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsertIntervention(entity)
        Notifier.showFullScreen(context, entity)
        Reshow.schedule(context, entity.id, entity.reshowMinutes)
        ActionQueue.enqueue(context, "local_fired", entity.id, blockId)
    }

    /** [시작] */
    fun onUserStart(context: Context, entity: InterventionEntity) {
        Notifier.cancel(context)
        Reshow.cancel(context)
        AppDatabase.dao(context).endIntervention(entity.id, localId(entity.blockId), System.currentTimeMillis())
        ActionQueue.enqueue(context, "start", entity.id, entity.blockId)
    }

    /** 뒤로가기·홈으로 나감. 재표시는 계속된다. */
    fun onUserLeft(context: Context, entity: InterventionEntity) {
        ActionQueue.enqueue(context, "dismiss", entity.id, entity.blockId)
        val current = AppDatabase.dao(context).intervention(entity.id)
        if (current?.status == "running") {
            Reshow.schedule(context, entity.id, entity.reshowMinutes)
        }
    }

    /**
     * 화면을 다시 띄워도 되는지. 서버 expire(블록 끝 + 유예)·suspend(조용한 시간) 시각이 지났으면
     * FCM 의 intervention.end 를 못 받았더라도 여기서 스스로 끝낸다(알림 제거, 상태 ended).
     */
    fun stillActive(context: Context, entity: InterventionEntity): Boolean {
        if (entity.status != "running") return false
        val now = Instant.now()
        val expired = entity.expireAt?.let { !now.isBefore(OffsetDateTime.parse(it).toInstant()) } ?: false
        val quiet = AppDatabase.dao(context).plan()?.let { inQuiet(it, now) } ?: false
        if (expired || quiet) {
            AppDatabase.dao(context).endIntervention(entity.id, localId(entity.blockId), System.currentTimeMillis())
            Notifier.cancel(context)
            Reshow.cancel(context)
            return false
        }
        return true
    }

    fun localId(blockId: Long) = "local:$blockId"

    fun upcoming(plan: PlanSnapshot): List<UpcomingItem> =
        runCatching { AppJson.decodeFromString<List<UpcomingItem>>(plan.upcoming) }.getOrDefault(emptyList())

    fun inQuiet(plan: PlanSnapshot, now: Instant): Boolean {
        val windows = runCatching { AppJson.decodeFromString<List<QuietWindow>>(plan.quietWindows) }
            .getOrDefault(emptyList())
        return windows.any {
            val from = OffsetDateTime.parse(it.from).toInstant()
            val to = OffsetDateTime.parse(it.to).toInstant()
            !now.isBefore(from) && now.isBefore(to)
        }
    }
}
