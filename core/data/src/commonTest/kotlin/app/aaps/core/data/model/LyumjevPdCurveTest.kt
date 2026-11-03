package app.aaps.core.data.model

import app.aaps.core.data.iob.Iob
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [InsulinModel.LYUMJEV_PD] in [ICfg.iobCalcForTreatment] to Boost 7.4.5.
 *
 * Two references. [boost612] is the 6.1.2 `InsulinLyumjevPDBasePlugin.iobCalcForTreatment` body copied
 * verbatim, fed the amount in pump units and the effective dose the 7.4.5 U100 or U200 plugin uses: the
 * amount on U100 and twice it on U200 (audit #23; 6.1.2 had the two swapped). The literal values come from
 * `resources/lyumjev_pd_reference.py`, an independent transcription, so a change that altered the copy and
 * the port alike would still fail.
 *
 * The plugins kept amounts in pump units; here they are IU (pump units x concentration). For U100 the two are
 * equal. For U200 a dose of 2u IU was u pump units, so the port must return exactly twice what the plugin
 * returned for u, both IOB and activity, at every time.
 */
class LyumjevPdCurveTest {

    private val minute = 60_000L

    private fun lyumjev(concentration: Double) =
        ICfg(insulinLabel = "Lyumjev PD", peak = ICfg.LYUMJEV_PD_NOMINAL_PEAK_MINUTES, dia = ICfg.LYUMJEV_PD_END_HOURS, concentration = concentration, model = InsulinModel.LYUMJEV_PD)

    private fun iob(iCfg: ICfg, amountIu: Double, minutes: Double): Iob =
        iCfg.iobCalcForTreatment(BS(timestamp = 0L, amount = amountIu, type = BS.Type.NORMAL, iCfg = iCfg), (minutes * minute).toLong())

    /** Boost 6.1.2 InsulinLyumjevPDBasePlugin.iobCalcForTreatment, verbatim apart from the parameters. */
    private fun boost612(amount: Double, effDose: Double, t: Double): Iob {
        val a0 = 61.33
        val a1 = 12.27
        val b1 = 0.05185
        val pdDiaMinutes = 8.0 * 60.0
        val result = Iob()
        if (amount != 0.0) {
            if (t < pdDiaMinutes) {
                val tp = (a0 + a1 * effDose) / (1 + b1 * effDose)
                val tpModel = tp.pow(2.0) * 2
                result.activityContrib = (2 * amount / tpModel) * t * exp(-t.pow(2.0) / tpModel)
                val lowerLimit = t
                val upperLimit = pdDiaMinutes
                result.iobContrib = amount * (exp(-lowerLimit.pow(2.0) / tpModel) - exp(-upperLimit.pow(2.0) / tpModel))
            }
        }
        return result
    }

    private val doses = listOf(0.05, 0.3, 1.0, 2.5, 5.0, 10.0)
    private val times = listOf(0.0, 5.0, 30.0, 60.0, 90.0, 120.0, 180.0, 240.0, 360.0, 479.0)

    private fun assertClose(expected: Double, actual: Double, what: String) {
        val tolerance = 1e-12 * maxOf(1.0, abs(expected))
        assertTrue(abs(expected - actual) <= tolerance, "$what: expected $expected but was $actual")
    }

    @Test
    fun `U100 matches the 7_4_5 U100 plugin`() {
        val iCfg = lyumjev(1.0)
        for (dose in doses) for (t in times) {
            val expected = boost612(amount = dose, effDose = dose, t = t)
            val actual = iob(iCfg, dose, t)
            assertClose(expected.iobContrib, actual.iobContrib, "iob dose=$dose t=$t")
            assertClose(expected.activityContrib, actual.activityContrib, "activity dose=$dose t=$t")
        }
    }

    @Test
    fun `U200 matches the 7_4_5 U200 plugin for the same physical dose`() {
        val iCfg = lyumjev(2.0)
        for (pumpUnits in doses) for (t in times) {
            val expected = boost612(amount = pumpUnits, effDose = 2 * pumpUnits, t = t)
            val actual = iob(iCfg, 2.0 * pumpUnits, t)
            assertClose(2.0 * expected.iobContrib, actual.iobContrib, "iob pumpUnits=$pumpUnits t=$t")
            assertClose(2.0 * expected.activityContrib, actual.activityContrib, "activity pumpUnits=$pumpUnits t=$t")
        }
    }

    @Test
    fun `effective dose is the insulin delivered`() {
        // 4 pump units of U100 are 4 IU and 4 pump units of U200 are 8 IU; the amount here is already IU.
        assertEquals(4.0, ICfg.lyumjevEffectiveDose(4.0))
        assertEquals(8.0, ICfg.lyumjevEffectiveDose(8.0))
    }

    @Test
    fun `the same insulin gives the same curve on U100 and U200`() {
        // 6.1.2 gave U100 four times U200's peak-time dose for the same insulin (audit #23).
        for (dose in doses) for (t in times) {
            val u100 = iob(lyumjev(1.0), dose, t)
            val u200 = iob(lyumjev(2.0), dose, t)
            assertEquals(u100.iobContrib, u200.iobContrib, "iob dose=$dose t=$t")
            assertEquals(u100.activityContrib, u200.activityContrib, "activity dose=$dose t=$t")
        }
    }

    @Test
    fun `pinned values from the independent transcription`() {
        // python3 -I core/data/src/commonTest/resources/lyumjev_pd_reference.py
        val u100 = lyumjev(1.0)
        assertClose(0.9121869064381016, iob(u100, 1.0, 30.0).iobContrib, "U100 1 U t=30 iob")
        assertClose(0.005589295652023078, iob(u100, 1.0, 30.0).activityContrib, "U100 1 U t=30 activity")
        assertClose(0.2297954077677354, iob(u100, 1.0, 120.0).iobContrib, "U100 1 U t=120 iob")
        assertClose(2.3415975047980626, iob(u100, 5.0, 120.0).iobContrib, "U100 5 U t=120 iob")
        assertClose(0.029605666525605995, iob(u100, 5.0, 120.0).activityContrib, "U100 5 U t=120 activity")
        assertClose(0.24049745748460816, iob(u100, 5.0, 240.0).iobContrib, "U100 5 U t=240 iob")

        // 5 IU of U200 is 2.5 pump units in the plugin; values here are twice the script's pump-unit values.
        val u200 = lyumjev(2.0)
        assertClose(2 * 2.0681117374455473, iob(u200, 5.0, 60.0).iobContrib, "U200 5 IU t=60 iob")
        assertClose(2 * 0.01307387852814373, iob(u200, 5.0, 60.0).activityContrib, "U200 5 IU t=60 activity")
        assertClose(2 * 1.1707987523990313, iob(u200, 5.0, 120.0).iobContrib, "U200 5 IU t=120 iob")
    }

    @Test
    fun `IOB starts at the dose and is zero from 8 h`() {
        for (concentration in listOf(1.0, 2.0)) {
            val iCfg = lyumjev(concentration)
            for (dose in listOf(0.1, 1.0, 3.0)) {
                // At t = 0 the IOB is the dose less exp(-480² / tpModel) of it: 1.2e-7 of the dose for 3 U
                // (X = 3, tp = 85 min), less for the smaller doses.
                val start = iob(iCfg, dose, 0.0).iobContrib
                assertTrue(abs(start - dose) < 1e-4 * dose, "conc=$concentration dose=$dose start=$start")
                assertEquals(0.0, iob(iCfg, dose, 0.0).activityContrib)
                assertEquals(0.0, iob(iCfg, dose, 480.0).iobContrib)
                assertEquals(0.0, iob(iCfg, dose, 480.0).activityContrib)
                assertEquals(0.0, iob(iCfg, dose, 600.0).iobContrib)
                // Just before the end the curve is effectively spent.
                assertTrue(iob(iCfg, dose, 479.0).iobContrib < 1e-6 * dose)
            }
        }
    }

    @Test
    fun `large doses keep the 6_1_2 truncation at 8 h`() {
        // A 20 U bolus has X = 20 and tp of about 151 min, so the closed form leaves 0.6 % of it off the
        // start and the curve drops to 0 at 8 h. This is the 6.1.2 behaviour and is kept; the test records it.
        val start = iob(lyumjev(1.0), 20.0, 0.0).iobContrib
        assertClose(boost612(20.0, 20.0, 0.0).iobContrib, start, "20 U t=0")
        assertClose(19.87568665153964, start, "20 U t=0, independent transcription")
    }

    @Test
    fun `peak and DIA of the ICfg do not change the Lyumjev curve`() {
        val a = ICfg("a", peak = 45, dia = 8.0, concentration = 1.0, model = InsulinModel.LYUMJEV_PD)
        val b = ICfg("b", peak = 75, dia = 5.0, concentration = 1.0, model = InsulinModel.LYUMJEV_PD)
        for (t in times) assertEquals(iob(a, 2.0, t).iobContrib, iob(b, 2.0, t).iobContrib)
    }

    @Test
    fun `the oref model is still the default and unchanged`() {
        val implicit = ICfg(insulinLabel = "test", peak = 75, dia = 6.0, concentration = 1.0)
        assertEquals(InsulinModel.OREF, implicit.model)
        // The oref curve from 4.0.0-beta1, evaluated independently: 1 U, peak 75, DIA 6 h, at 60 min.
        val td = 360.0
        val tp = 75.0
        val tau = tp * (1 - tp / td) / (1 - 2 * tp / td)
        val a = 2 * tau / td
        val s = 1 / (1 - a + (1 + a) * exp(-td / tau))
        val t = 60.0
        val expectedIob = 1 - s * (1 - a) * ((t.pow(2.0) / (tau * td * (1 - a)) - t / tau - 1) * exp(-t / tau) + 1)
        assertEquals(expectedIob, iob(implicit, 1.0, t).iobContrib)
    }

    @Test
    fun `model takes part in equality and survives deepClone`() {
        val oref = ICfg("x", peak = 45, dia = 8.0, concentration = 1.0)
        val pd = ICfg("x", peak = 45, dia = 8.0, concentration = 1.0, model = InsulinModel.LYUMJEV_PD)
        assertTrue(!oref.isEqual(pd))
        assertTrue(pd.isEqual(pd.deepClone()))
        assertEquals(InsulinModel.LYUMJEV_PD, pd.deepClone().model)
    }

    @Test
    fun `stored names read back and unknown or missing ones are oref`() {
        assertEquals(InsulinModel.LYUMJEV_PD, InsulinModel.fromStored("LYUMJEV_PD"))
        assertEquals(InsulinModel.OREF, InsulinModel.fromStored("OREF"))
        assertEquals(InsulinModel.OREF, InsulinModel.fromStored(null))
        assertEquals(InsulinModel.OREF, InsulinModel.fromStored("SOMETHING_NEWER"))
    }
}
