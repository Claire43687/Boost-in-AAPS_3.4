package app.aaps.database

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.aaps.database.di.databaseMigrations
import app.aaps.database.entities.Bolus
import app.aaps.database.entities.embedments.InsulinConfiguration
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * migration36to37 adds `insulinModel` to the three tables that embed the insulin configuration.
 *
 * The instrumented tests use MigrationTestHelper, which needs a device. This does the same on the desktop JVM
 * with the bundled driver the phone uses: it builds a version 36 database from the exported 36.json, puts one
 * row in each table, then opens it through Room with the production migrations. Room checks the migrated schema
 * against the entities when it opens, so a column that differs from what the entity declares (type, NOT NULL
 * or default) fails here. The rows must survive and read back as OREF.
 */
class Migration36To37Test {

    private val directory = createTempDirectory("aaps-migration-test")
    private val path = "$directory/aaps.db"

    @AfterTest
    fun tearDown() {
        directory.toFile().deleteRecursively()
    }

    private fun schema36(): JsonObject {
        // Gradle runs the tests with the module directory as working directory.
        val file = File("src/androidDeviceTest/assets/app.aaps.database.AppDatabase/36.json")
        assertThat(file.exists()).isTrue()
        return Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
    }

    private fun createVersion36(connection: SQLiteConnection) {
        val database = schema36()
        for (entity in database["entities"]!!.jsonArray) {
            val table = entity.jsonObject["tableName"]!!.jsonPrimitive.content
            connection.execSQL(entity.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            (entity.jsonObject["indices"] as? JsonArray)?.forEach { index ->
                connection.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            }
        }
        for (query in database["setupQueries"]!!.jsonArray) connection.execSQL(query.jsonPrimitive.content)
        connection.execSQL("PRAGMA user_version = 36")
    }

    @Test
    fun `rows survive and read back as OREF`() = runTest {
        BundledSQLiteDriver().open(path).also { connection ->
            createVersion36(connection)
            connection.execSQL(
                "INSERT INTO boluses (id, version, dateCreated, isValid, timestamp, utcOffset, amount, type, isBasalInsulin, insulinLabel, insulinEndTime, insulinPeakTime, concentration) " +
                    "VALUES (1, 0, 0, 1, $TIMESTAMP, 0, 1.5, 'NORMAL', 0, 'Rapid', 28800000, 4500000, 1.0)"
            )
            connection.execSQL(
                "INSERT INTO profileSwitches (id, version, dateCreated, isValid, timestamp, utcOffset, basalBlocks, isfBlocks, icBlocks, targetBlocks, glucoseUnit, profileName, timeshift, percentage, duration, insulinLabel, insulinEndTime, insulinPeakTime, concentration) " +
                    "VALUES (1, 0, 0, 1, $TIMESTAMP, 0, '[]', '[]', '[]', '[]', 'MGDL', 'Default', 0, 100, 0, 'Rapid', 28800000, 4500000, 2.0)"
            )
            connection.execSQL(
                "INSERT INTO effectiveProfileSwitches (id, version, dateCreated, isValid, timestamp, utcOffset, basalBlocks, isfBlocks, icBlocks, targetBlocks, glucoseUnit, originalProfileName, originalCustomizedName, originalTimeshift, originalPercentage, originalDuration, originalEnd, insulinLabel, insulinEndTime, insulinPeakTime, concentration) " +
                    "VALUES (1, 0, 0, 1, $TIMESTAMP, 0, '[]', '[]', '[]', '[]', 'MGDL', 'Default', 'Default', 0, 100, 0, 0, 'Rapid', 28800000, 4500000, 1.0)"
            )
            connection.close()
        }

        val database = Room.databaseBuilder<AppDatabase>(name = path)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(*databaseMigrations)
            .fallbackToDestructiveMigration(false)
            .build()
        try {
            val bolus = database.bolusDao.findById(1)!!
            assertThat(bolus.amount).isEqualTo(1.5)
            assertThat(bolus.insulinConfiguration).isEqualTo(InsulinConfiguration("Rapid", 28_800_000, 4_500_000, 1.0, "OREF"))

            val profileSwitch = database.profileSwitchDao.findById(1)!!
            assertThat(profileSwitch.insulinConfiguration).isEqualTo(InsulinConfiguration("Rapid", 28_800_000, 4_500_000, 2.0, "OREF"))

            val effectiveProfileSwitch = database.effectiveProfileSwitchDao.findById(1)!!
            assertThat(effectiveProfileSwitch.insulinConfiguration).isEqualTo(InsulinConfiguration("Rapid", 28_800_000, 4_500_000, 1.0, "OREF"))

            // A new row keeps a non-default model.
            val id = database.bolusDao.insert(
                Bolus(
                    timestamp = TIMESTAMP + 1, amount = 2.0, type = Bolus.Type.NORMAL,
                    insulinConfiguration = InsulinConfiguration("Lyumjev PD", 28_800_000, 2_700_000, 1.0, "LYUMJEV_PD")
                )
            )
            assertThat(database.bolusDao.findById(id)!!.insulinConfiguration.insulinModel).isEqualTo("LYUMJEV_PD")
        } finally {
            database.close()
        }
    }

    /** The rows that were computed with PD: the label names them, and they hold the profile's DIA. */
    @Test
    fun `a Boost 3_4 PD row becomes the PD curve and other rows do not`() = runTest {
        BundledSQLiteDriver().open(path).also { connection ->
            createVersion36(connection)
            for ((id, label) in listOf(1 to "Lyumjev U200 Glucodynamic", 2 to "Lyumjev U100 Glucodynamic", 3 to "Lyumjev")) connection.execSQL(
                "INSERT INTO profileSwitches (id, version, dateCreated, isValid, timestamp, utcOffset, basalBlocks, isfBlocks, icBlocks, targetBlocks, glucoseUnit, profileName, timeshift, percentage, duration, insulinLabel, insulinEndTime, insulinPeakTime, concentration) " +
                    "VALUES ($id, 0, 0, 1, ${TIMESTAMP + id}, 0, '[]', '[]', '[]', '[]', 'MGDL', 'Default', 0, 100, 0, '$label', 36000000, 2700000, 1.0)"
            )
            connection.close()
        }
        val database = Room.databaseBuilder<AppDatabase>(name = path)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(*databaseMigrations)
            .fallbackToDestructiveMigration(false)
            .build()
        try {
            assertThat(database.profileSwitchDao.findById(1)!!.insulinConfiguration)
                .isEqualTo(InsulinConfiguration("Lyumjev U200 Glucodynamic", 28_800_000, 2_700_000, 1.0, "LYUMJEV_PD"))
            assertThat(database.profileSwitchDao.findById(2)!!.insulinConfiguration.insulinModel).isEqualTo("LYUMJEV_PD")
            assertThat(database.profileSwitchDao.findById(3)!!.insulinConfiguration)
                .isEqualTo(InsulinConfiguration("Lyumjev", 36_000_000, 2_700_000, 1.0, "OREF"))
        } finally {
            database.close()
        }
    }

    /** A bolus has no insulin in 3.4, so the start-up stamp is what gives it the curve. */
    @Test
    fun `the bulk stamp writes the model onto sentinel rows only`() = runTest {
        BundledSQLiteDriver().open(path).also { connection ->
            createVersion36(connection)
            for ((id, end) in listOf(1 to -1L, 2 to 28_800_000L)) connection.execSQL(
                "INSERT INTO boluses (id, version, dateCreated, isValid, timestamp, utcOffset, amount, type, isBasalInsulin, insulinLabel, insulinEndTime, insulinPeakTime, concentration) " +
                    "VALUES ($id, 0, 0, 1, ${TIMESTAMP + id}, 0, 1.5, 'NORMAL', 0, 'x', $end, 4500000, 1.0)"
            )
            connection.close()
        }
        val database = Room.databaseBuilder<AppDatabase>(name = path)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(*databaseMigrations)
            .fallbackToDestructiveMigration(false)
            .build()
        try {
            assertThat(database.bolusDao.countAwaitingInsulinConfig()).isEqualTo(1)
            assertThat(database.bolusDao.bulkMigrateInsulinConfig("Lyumjev PD", 28_800_000, 2_700_000, 1.0, "LYUMJEV_PD")).isEqualTo(1)
            assertThat(database.bolusDao.findById(1)!!.insulinConfiguration.insulinModel).isEqualTo("LYUMJEV_PD")
            assertThat(database.bolusDao.findById(2)!!.insulinConfiguration.insulinModel).isEqualTo("OREF")
            assertThat(database.bolusDao.countAwaitingInsulinConfig()).isEqualTo(0)
        } finally {
            database.close()
        }
    }

    companion object {

        private const val TIMESTAMP = 1_700_000_000_000L
    }
}
