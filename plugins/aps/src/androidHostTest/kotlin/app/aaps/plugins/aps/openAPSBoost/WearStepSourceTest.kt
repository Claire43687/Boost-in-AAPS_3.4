package app.aaps.plugins.aps.openAPSBoost

import app.aaps.core.data.model.SC
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Wear step bridge: reconstruct today's cumulative from the rolling 5-min windows in the
 * stepsCount table without double-counting overlapping samples, and detect a live (worn) feed.
 */
class WearStepSourceTest {

    private val DAY = 86_400_000L
    private fun sc(ts: Long, s5: Int) = SC(duration = 300_000L, timestamp = ts, steps5min = s5,
        steps10min = 0, steps15min = 0, steps30min = 0, steps60min = 0, steps180min = 0, device = "wear")

    @Test fun `cumulative sums one sample per watch bucket`() {
        val day0 = 100 * DAY
        // The watch reports bucket b from 2.5 to 7.5 min after b's start; three buckets, the first reported twice
        val list = listOf(
            sc(day0 + 180_000, 120),              // bucket 0, early report
            sc(day0 + 400_000, 140),              // bucket 0 again -> max 140, not 120+140
            sc(day0 + 480_000, 200),              // bucket 1
            sc(day0 + 780_000, 90)                // bucket 2
        )
        assertThat(WearStepSource.stepsToday(list, day0, day0 + 800_000)).isEqualTo(140 + 200 + 90)
    }

    // One 150-step walk at 03:00 (watch bucket 02:57:30 to 03:02:30), reported
    // every 40 s from 03:02:40 to 03:07:20 as the watch does. Keyed by wall-clock slot it fell into 03:00 and 03:05
    // and counted 300, enough to clear the 250-step wake threshold.
    @Test fun `a bucket the watch resends across a slot boundary counts once`() {
        val day0 = 100 * DAY
        val reportStart = day0 + 3 * 3_600_000L + 2 * 60_000L + 40_000L
        val list = (0 until 8).map { i -> sc(reportStart + i * 40_000L, 150) }
        assertThat(WearStepSource.stepsToday(list, day0, day0 + DAY)).isEqualTo(150)
    }

    @Test fun `freshness reflects a recent sample`() {
        val now = 100 * DAY
        assertThat(WearStepSource.isFresh(listOf(sc(now - 5 * 60_000, 50)), now)).isTrue()      // 5 min ago
        assertThat(WearStepSource.isFresh(listOf(sc(now - 30 * 60_000, 50)), now)).isFalse()    // 30 min ago
        assertThat(WearStepSource.isFresh(emptyList(), now)).isFalse()
    }

    @Test fun `resolution grace window keeps wear live through a short quiet spell`() {
        // anti-flap: strictly-stale-but-recent wear (e.g. 20 min quiet) must still
        // resolve as today's source — its reconstructed today-count is still valid — while a
        // genuinely-dead feed (> 30 min) is not.
        val now = 100 * DAY
        val quiet20m = listOf(sc(now - 20 * 60_000, 50))
        assertThat(WearStepSource.isFresh(quiet20m, now)).isFalse()          // strict freshness: stale
        assertThat(WearStepSource.isRecentlyFresh(quiet20m, now)).isTrue()   // resolution: still live
        assertThat(WearStepSource.isRecentlyFresh(listOf(sc(now - 31 * 60_000, 50)), now)).isFalse()
        assertThat(WearStepSource.isRecentlyFresh(emptyList(), now)).isFalse()
    }

    @Test fun `ignores samples outside the day window`() {
        val day0 = 100 * DAY
        val list = listOf(sc(day0 - 600_000, 999), sc(day0 + 120_000, 80))   // first is yesterday
        assertThat(WearStepSource.stepsToday(list, day0, day0 + 300_000)).isEqualTo(80)
    }
}
