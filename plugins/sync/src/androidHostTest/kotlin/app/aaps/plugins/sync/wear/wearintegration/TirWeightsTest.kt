package app.aaps.plugins.sync.wear.wearintegration

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TirWeightsTest {

    @Test
    fun `no readings gives an empty string`() {
        assertThat(TirWeights.format(emptyList())).isEmpty()
    }

    @Test
    fun `only sensor error codes give an empty string`() {
        assertThat(TirWeights.format(listOf(38.0, 10.0, 0.0))).isEmpty()
    }

    @Test
    fun `band edges fall into the AGP bands`() {
        // 53 very low; 54 and 69 low; 70 and 180 in range; 181 and 250 high; 251 very high; 38 ignored
        val values = listOf(38.0, 53.0, 54.0, 69.0, 70.0, 180.0, 181.0, 250.0, 251.0, 39.0)
        // 39 counts as very low. Counted: 53, 39 (vlow 2); 54, 69 (low 2); 70, 180 (in 2); 181, 250 (high 2); 251 (vhigh 1)
        assertThat(TirWeights.format(values)).isEqualTo("22,22,22,22,11")
    }

    @Test
    fun `all in range is 100 percent in range`() {
        assertThat(TirWeights.format(List(288) { 120.0 })).isEqualTo("0,0,100,0,0")
    }

    @Test
    fun `each band is rounded on its own`() {
        // 1 of 3 per band: 33.33 rounds to 33, sum 99
        assertThat(TirWeights.format(listOf(60.0, 120.0, 200.0))).isEqualTo("0,33,33,33,0")
        // 1 of 8 = 12.5 rounds half up to 13, 7 of 8 = 87.5 rounds to 88, sum 101
        assertThat(TirWeights.format(listOf(300.0) + List(7) { 100.0 })).isEqualTo("0,0,88,0,13")
    }
}
