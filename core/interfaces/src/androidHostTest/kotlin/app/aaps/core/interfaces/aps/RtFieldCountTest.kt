package app.aaps.core.interfaces.aps

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * Pins the number of instance fields on [RT].
 *
 * RT is constructed inside determine_basal in every engine in this repository, and the largest of
 * those, DetermineBasalBoostV3MLG3.determine_basal, sits against the ART method verifier's ceiling.
 * One extra RT field costs one extra register in each of those methods, and the V3MLG3 one has no
 * headroom left: a build with 274 registers there crashed at startup, where 273 runs.
 * The failure gives no stack trace and no log line, because it happens before the app starts.
 *
 * So a new field here is not a local change to a data class. If telemetry needs to reach Nightscout,
 * append a "tag=value; " to the existing reason string instead, as the KAIROS twin, the tranche and
 * the shadow hypo score all do, and parse it in the extractor. That costs no registers.
 *
 * If this test fails because a field was added, do not simply update the number. Move the value into
 * the reason string. If a field genuinely has to exist, first rebuild and check with dexdump that
 * DetermineBasalBoostV3MLG3.determine_basal has not gone above 273 registers.
 */
class RtFieldCountTest {

    @Test fun rtCarriesNoMoreFieldsThanTheVerifierAllows() {
        val instanceFields = RT::class.java.declaredFields.count { !Modifier.isStatic(it.modifiers) }
        assertThat(instanceFields).isEqualTo(EXPECTED_INSTANCE_FIELDS)
    }

    // Results stored locally and in Nightscout by 7.4.2 and earlier carry keys for the shadow fields
    // removed in 7.4.3. They must still load, through RT.deserialize, after the upgrade.
    @Test fun jsonWithRemovedShadowFieldsStillDeserialises() {
        val old = """{"algorithm":"BOOST","runningDynamicIsf":true,"bg":142.0,"units":0.4,""" +
            """"reason":"prTrial=0,control,0.0; ","isfShadow_ratioRaw":1.02,"isfShadow_deltaPct":-3.1,""" +
            """"boostVwa_blend":31.5,"boostVwa_curveDays":7,"boostV7_pool":"quiet_flat(n=812)",""" +
            """"boostV7_wouldDoseR4":0.3,"boostAutosens_mode":"tdd","boostAutosens_appliedRatio":1.0,""" +
            """"boostSteps_feed":"phone"}"""
        val rt = RT.deserialize(old)
        assertThat(rt.bg).isEqualTo(142.0)
        assertThat(rt.units).isEqualTo(0.4)
        assertThat(rt.reason.toString()).isEqualTo("prTrial=0,control,0.0; ")
        assertThat(rt.boostSteps_feed).isEqualTo("phone")
        assertThat(RT.deserialize(rt.serialize()).units).isEqualTo(0.4)
    }

    companion object {

        // Measured on the build that runs. Raising this number has crashed the app before.
        // 100 on Boost 7.4.3, which removed 26 shadow fields (isfShadow_ 7, boostVwa_ 8, boostV7_ 7,
        // boostAutosens_ 4). 109 on this port, which keeps boostVwa_ (8) and has one 4.0 field. The
        // port has no V3MLG3 engine; DetermineBasalBoost.determine_basal is the method at risk here.
        private const val EXPECTED_INSTANCE_FIELDS = 109
    }
}
