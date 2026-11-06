package app.aaps.plugins.aps.openAPSBoostV5

import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.getBoostDosing
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Auto-config holds the "max minutes of basal to limit SMB" settings at or below Boost's values,
 * 15 for the standard setting and 20 for UAM.
 */
class SmbBasalMinutesCeilingTest {

    private val smb = IntKey.ApsMaxMinutesOfBasalToLimitSmb
    private val uam = IntKey.ApsUamMaxMinutesOfBasalToLimitSmb

    private fun run(start: Map<IntKey, Int>): Pair<Map<IntKey, Int>, List<Pair<IntKey, Int>>> {
        val store = start.toMutableMap()
        val lowered = BoostV5AutoConfigApply.capSmbBasalMinutes({ store.getValue(it) }, { k, v -> store[k] = v })
        return store to lowered
    }

    @Test fun `field case - both at 120 are lowered to 15 and 20 and the old values reported`() {
        val (store, lowered) = run(mapOf(smb to 120, uam to 120))
        assertThat(store).containsExactly(smb, 15, uam, 20)
        assertThat(lowered).containsExactly(smb to 120, uam to 120)
    }

    @Test fun `values at their ceilings are left alone and nothing is written`() {
        var writes = 0
        val start = mapOf(smb to 15, uam to 20)
        val lowered = BoostV5AutoConfigApply.capSmbBasalMinutes({ start.getValue(it) }, { _, _ -> writes++ })
        assertThat(lowered).isEmpty()
        assertThat(writes).isEqualTo(0)
    }

    @Test fun `the cohort's common 20 for UAM is kept, while 20 for the standard setting is lowered`() {
        val (store, lowered) = run(mapOf(smb to 20, uam to 20))
        assertThat(store).containsExactly(smb, 15, uam, 20)
        assertThat(lowered).containsExactly(smb to 20)
    }

    @Test fun `UAM at 30 is lowered to 20, not 15`() {
        val (store, _) = run(mapOf(smb to 15, uam to 30))
        assertThat(store[uam]).isEqualTo(20)
    }

    @Test fun `no ceiling sits below the key minimum`() {
        BoostV5AutoConfigApply.smbBasalMinutesCeilings.forEach { (key, ceiling) -> assertThat(ceiling).isAtLeast(key.min) }
    }

    // 4.0 defaults both keys to 30 and they are defaultedBySM, so get() reads 30 in Simple Mode whatever is stored.
    @Test fun `in Simple Mode a stored value at the ceiling is not rewritten on every cycle`() {
        val prefs = mock<Preferences>()
        for (k in listOf(smb, uam)) whenever(prefs.get(k as IntPreferenceKey)).thenReturn(30)
        whenever(prefs.getIfExists(smb as IntPreferenceKey)).thenReturn(15)
        whenever(prefs.getIfExists(uam as IntPreferenceKey)).thenReturn(20)
        val writes = mutableListOf<IntKey>()
        val lowered = BoostV5AutoConfigApply.capSmbBasalMinutes({ prefs.getBoostDosing(it) }, { k, _ -> writes += k })
        assertThat(lowered).isEmpty()
        assertThat(writes).isEmpty()
    }
}
