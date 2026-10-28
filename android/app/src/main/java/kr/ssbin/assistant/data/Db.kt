package kr.ssbin.assistant.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert

/**
 * 받은 개입. id 는 서버 intervention_id, 또는 로컬 백업이 띄운 "local:{block_id}".
 * status: running | ended
 */
@Entity(tableName = "interventions")
data class InterventionEntity(
    @PrimaryKey val id: String,
    val blockId: Long,
    val level: Int,
    val taskTitle: String,
    val screenText: String,
    val ttsText: String,
    val reshowMinutes: Int,
    val blockEnd: String?,
    val status: String,
    val fromServer: Boolean,
    val updatedAt: Long,
)

@Entity(tableName = "pending_actions")
data class PendingAction(
    @PrimaryKey val idempotencyKey: String,
    val json: String,
    val createdAt: Long,
)

/** 마지막 plan.sync (행 1개). quietWindows·upcoming 은 JSON 문자열 그대로. */
@Entity(tableName = "plan")
data class PlanSnapshot(
    @PrimaryKey val id: Int = 1,
    val planVersion: Int,
    val quietWindows: String,
    val upcoming: String,
    val graceSeconds: Int,
    val reshowMinutes: Int,
)

/** 메인 스레드가 아닌 곳(서비스·리시버 스레드·IO 디스패처)에서만 부른다. */
@Dao
interface AssistantDao {
    @Upsert
    fun upsertIntervention(entity: InterventionEntity)

    @Query("SELECT * FROM interventions WHERE id = :id")
    fun intervention(id: String): InterventionEntity?

    @Query("SELECT * FROM interventions WHERE blockId = :blockId AND fromServer = 1 LIMIT 1")
    fun serverInterventionForBlock(blockId: Long): InterventionEntity?

    @Query("DELETE FROM interventions WHERE id = :id")
    fun deleteIntervention(id: String)

    @Query("UPDATE interventions SET status = 'ended', updatedAt = :now WHERE id = :id OR id = :localId")
    fun endIntervention(id: String, localId: String, now: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAction(action: PendingAction)

    @Query("SELECT * FROM pending_actions ORDER BY createdAt")
    fun pendingActions(): List<PendingAction>

    @Query("DELETE FROM pending_actions WHERE idempotencyKey = :key")
    fun deleteAction(key: String)

    @Upsert
    fun savePlan(plan: PlanSnapshot)

    @Query("SELECT * FROM plan WHERE id = 1")
    fun plan(): PlanSnapshot?
}

@Database(
    entities = [InterventionEntity::class, PendingAction::class, PlanSnapshot::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AssistantDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun dao(context: Context): AssistantDao = (instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "assistant.db")
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }).dao()
    }
}
