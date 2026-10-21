package com.openjarvis.automation

import android.content.Context
import androidx.room.*
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

@Entity(tableName = "automations")
data class AutomationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val command: String,
    val scheduleType: String,
    val scheduleHour: Int = 0,
    val scheduleMinute: Int = 0,
    val scheduleDayOfWeek: Int = 0,
    val scheduleIntervalMs: Long = 0,
    val enabled: Boolean = true,
    val lastRun: Long? = null,
    val lastResult: String? = null,
    val runCount: Int = 0
)

@Dao
interface AutomationDao {
    @Query("SELECT * FROM automations ORDER BY name")
    suspend fun getAll(): List<AutomationEntity>

    @Query("SELECT * FROM automations WHERE id = :id")
    suspend fun getById(id: String): AutomationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(automation: AutomationEntity)

    @Update
    suspend fun update(automation: AutomationEntity)

    @Query("DELETE FROM automations WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [AutomationEntity::class], version = 1)
abstract class AutomationDB : RoomDatabase() {
    abstract fun automationDao(): AutomationDao

    companion object {
        @Volatile private var INSTANCE: AutomationDB? = null

        fun getInstance(context: Context): AutomationDB {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AutomationDB::class.java,
                    "automations.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

internal fun AutomationEntity.toAutomation(): AutomationManager.Automation {
    val savedSchedule = when (scheduleType.lowercase()) {
        "daily" -> AutomationManager.AutomationSchedule.Daily(
            scheduleHour, scheduleMinute
        )
        "weekly" -> AutomationManager.AutomationSchedule.Weekly(
            scheduleDayOfWeek, scheduleHour, scheduleMinute
        )
        "once" -> AutomationManager.AutomationSchedule.Once(scheduleIntervalMs)
        else -> AutomationManager.AutomationSchedule.Interval(
            scheduleIntervalMs.coerceAtLeast(1L)
        )
    }

    return AutomationManager.Automation(
        id = id,
        name = name,
        command = command,
        schedule = savedSchedule,
        enabled = enabled,
        lastRun = lastRun,
        lastResult = lastResult,
        runCount = runCount
    )
}

internal fun AutomationManager.Automation.toEntity(): AutomationEntity {
    val type: String
    val hour: Int
    val minute: Int
    val day: Int
    val interval: Long

    when (val savedSchedule = schedule) {
        is AutomationManager.AutomationSchedule.Daily -> {
            type = "daily"
            hour = savedSchedule.hour
            minute = savedSchedule.minute
            day = 0
            interval = 0L
        }
        is AutomationManager.AutomationSchedule.Weekly -> {
            type = "weekly"
            hour = savedSchedule.hour
            minute = savedSchedule.minute
            day = savedSchedule.dayOfWeek
            interval = 0L
        }
        is AutomationManager.AutomationSchedule.Interval -> {
            type = "interval"
            hour = 0
            minute = 0
            day = 0
            interval = savedSchedule.intervalMs
        }
        is AutomationManager.AutomationSchedule.Once -> {
            type = "once"
            hour = 0
            minute = 0
            day = 0
            interval = savedSchedule.atMs
        }
    }

    return AutomationEntity(
        id = id,
        name = name,
        command = command,
        scheduleType = type,
        scheduleHour = hour,
        scheduleMinute = minute,
        scheduleDayOfWeek = day,
        scheduleIntervalMs = interval,
        enabled = enabled,
        lastRun = lastRun,
        lastResult = lastResult,
        runCount = runCount
    )
}

class AutomationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString("automation_id") ?: return Result.failure()
        inputData.getString("automation_command") ?: return Result.failure()

        return try {
            val dao = AutomationDB.getInstance(applicationContext).automationDao()
            val automation = dao.getById(id) ?: return Result.failure()

            kotlinx.coroutines.delay(2000)

            dao.update(
                automation.copy(
                    lastRun = System.currentTimeMillis(),
                    lastResult = "success",
                    runCount = automation.runCount + 1
                )
            )
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }
}
