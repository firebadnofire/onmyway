package org.archuser.onmyway.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "notification_events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean,
    val triggerType: String,
    val notificationTitle: String?,
    val notificationBody: String,
    val oneTime: Boolean,
    val invert: Boolean,
    val customSoundEnabled: Boolean,
    val customSoundUri: String?,
    val createdAt: Long,
    val lastTriggeredAt: Long?,
    val triggerState: String,
    val baselineLatitude: Double?,
    val baselineLongitude: Double?,
    val baselineAltitudeMeters: Double?,
    val baselineHorizontalAccuracyMeters: Float?,
    val baselineVerticalAccuracyMeters: Float?,
    val baselineTimestampMillis: Long?,
)

@Entity(
    tableName = "trigger_configs",
    foreignKeys = [ForeignKey(
        entity = EventEntity::class,
        parentColumns = ["id"],
        childColumns = ["eventId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("eventId", unique = true)],
)
data class TriggerConfigEntity(
    @PrimaryKey val eventId: Long,
    val textValue: String?,
    val numberValue1: Double?,
    val numberValue2: Double?,
    val numberValue3: Double?,
    val optionValue: String?,
    val booleanValue: Boolean?,
    val additionalTargets: String? = null,
)

@Entity(tableName = "trigger_history", indices = [Index("triggeredAt")])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: Long,
    val eventLabel: String,
    val triggerSummary: String,
    val triggeredAt: Long,
)

data class EventWithConfig(
    @Embedded val event: EventEntity,
    @Relation(parentColumn = "id", entityColumn = "eventId") val config: TriggerConfigEntity,
)

@Dao
abstract class EventDao {
    @Transaction
    @Query("SELECT * FROM notification_events ORDER BY enabled DESC, createdAt DESC")
    abstract fun observeEvents(): Flow<List<EventWithConfig>>

    @Transaction
    @Query("SELECT * FROM notification_events WHERE enabled = 1 ORDER BY id")
    abstract suspend fun enabledEvents(): List<EventWithConfig>

    @Transaction
    @Query("SELECT * FROM notification_events WHERE enabled = 0 ORDER BY id")
    abstract suspend fun disabledEvents(): List<EventWithConfig>

    @Transaction
    @Query("SELECT * FROM notification_events WHERE id = :id")
    abstract suspend fun event(id: Long): EventWithConfig?

    @Insert
    abstract suspend fun insertEvent(event: EventEntity): Long

    @Update
    abstract suspend fun updateEvent(event: EventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertConfig(config: TriggerConfigEntity)

    @Transaction
    open suspend fun save(event: EventEntity, config: TriggerConfigEntity): Long {
        val id = if (event.id == 0L) insertEvent(event) else {
            updateEvent(event)
            event.id
        }
        upsertConfig(config.copy(eventId = id))
        return id
    }

    @Query("UPDATE notification_events SET enabled = :enabled, triggerState = 'UNKNOWN', baselineLatitude = NULL, baselineLongitude = NULL, baselineAltitudeMeters = NULL, baselineHorizontalAccuracyMeters = NULL, baselineVerticalAccuracyMeters = NULL, baselineTimestampMillis = NULL WHERE id = :id")
    abstract suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM notification_events WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("SELECT * FROM trigger_history ORDER BY triggeredAt DESC LIMIT 200")
    abstract fun observeHistory(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM trigger_history ORDER BY triggeredAt DESC LIMIT 200")
    abstract suspend fun historySnapshot(): List<HistoryEntity>

    @Insert
    abstract suspend fun insertHistory(history: HistoryEntity)

    @Query("DELETE FROM trigger_history WHERE id NOT IN (SELECT id FROM trigger_history ORDER BY triggeredAt DESC LIMIT 200)")
    abstract suspend fun pruneHistory()

    @Query("DELETE FROM trigger_history")
    abstract suspend fun clearHistory()

    @Query("DELETE FROM notification_events")
    abstract suspend fun clearEvents()
}

@Database(
    entities = [EventEntity::class, TriggerConfigEntity::class, HistoryEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    companion object {
        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "onmyway.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE trigger_configs ADD COLUMN additionalTargets TEXT")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE notification_events ADD COLUMN invert INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE notification_events ADD COLUMN customSoundEnabled INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE notification_events ADD COLUMN customSoundUri TEXT")
            }
        }
    }
}
