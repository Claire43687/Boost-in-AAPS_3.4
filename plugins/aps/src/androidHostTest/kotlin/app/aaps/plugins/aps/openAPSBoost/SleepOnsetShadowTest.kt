package app.aaps.plugins.aps.openAPSBoost

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Learned sleep ONSET shadow. The shipped mean counts every session start, while the
 * learned WAKE is filtered to genuine wakes. A spurious mid-night wake therefore splits one night
 * into two sessions and the second start is learned as a bedtime.
 *
 * These cover the two corrections computed alongside it. Nothing here doses; the corrections are
 * logged only.
 */
class SleepOnsetShadowTest {

    private val T = SleepHistoryTracker
    private val OFFSET = 0L                        // UTC, so clock minutes read directly
    private val DAY = 24L * 60 * 60 * 1000

    /** A session on night [n], from [startMin] to [endMin] minutes past that night's midnight. */
    private fun session(n: Long, startMin: Int, endMin: Int) =
        SleepHistoryTracker.Session(
            sleepStartMs = n * DAY + startMin * 60_000L,
            wakeMs = n * DAY + endMin * 60_000L,
            wakeReason = "hr_steps"
        )

    private fun history(vararg s: SleepHistoryTracker.Session) =
        SleepHistoryTracker.History(s.toMutableList())

    /** Seven nights, each split at 00:50 by a spurious wake and resumed at 01:15. */
    private fun splitNights(): SleepHistoryTracker.History {
        val out = mutableListOf<SleepHistoryTracker.Session>()
        for (n in 1L..7L) {
            out.add(session(n, 23 * 60 + 30, 24 * 60 + 50))      // 23:30 -> 00:50 next day
            out.add(session(n, 25 * 60 + 15, 32 * 60))           // 01:15 -> 08:00 next day
        }
        return SleepHistoryTracker.History(out)
    }

    @Test fun `a split night contributes both starts to the shipped mean`() {
        val mins = splitNights().sessions.map { T.msToMinOfDay(it.sleepStartMs, OFFSET) }
        assertThat(mins).hasSize(14)
        assertThat(mins.toSet()).containsExactly(23 * 60 + 30, 60 + 15)   // 23:30 and 01:15
    }

    @Test fun `first-of-night keeps only the real bedtime`() {
        val got = T.firstOfNightOnsets(splitNights(), OFFSET)
        assertThat(got).hasSize(7)
        assertThat(got.toSet()).containsExactly(23 * 60 + 30)
    }

    @Test fun `gap-merging joins sessions separated by less than the threshold`() {
        val got = T.gapMergedOnsets(splitNights(), OFFSET)
        assertThat(got).hasSize(7)
        assertThat(got.toSet()).containsExactly(23 * 60 + 30)
    }

    @Test fun `a genuine long gap is not merged away`() {
        // asleep 22:00-23:00, properly up for three hours, back to sleep 02:00
        val h = history(session(1, 22 * 60, 23 * 60), session(1, 26 * 60, 32 * 60))
        val merged = T.gapMergedOnsets(h, OFFSET)
        assertThat(merged).hasSize(2)
        assertThat(merged.toSet()).containsExactly(22 * 60, 2 * 60)
    }

    @Test fun `first-of-night treats after-midnight onsets as the same night`() {
        // one night only: an onset at 23:30 and another at 01:15 belong together
        val h = history(session(3, 23 * 60 + 30, 24 * 60 + 50), session(3, 25 * 60 + 15, 32 * 60))
        assertThat(T.firstOfNightOnsets(h, OFFSET)).containsExactly(23 * 60 + 30)
    }

    @Test fun `the aggregate exposes both corrections and leaves the shipped value alone`() {
        val agg = T.aggregate(splitNights(), OFFSET)
        assertThat(agg.sleepStartMinFirstOfNight).isEqualTo(23 * 60 + 30)
        assertThat(agg.sleepStartMinGapMerged).isEqualTo(23 * 60 + 30)
        // the shipped mean sits between the two clusters, which is the defect being measured
        val shipped = agg.sleepStartMinAvg!!
        assertThat(shipped).isNotEqualTo(agg.sleepStartMinFirstOfNight)
    }

    @Test fun `corrections stay null below the session-count gate`() {
        val h = history(session(1, 23 * 60, 30 * 60))
        val agg = T.aggregate(h, OFFSET)
        assertThat(agg.sleepStartMinFirstOfNight).isNull()
        assertThat(agg.sleepStartMinGapMerged).isNull()
    }
}
