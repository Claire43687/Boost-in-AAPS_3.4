package app.aaps.core.nssdk.mapper

import app.aaps.core.nssdk.localmodel.treatment.EventType
import app.aaps.core.nssdk.localmodel.treatment.NSBolus
import app.aaps.core.nssdk.localmodel.treatment.NSICfg
import app.aaps.core.nssdk.nsSdkJson
import app.aaps.core.nssdk.remotemodel.RemoteTreatment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `icfg.insulinModel` on the Nightscout treatments feed: written only for a curve other than oref, so an oref
 * treatment is uploaded as before the field existed, and a document without it reads back as null (oref).
 */
class ICfgInsulinModelTest {

    private fun bolus(insulinModel: String?) = NSBolus(
        date = 1_700_000_000_000L, identifier = null, utcOffset = 0, isValid = true, eventType = EventType.MEAL_BOLUS,
        notes = null, pumpId = null, endId = null, pumpType = null, pumpSerial = null,
        insulin = 2.0, type = NSBolus.BolusType.NORMAL, isBasalInsulin = false,
        iCfg = NSICfg(insulinLabel = "Lyumjev PD 45m 8h U100", insulinEndTime = 28_800_000, insulinPeakTime = 2_700_000, concentration = 1.0, insulinModel = insulinModel)
    )

    private fun upload(bolus: NSBolus): String = nsSdkJson.encodeToString(RemoteTreatment.serializer(), bolus.toRemoteTreatment()!!)

    @Test
    fun lyumjevIsUploadedAndReadBack() {
        val json = upload(bolus("LYUMJEV_PD"))
        assertTrue(json.contains("\"insulinModel\":\"LYUMJEV_PD\""), json)
        val back = json.toNSTreatment() as NSBolus
        assertEquals("LYUMJEV_PD", back.iCfg?.insulinModel)
    }

    @Test
    fun orefLeavesTheKeyOut() {
        val json = upload(bolus(null))
        assertFalse(json.contains("insulinModel"), json)
        assertNull((json.toNSTreatment() as NSBolus).iCfg?.insulinModel)
    }

    @Test
    fun anOlderDocumentWithoutTheKeyReadsAsNull() {
        val withKey = upload(bolus("LYUMJEV_PD"))
        val older = withKey.replace(",\"insulinModel\":\"LYUMJEV_PD\"", "")
        assertFalse(older.contains("insulinModel"), older)
        val back = older.toNSTreatment() as NSBolus
        assertEquals(1.0, back.iCfg?.concentration)
        assertNull(back.iCfg?.insulinModel)
    }
}
