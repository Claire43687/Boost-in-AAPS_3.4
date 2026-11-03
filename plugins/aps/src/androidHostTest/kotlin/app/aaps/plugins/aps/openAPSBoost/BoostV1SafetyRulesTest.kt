package app.aaps.plugins.aps.openAPSBoost

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test

/**
 * Worked numbers for the V1 dose-path rules in BoostV1SafetyRules.kt (audit of 8 October 2026,
 * items 5, 19 and 20). BoostV1SafetyEngineTest covers the same rules through determine_basal.
 */
class BoostV1SafetyRulesTest {

    // Item 5: max_iob 3.0, IOB 2.0 leave 1.0 U; IOB 2.73 leaves 0.27, floored to 0.25 at 0.05 U.
    @Test fun `SMB is clamped to the max_iob headroom and floored to the increment`() {
        assertThat(boostSmbWithinMaxIob(2.5, 3.0, 2.0, 0.05)).isWithin(1e-9).of(1.0)
        assertThat(boostSmbWithinMaxIob(2.5, 3.0, 2.73, 0.05)).isWithin(1e-9).of(0.25)
        assertThat(boostSmbWithinMaxIob(2.5, 3.0, 2.73, 0.1)).isWithin(1e-9).of(0.2)
    }

    @Test fun `SMB inside the headroom is returned unchanged`() {
        assertThat(boostSmbWithinMaxIob(0.65, 3.0, 2.0, 0.05)).isEqualTo(0.65)
        assertThat(boostSmbWithinMaxIob(1.0, 3.0, 2.0, 0.05)).isEqualTo(1.0)
        assertThat(boostSmbWithinMaxIob(0.0, 3.0, 2.0, 0.05)).isEqualTo(0.0)
    }

    @Test fun `no headroom gives no SMB`() {
        assertThat(boostSmbWithinMaxIob(0.5, 3.0, 3.0, 0.05)).isEqualTo(0.0)
        assertThat(boostSmbWithinMaxIob(0.5, 3.0, 3.4, 0.05)).isEqualTo(0.0)
        assertThat(boostSmbWithinMaxIob(0.5, Double.NaN, 1.0, 0.05)).isEqualTo(0.0)
    }

    // Item 19: the hold no longer depends on a recent low. The three remaining conditions are unchanged.
    @Test fun `G3 hold conditions`() {
        assertThat(boostG3HoldConditionsMet(0.0, 6.0, 4.0)).isTrue()
        assertThat(boostG3HoldConditionsMet(1.0, 6.0, 4.0)).isFalse()
        assertThat(boostG3HoldConditionsMet(0.0, 4.9, 4.0)).isFalse()
        assertThat(boostG3HoldConditionsMet(0.0, 6.0, 2.9)).isFalse()
    }

    // Item 20.
    @Test fun `spike override only on tier 8 and never in the post-rescue window or under ML downgrade`() {
        assertThat(boostSpikeOverrideAllowed(true, "REGULAR_OREF1", false, false)).isTrue()
        assertThat(boostSpikeOverrideAllowed(false, "REGULAR_OREF1", false, false)).isFalse()
        assertThat(boostSpikeOverrideAllowed(true, "REGULAR_OREF1", true, false)).isFalse()
        assertThat(boostSpikeOverrideAllowed(true, "REGULAR_OREF1", false, true)).isFalse()
        for (t in listOf("ACCELERATION", "ENHANCED_OREF1", "UAM_BOOST", "PERCENT_SCALE", "COB_PRIMARY", "NONE", null))
            assertWithMessage("tier $t").that(boostSpikeOverrideAllowed(true, t, false, false)).isFalse()
    }

    // The accelerating-rise lowering to 65.
    @Test fun `threshold is lowered to 65 only on an accelerating rise`() {
        assertThat(boostAccelLoweredThreshold(70.0, 6.0, 3.0, null)).isEqualTo(65.0)
        assertThat(boostAccelLoweredThreshold(60.0, 6.0, 3.0, null)).isEqualTo(65.0)   // as before: 65 on any accelerating rise
        assertThat(boostAccelLoweredThreshold(70.0, -1.0, -3.0, null)).isEqualTo(70.0) // slowing fall: unchanged
        assertThat(boostAccelLoweredThreshold(70.0, 0.0, -3.0, null)).isEqualTo(70.0)  // flat after a fall: unchanged
        assertThat(boostAccelLoweredThreshold(70.0, 3.0, 6.0, null)).isEqualTo(70.0)   // slowing rise: unchanged
        assertThat(boostAccelLoweredThreshold(70.0, 6.0, 0.0, null)).isEqualTo(70.0)   // short average zero: delta_accl is 0
        assertThat(boostAccelLoweredThreshold(80.0, 6.0, 3.0, 80)).isEqualTo(80.0)     // never below the LGS setting
        assertThat(boostAccelLoweredThreshold(80.0, -1.0, -3.0, 80)).isEqualTo(80.0)
    }
}
