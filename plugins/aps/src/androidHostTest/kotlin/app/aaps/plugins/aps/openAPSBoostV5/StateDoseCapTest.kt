package app.aaps.plugins.aps.openAPSBoostV5

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [applyStateDoseCap]: RECOVERING, the cautious state, is held to the committed cap like COMMITTED, so
 * it never passes more per cycle than the full meal state. IDLE and OBSERVING stay uncapped here and are
 * bounded at V1's would-dose at the override seam.
 */
class StateDoseCapTest {

    @Test
    fun `RECOVERING is capped at the committed cap`() {
        assertThat(applyStateDoseCap(MealHypothesis.RECOVERING, 3.4, confirmedCapU = 2.5, committedCapU = 1.0)).isEqualTo(1.0)
    }

    @Test
    fun `RECOVERING below the committed cap is unchanged`() {
        assertThat(applyStateDoseCap(MealHypothesis.RECOVERING, 0.4, confirmedCapU = 2.5, committedCapU = 1.0)).isEqualTo(0.4)
    }

    @Test
    fun `CONFIRMED and COMMITTED keep their caps, IDLE and OBSERVING pass through`() {
        assertThat(applyStateDoseCap(MealHypothesis.CONFIRMED, 3.4, confirmedCapU = 2.5, committedCapU = 1.0)).isEqualTo(2.5)
        assertThat(applyStateDoseCap(MealHypothesis.COMMITTED, 3.4, confirmedCapU = 2.5, committedCapU = 1.0)).isEqualTo(1.0)
        assertThat(applyStateDoseCap(MealHypothesis.IDLE, 3.4, confirmedCapU = 2.5, committedCapU = 1.0)).isEqualTo(3.4)
        assertThat(applyStateDoseCap(MealHypothesis.OBSERVING, 3.4, confirmedCapU = 2.5, committedCapU = 1.0)).isEqualTo(3.4)
    }

    /** Through decide(): budget 10 U at RECOVERING 0.4x is 4 U before the cap; no brake or gate binds. */
    @Test
    fun `decide holds a large RECOVERING dose to the committed cap`() {
        val inputs = V5Inputs(
            delta = 2.0, shortAvgDelta = 2.0, deltaAccl = 0.0,
            bg = 250.0, eventualBg = 280.0, targetBg = 100.0, maxDelta = 2.0,
            minGuardBg = 150.0, minGuardThreshold = 80.0, deltaHistory = listOf(2.0, 2.0, 2.0),
            iob = 0.5, maxIob = 20.0, baseInsulinReq = 10.0, roundSmbTo = 0.05,
            enableSmbPreChecks = true, mlHypoRisk = null, mlMealLikely = 0.5, recentLowBg = 120.0,
            cumulativeRise30min = 60.0, hour = 12, exerciseActive = false, inPostExerciseWindow = false,
            asleep = false, committedCapU = 0.5, confirmedCapU = 2.5, postRescueWindow = false, v1WouldDoseU = null,
        )
        val state = V5PersistedState(
            mealHypothesis = MealHypothesisState(MealHypothesis.RECOVERING, ageCycles = 1, committedInSession = true)
        )
        val d = DetermineBasalBoostV5().decide(inputs, state)
        assertThat(d.mealHypothesis).isEqualTo(MealHypothesis.RECOVERING)
        assertThat(d.finalDose).isWithin(1e-9).of(0.5)
    }
}
