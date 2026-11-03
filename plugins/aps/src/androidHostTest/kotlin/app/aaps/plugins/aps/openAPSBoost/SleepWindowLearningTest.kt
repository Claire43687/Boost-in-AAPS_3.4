package app.aaps.plugins.aps.openAPSBoost

import app.aaps.core.data.model.HR
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Sleep-window learning fix: the learned WAKE time must be trained ONLY on genuine
 * wakes ("hr_steps"/"resume"), never on "boundary" hard-exit wakes — otherwise the night-window
 * end fed its own learned value and ratcheted earlier every night, collapsing to ~2h. Also
 * confirms the detector labels a hard morning exit as "boundary".
 */
class SleepWindowLearningTest {

    private val T = SleepHistoryTracker
    private val DAY = 86_400_000L
    private val WAKE_0600 = 6 * 3_600_000L   // 06:00 into a UTC day (offset 0)

    private fun sessions(n: Int, wakeReason: String?) = SleepHistoryTracker.History(
        (0 until n).map { i ->
            SleepHistoryTracker.Session(sleepStartMs = i * DAY, wakeMs = i * DAY + WAKE_0600, wakeReason = wakeReason)
        }.toMutableList()
    )

    @Test fun `boundary wakes never train the learned wake time`() {
        val h = sessions(10, "boundary")               // 10 sessions, all hard-exit
        val agg = T.aggregate(h, localOffsetMs = 0)
        assertThat(agg.sessionCount).isEqualTo(10)
        assertThat(agg.wakeMinAvg).isNull()             // excluded → no learned wake (falls back to configured)
    }

    @Test fun `null (legacy) wakes are excluded too`() {
        val agg = T.aggregate(sessions(10, null), localOffsetMs = 0)
        assertThat(agg.wakeMinAvg).isNull()             // pre-fix collapsed history is discarded, not re-learned
    }

    @Test fun `genuine wakes train the learned wake once enough exist`() {
        assertThat(T.aggregate(sessions(6, "hr_steps"), 0).wakeMinAvg).isNull()  // <7 genuine → none
        val agg = T.aggregate(sessions(7, "hr_steps"), 0)
        assertThat(agg.wakeMinAvg).isEqualTo(360)       // 7 genuine wakes at 06:00 → learned 06:00
    }

    @Test fun `mixed - only genuine sessions count toward the wake gate`() {
        val mixed = SleepHistoryTracker.History(
            ((0 until 4).map { SleepHistoryTracker.Session(it * DAY, it * DAY + WAKE_0600, wakeReason = "hr_steps") } +
                (4 until 10).map { SleepHistoryTracker.Session(it * DAY, it * DAY + WAKE_0600, wakeReason = "boundary") })
                .toMutableList()
        )
        val agg = T.aggregate(mixed, 0)
        assertThat(agg.wakeMinAvg).isNull()             // only 4 genuine < 7 → no learned wake
        assertThat(agg.sleepStartMinAvg).isNotNull()    // onset still learns from all 10 sessions
    }

    @Test fun `onWake stores the reason and it round-trips through serialize`() {
        var h = T.onSleepStart(SleepHistoryTracker.History(), 1_000L)
        h = T.onWake(h, 1_000L + WAKE_0600, wakeReason = "resume")
        assertThat(h.sessions.last().wakeReason).isEqualTo("resume")
        val restored = SleepHistoryTracker.History.deserialize(h.serialize())
        assertThat(restored.sessions.last().wakeReason).isEqualTo("resume")
    }

    @Test fun `detector labels a hard morning exit as boundary`() {
        // SLEEPING, clock at 12:00 (720) outside the 22:00-07:00 (1320-420) window → hard exit.
        val prev = SleepStateDetector.State(state = SleepStateDetector.SleepState.SLEEPING, enteredAtMs = 0L)
        val res = SleepStateDetector.evaluate(
            prev = prev,
            inputs = SleepStateDetector.Inputs(
                nowMs = 1_000_000L, minuteOfDay = 720, hrReadings = emptyList<HR>(),
                hrResting = 60, stepsLast15Min = 0, mlMealLikely = null,
                nightStartMin = 1320, nightEndMin = 420
            )
        )
        assertThat(res.newState.state).isEqualTo(SleepStateDetector.SleepState.AWAKE)
        assertThat(res.wakeReason).isEqualTo("boundary")
    }

    // 14 sessions start at 23:00 local: seven in summer time (22:00 UTC) and
    // seven after the clocks went back (23:00 UTC). With today's offset (0) for all, the summer ones read 22:00
    // and the learned onset was 22:30. Each converted with its own offset, the onset stays 23:00.
    @Test fun `a clock change does not move the learned onset`() {
        val change = 200 * DAY
        val h = SleepHistoryTracker.History((0 until 14).map { i ->
            val start = if (i < 7) change - (7 - i) * DAY - 3_600_000L + 23 * 3_600_000L else change + (i - 7) * DAY + 23 * 3_600_000L
            SleepHistoryTracker.Session(sleepStartMs = start, wakeMs = start + 8 * 3_600_000L, wakeReason = "hr_steps")
        }.toMutableList())
        val offsetAt = { t: Long -> if (t < change) 3_600_000L else 0L }
        assertThat(T.aggregate(h, offsetAt).sleepStartMinAvg).isIn(23 * 60 - 1..23 * 60 + 1)
        assertThat(T.aggregate(h, localOffsetMs = 0).sleepStartMinAvg).isIn(22 * 60 + 29..22 * 60 + 31)
    }
}
