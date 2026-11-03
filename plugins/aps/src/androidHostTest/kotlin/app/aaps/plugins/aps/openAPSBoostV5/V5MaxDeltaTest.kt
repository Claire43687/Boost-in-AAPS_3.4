package app.aaps.plugins.aps.openAPSBoostV5

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** V6's maxDelta gate measured as V1 measures it. */
class V5MaxDeltaTest {

    // BG 100, delta 28, short average 31: V1 disables SMB (31 > 30). V6 saw 28 and passed.
    @Test fun `the short average counts as V1 counts it`() {
        val maxDelta = v5MaxDelta(28.0, 31.0, 20.0)
        assertThat(maxDelta).isEqualTo(31.0)
        assertThat(maxDelta > MAX_DELTA_BG_RATIO_DISABLE * 100.0).isTrue()
    }

    @Test fun `the long average counts too`() {
        assertThat(v5MaxDelta(5.0, 6.0, 9.0)).isEqualTo(9.0)
    }

    // abs(delta) stays, so a steep fall trips the gate as it did before.
    @Test fun `a steep fall still counts`() {
        assertThat(v5MaxDelta(-35.0, -20.0, -10.0)).isEqualTo(35.0)
    }
}
