package app.aaps.plugins.aps.openAPSBoostV5

import app.aaps.plugins.aps.openAPSBoostV5.InsulinNeedWatch.Day
import app.aaps.plugins.aps.openAPSBoostV5.InsulinNeedWatch.Status
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The insulin-need watch. A 38-day series of daily insulin (a rescaled example, units of insulin) pins the case the rule was chosen on;
 * the rest pin the gap, profile-change and cooldown behaviour a notification depends on.
 */
class InsulinNeedWatchTest {

    private val dayMs = 24L * 60 * 60 * 1000
    private val t0 = 1_788_652_800_000L      // a midnight; only differences matter

    /** 6 September to 13 October 2026. */
    private val october = listOf(
        35.7, 44.1, 35.3, 32.5, 30.7, 49.2, 47.9, 42.6, 41.7, 29.7, 36.5, 34.8, 45.2, 36.3, 38.2, 47.1, 36.5, 42.6, 44.0,
        28.6, 35.6, 40.2, 40.8, 40.8, 41.0, 33.4, 44.3, 39.9, 37.1, 44.3, 43.5, 33.1, 43.6, 59.6, 41.2, 54.5, 50.6, 39.4
    )

    private fun days(tdds: List<Double>, basal: (Int) -> Double? = { 8.0 }) =
        tdds.mapIndexed { i, x -> Day(t0 + i * dayMs, x, basal(i)) }

    @Test fun `a sustained rise is flagged on 12 October and not on 11 October`() {
        // index 36 is 12 October, index 35 is 11 October
        val on12 = InsulinNeedWatch.evaluate(days(october.take(37)))
        assertThat(on12.status).isEqualTo(Status.ABOVE)
        assertThat(on12.recentMeanU).isWithin(0.05).of((43.6 + 59.6 + 41.2 + 54.5 + 50.6) / 5)
        assertThat(on12.baselineDays).isEqualTo(30)
        assertThat(on12.z).isGreaterThan(1.5)
        assertThat(InsulinNeedWatch.evaluate(days(october.take(36))).status).isEqualTo(Status.WITHIN)
    }

    @Test fun `a steady month is within and a sustained fall is below`() {
        val steady = List(35) { 30.0 + (it % 5) - 2 }
        assertThat(InsulinNeedWatch.evaluate(days(steady)).status).isEqualTo(Status.WITHIN)
        val falling = steady.dropLast(5) + listOf(22.0, 21.0, 23.0, 22.0, 24.0)
        assertThat(InsulinNeedWatch.evaluate(days(falling)).status).isEqualTo(Status.BELOW)
    }

    @Test fun `too short a month gives no verdict`() {
        val r = InsulinNeedWatch.evaluate(days(List(15) { 30.0 + (it % 3) }))
        assertThat(r.status).isEqualTo(Status.FEW_DAYS)
    }

    @Test fun `a near-empty day is a gap in the record, not a dose`() {
        val base = List(30) { 30.0 + (it % 5) - 2 }
        // a gap in the window leaves four usable days: no verdict rather than a BELOW
        assertThat(InsulinNeedWatch.evaluate(days(base + listOf(30.0, 31.0, 2.0, 31.0, 30.0))).status).isEqualTo(Status.FEW_DAYS)
        // a gap in the month is left out, so it does not widen the spread and hide a real rise
        val withGap = base.toMutableList().also { it[10] = 1.5 } + listOf(40.0, 41.0, 42.0, 41.0, 40.0)
        val r = InsulinNeedWatch.evaluate(days(withGap))
        assertThat(r.baselineDays).isEqualTo(29)
        assertThat(r.status).isEqualTo(Status.ABOVE)
    }

    @Test fun `a profile change is reported as that, not as a change in need`() {
        // undiluted U200 halves the pump units of the profile and of every dose
        val tdds = List(30) { 30.0 + (it % 5) - 2 } + listOf(16.0, 15.0, 15.5, 16.0, 15.0)
        val r = InsulinNeedWatch.evaluate(days(tdds) { i -> if (i < 30) 16.0 else 8.0 })
        assertThat(r.status).isEqualTo(Status.PROFILE_CHANGED)
        // the same days with an unchanged profile would have been read as a fall
        assertThat(InsulinNeedWatch.evaluate(days(tdds)).status).isEqualTo(Status.BELOW)
        // a missing profile does not count as a change
        assertThat(InsulinNeedWatch.evaluate(days(tdds) { null }).status).isEqualTo(Status.BELOW)
    }

    @Test fun `a missing yesterday leaves the window short instead of moving it back`() {
        val d = days(october.take(37))
        val yesterday = d.last().startMs
        // drop 12 October: the window is still 8 to 12 October, now with four days, so no verdict
        assertThat(InsulinNeedWatch.evaluate(d.dropLast(1), yesterday).status).isEqualTo(Status.FEW_DAYS)
        assertThat(InsulinNeedWatch.evaluate(d, yesterday).status).isEqualTo(Status.ABOVE)
    }

    @Test fun `a temporary percentage switch is divided out, a permanent one is kept`() {
        // 16 U a day profile under a 70% exercise switch stores 11.2 U
        assertThat(InsulinNeedWatch.profileDailyBasal(11.2, 70, 2 * 60 * 60 * 1000L)).isWithin(1e-9).of(16.0)
        assertThat(InsulinNeedWatch.profileDailyBasal(19.2, 120, 0L)).isWithin(1e-9).of(19.2)
    }

    @Test fun `a direction is not repeated within 14 days, the other direction is`() {
        val above = InsulinNeedWatch.evaluate(days(october.take(37)))
        val now = t0 + 40 * dayMs
        assertThat(InsulinNeedWatch.shouldNotify(above, 0L, 0L, now)).isTrue()
        assertThat(InsulinNeedWatch.shouldNotify(above, now - 13 * dayMs, 0L, now)).isFalse()
        assertThat(InsulinNeedWatch.shouldNotify(above, now - 14 * dayMs, 0L, now)).isTrue()
        assertThat(InsulinNeedWatch.shouldNotify(above, 0L, now - dayMs, now)).isTrue()
        val within = InsulinNeedWatch.evaluate(days(october.take(36)))
        assertThat(InsulinNeedWatch.shouldNotify(within, 0L, 0L, now)).isFalse()
    }

    @Test fun `the breadcrumb token carries the verdict`() {
        assertThat(InsulinNeedWatch.evaluate(days(october.take(37))).token()).matches("ndw=ABOVE/z=\\d\\.\\d/p=\\+\\d+/n=30")
        assertThat(InsulinNeedWatch.evaluate(days(List(10) { 30.0 })).token()).startsWith("ndw=FEW_DAYS")
    }
}
