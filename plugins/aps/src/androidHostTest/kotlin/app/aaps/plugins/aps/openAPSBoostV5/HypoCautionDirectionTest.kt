package app.aaps.plugins.aps.openAPSBoostV5

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * fix — "Hypo Caution" was INVERTED: the old code raised the mlHypoRiskScale floor with
 * the knob, so a higher setting REMOVED hypo damping and dosed MORE. These tests pin the corrected
 * behaviour: higher knob ⇒ LOWER scale ⇒ LESS insulin at elevated ML hypo-risk, with knob 1.0 an
 * exact no-op vs the prior default calibration.
 */
class HypoCautionDirectionTest {

    @Test fun `knob 1_0 reproduces the prior default scale`() {
        // prior: max(0.5, 1 - (risk-0.3)/0.7). risk 0.45 → max(0.5, 0.7857) = 0.7857
        assertThat(mlHypoRiskScale(0.45, 1.0)).isWithin(1e-9).of(1.0 - (0.45 - 0.30) / 0.70)
        // risk 1.0 → floor 0.5
        assertThat(mlHypoRiskScale(1.0, 1.0)).isWithin(1e-9).of(0.50)
    }

    @Test fun `no damping below or at the threshold regardless of knob`() {
        assertThat(mlHypoRiskScale(0.30, 2.0)).isEqualTo(1.0)
        assertThat(mlHypoRiskScale(0.10, 2.0)).isEqualTo(1.0)
        assertThat(mlHypoRiskScale(null, 2.0)).isEqualTo(1.0)
    }

    @Test fun `higher knob means LESS insulin at elevated risk (the fix)`() {
        // mid band: higher caution must reduce the scale, not raise it
        val s10 = mlHypoRiskScale(0.45, 1.0)
        val s15 = mlHypoRiskScale(0.45, 1.5)
        val s20 = mlHypoRiskScale(0.45, 2.0)
        assertThat(s15).isLessThan(s10)
        assertThat(s20).isLessThan(s15)
        // high band: knob 2.0 deepens the cut below the old 0.5 floor
        assertThat(mlHypoRiskScale(0.65, 2.0)).isLessThan(0.50)
    }

    @Test fun `floor lowers with the knob`() {
        // risk 1.0 → reduction saturates at 1.0 → returns the (lowered) floor
        assertThat(mlHypoRiskScale(1.0, 2.0)).isWithin(1e-9).of(0.25)   // 0.50 / 2.0
        assertThat(mlHypoRiskScale(1.0, 1.5)).isWithin(1e-9).of(0.50 / 1.5)
    }

    @Test fun `the inverted regression is gone - knob 2 never doses more than knob 1`() {
        for (r in listOf(0.31, 0.4, 0.5, 0.6, 0.75, 0.9, 1.0)) {
            assertThat(mlHypoRiskScale(r, 2.0)).isAtMost(mlHypoRiskScale(r, 1.0))
        }
    }

    // At risk 1.0 the 0.30 budget floor held every Hypo Caution above about
    // 1.67 at the same dose. The floor now falls with the knob, so 2.0 gives 0.25 x insulinReq; 1.0 is unchanged.
    @Test fun `Hypo Caution above 1_67 still lowers the budget at high risk`() {
        val req = 2.0
        assertThat(aggressionBudget(req, 1.0, false, hypoCautionUserKnob = 1.0).budget).isWithin(1e-9).of(0.50 * req)
        assertThat(aggressionBudget(req, 1.0, false, hypoCautionUserKnob = 1.67).budget).isWithin(1e-3).of(0.50 / 1.67 * req)
        assertThat(aggressionBudget(req, 1.0, false, hypoCautionUserKnob = 2.0).budget).isWithin(1e-9).of(0.25 * req)
        // risk 0.65 in the post-exercise window at Hypo Caution 1.0: 0.5 x 0.5 = 0.25, still floored at 0.30 as before
        assertThat(aggressionBudget(req, 0.65, true, hypoCautionUserKnob = 1.0).budget).isWithin(1e-9).of(0.30 * req)
    }
}
