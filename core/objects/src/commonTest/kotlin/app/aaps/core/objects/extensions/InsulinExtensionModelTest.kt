package app.aaps.core.objects.extensions

import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.InsulinModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The ICfg JSON (insulin catalogue preference, ProfileSealed, client control) carries the curve model only when
 * it is not oref, so a stored catalogue of oref insulins serialises exactly as it did before the field existed.
 */
class InsulinExtensionModelTest {

    @Test
    fun lyumjevRoundTrips() {
        val iCfg = ICfg("Lyumjev PD 45m 8h U100", peak = 45, dia = 8.0, concentration = 1.0, model = InsulinModel.LYUMJEV_PD)
        val json = iCfg.toJsonObject()
        assertEquals("\"LYUMJEV_PD\"", json["insulinModel"].toString())
        assertEquals(InsulinModel.LYUMJEV_PD, ICfg.fromJsonObject(json).model)
    }

    @Test
    fun orefIsWrittenWithoutTheKey() {
        val json = ICfg("Rapid", peak = 75, dia = 8.0, concentration = 1.0).toJsonObject()
        assertFalse(json.containsKey("insulinModel"))
    }

    @Test
    fun aCatalogueEntryFromBeforeTheFieldReadsAsOref() {
        val stored = Json.parseToJsonElement(
            """{"insulinLabel":"Lyumjev 45m 8h U100","insulinEndTime":28800000,"insulinPeakTime":2700000,"concentration":1.0,"insulinNickname":"Lyumjev"}"""
        ) as JsonObject
        val iCfg = ICfg.fromJsonObject(stored)
        assertEquals(InsulinModel.OREF, iCfg.model)
        assertEquals(45, iCfg.peak)
    }
}
