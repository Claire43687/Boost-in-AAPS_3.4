package app.aaps.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.aaps.database.di.databaseMigrations
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test

/** A Boost 3.4 PD profile switch through every real migration from the 3.4 schema. The row holds the profile's DIA, as 3.4 wrote it. */
class LyumjevPdUpgradeChainTest {

    private val directory = createTempDirectory("pd-chain")
    private val connection: SQLiteConnection = BundledSQLiteDriver().open("$directory/aaps.db")

    @AfterTest
    fun tearDown() {
        connection.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `a 3_4 PD profile switch arrives as the PD curve`() {
        val database = Json.parseToJsonElement(File("src/androidDeviceTest/assets/app.aaps.database.AppDatabase/32.json").readText()).jsonObject["database"]!!.jsonObject
        for (entity in database["entities"]!!.jsonArray) {
            val table = entity.jsonObject["tableName"]!!.jsonPrimitive.content
            connection.execSQL(entity.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            (entity.jsonObject["indices"] as? JsonArray)?.forEach { connection.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
        }
        connection.execSQL(
            "INSERT INTO profileSwitches (version, dateCreated, isValid, timestamp, utcOffset, basalBlocks, isfBlocks, icBlocks, targetBlocks, glucoseUnit, profileName, timeshift, percentage, duration, insulinLabel, insulinEndTime, peak) " +
                "VALUES (1, 0, 1, 1700000000000, 0, '[]', '[]', '[]', '[]', 'MGDL', 'A', 0, 100, 0, 'Lyumjev U200 Glucodynamic', 36000000, 2700000)"
        )

        var version = 32
        while (true) {
            val migration = databaseMigrations.firstOrNull { it.startVersion == version } ?: break
            migration.migrate(connection)
            version = migration.endVersion
        }

        assertThat(version).isAtLeast(37)
        connection.prepare("SELECT insulinLabel, insulinEndTime, insulinPeakTime, concentration, insulinModel FROM profileSwitches").use {
            check(it.step())
            assertThat(it.getText(0)).isEqualTo("Lyumjev U200 Glucodynamic")
            assertThat(it.getLong(1)).isEqualTo(28_800_000)
            assertThat(it.getLong(2)).isEqualTo(2_700_000)
            assertThat(it.getDouble(3)).isEqualTo(1.0)
            assertThat(it.getText(4)).isEqualTo("LYUMJEV_PD")
        }
    }
}
