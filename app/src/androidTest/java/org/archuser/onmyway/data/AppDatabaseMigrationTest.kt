package org.archuser.onmyway.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AppDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate2To3AddsDisabledSoundOverrideWithoutLosingRows() {
        helper.createDatabase(DATABASE_NAME, 2).use { database ->
            database.execSQL(
                """INSERT INTO notification_events
                    (id, name, enabled, triggerType, notificationBody, oneTime, invert, createdAt, triggerState)
                    VALUES (1, 'Existing', 1, 'CONNECTED_SSID', 'Body', 0, 0, 100, 'UNKNOWN')
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(DATABASE_NAME, 3, true, AppDatabase.MIGRATION_2_3).use { database ->
            database.query("SELECT name, customSoundEnabled, customSoundUri FROM notification_events WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals("Existing", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(true, cursor.isNull(2))
            }
        }
    }

    private companion object { const val DATABASE_NAME = "migration-test" }
}
