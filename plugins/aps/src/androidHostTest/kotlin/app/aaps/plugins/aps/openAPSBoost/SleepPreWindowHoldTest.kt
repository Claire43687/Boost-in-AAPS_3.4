package app.aaps.plugins.aps.openAPSBoost

import app.aaps.core.data.model.HR
import app.aaps.plugins.aps.openAPSBoost.SleepStateDetector.SleepState
import app.aaps.plugins.aps.openAPSBoost.SleepStateDetector.State
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pre-window hold.
 * Sleep candidacy may start SLEEP_SCHEDULE_TOLERANCE_MIN (90 min) before nightStart; SLEEPING now holds
 * from the candidacy start, with a steps wake and an HR-with-steps wake inside the pre-window. Night
 * 22:00 to 07:00, so the pre-window is 20:30 to 22:00 and PRE_SLEEP starts at 21:00.
 */
class SleepPreWindowHoldTest {

    private val T = SleepStateDetector
    private val MIN = 60_000L

    /** T0 is 20:50 local. */
    private val T0 = 1_000_000_000_000L
    private val START_MIN = 20 * 60 + 50

    private fun minuteAt(now: Long) = ((START_MIN + (now - T0) / MIN) % 1440).toInt()

    /** Live one-sample-a-minute feed at [bpm] for the last 30 min. */
    private fun liveHr(now: Long, bpm: Double) =
        (1..30).map { HR(duration = 60_000, timestamp = now - it * MIN, beatsPerMinute = bpm, device = "watch") }

    private fun inputs(now: Long, bpm: Double, stepsToday: Int, steps15: Int) =
        SleepStateDetector.Inputs(
            nowMs = now, minuteOfDay = minuteAt(now), hrReadings = liveHr(now, bpm),
            hrResting = 60, stepsLast15Min = steps15, mlMealLikely = null,
            nightStartMin = 1320, nightEndMin = 420, stepsToday = stepsToday,
            sleepInStepsThreshold = 250, sleepInWindowMin = 120, cycleSpacingMinutes = 5.0
        )

    private fun simulate(cycles: Int, input: (Long) -> SleepStateDetector.Inputs): List<Pair<Long, SleepStateDetector.Result>> {
        var st = State()
        return (0 until cycles).map { c ->
            val now = T0 + c * 5 * MIN
            val r = T.evaluate(st, input(now))
            st = r.newState
            now to r
        }
    }

    @Test fun `SLEEPING entered an hour before nightStart holds through nightStart`() {
        // asleep on the sofa from 20:50: HR 55 against a sleep cap of 69, no steps. The old rule ended this
        // sleep on the next cycle as "boundary" and re-entered it, at least twice before 22:00.
        val steps = simulate(cycles = 19) { now -> inputs(now, bpm = 55.0, stepsToday = 6000, steps15 = 0) }
        val first = steps.indexOfFirst { it.second.newState.state == SleepState.SLEEPING }
        assertThat(minuteAt(steps[first].first)).isEqualTo(21 * 60)
        for ((_, r) in steps.drop(first)) {
            assertThat(r.newState.state).isEqualTo(SleepState.SLEEPING)
            assertThat(r.wakeReason).isNull()
        }
    }

    @Test fun `an evening doze then getting up wakes on HR and steps`() {
        val steps = simulate(cycles = 15) { now ->
            val up = minuteAt(now) >= 21 * 60 + 30
            val walked = if (up) (((now - T0) / MIN - 40) / 5 + 1).toInt() * 100 else 0
            inputs(now, bpm = if (up) 85.0 else 55.0, stepsToday = 6000 + walked, steps15 = if (up) 150 else 0)
        }
        val wake = steps.first { it.second.wakeReason != null }
        assertThat(wake.second.wakeReason).isEqualTo("pw_hr_steps")
        assertThat(minuteAt(wake.first)).isAtMost(21 * 60 + 45)
    }

    @Test fun `an evening doze then getting up wakes on steps alone`() {
        val steps = simulate(cycles = 15) { now ->
            val up = minuteAt(now) >= 21 * 60 + 30
            val walked = if (up) (((now - T0) / MIN - 40) / 5 + 1).toInt() * 100 else 0
            inputs(now, bpm = 55.0, stepsToday = 6000 + walked, steps15 = if (up) 150 else 0)
        }
        val wake = steps.first { it.second.wakeReason != null }
        assertThat(wake.second.wakeReason).isEqualTo("pw_steps")
        assertThat(minuteAt(wake.first)).isAtMost(21 * 60 + 50)
    }

    @Test fun `the walk to bed does not end the sleep it preceded`() {
        // 400 steps between 20:00 and 20:35 are inside the 60-min lookback when SLEEPING starts at 21:00;
        // only steps since the entry count in the pre-window
        var st = State(stepSamples = mutableListOf(
            SleepStateDetector.StepSample(T0 - 50 * MIN, 6000),
            SleepStateDetector.StepSample(T0 - 15 * MIN, 6400)
        ))
        var sleptAt: Int? = null
        for (c in 0 until 13) {
            val now = T0 + c * 5 * MIN
            st = T.evaluate(st, inputs(now, bpm = 55.0, stepsToday = 6400, steps15 = 0)).newState
            if (sleptAt == null && st.state == SleepState.SLEEPING) sleptAt = minuteAt(now)
            if (sleptAt != null) assertThat(st.state).isEqualTo(SleepState.SLEEPING)
        }
        assertThat(sleptAt).isEqualTo(21 * 60)
    }

    @Test fun `pre-window wakes do not train the learned wake time`() {
        val h = SleepHistoryTracker.History((0 until 10).map { i ->
            SleepHistoryTracker.Session(sleepStartMs = i * 86_400_000L, wakeMs = i * 86_400_000L + 3_600_000L, wakeReason = "pw_hr_steps")
        }.toMutableList())
        assertThat(SleepHistoryTracker.aggregate(h, 0L).wakeMinAvg).isNull()
    }
}
