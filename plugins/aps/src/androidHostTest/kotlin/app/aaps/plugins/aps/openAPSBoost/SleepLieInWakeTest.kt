package app.aaps.plugins.aps.openAPSBoost

import app.aaps.core.data.model.HR
import app.aaps.plugins.aps.openAPSBoost.SleepStateDetector.SleepState
import app.aaps.plugins.aps.openAPSBoost.SleepStateDetector.State
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Waking in the morning lie-in. A person could be up, with watch steps and HR around the wake
 * floor, while the detector held SLEEPING: it measured growth of max(watch, phone) day totals, the
 * phone's Health-Connect-topped total was the larger and did not move until the phone was picked up,
 * and the HR + steps rule only ran up to the learned scheduled wake, never inside the lie-in.
 *
 * Now growth is measured on each series and the larger taken, and the HR + steps rule runs through
 * the lie-in. Guards kept: HR without steps never wakes, and in the core night with live HR, steps
 * alone below the strong threshold do not wake.
 */
class SleepLieInWakeTest {

    private val T = SleepStateDetector
    private val MIN = 60_000L
    private val T0 = 1_000_000_000_000L
    private val NIGHT_END = 492           // the learned wake
    private val LIE_IN = 120              // two hours

    /** Fifteen one-minute readings ending now, so the feed is live and the resume path stays quiet. */
    private fun hrFeed(now: Long, bpm: Double) =
        (1..15).map { k -> HR(duration = 60_000, timestamp = now - k * MIN, beatsPerMinute = bpm, device = "test") }

    private fun inputs(
        now: Long, minuteOfDay: Int, bpm: Double, watch: Int, phone: Int, steps15: Int = 0, sleepInSteps: Int = 75
    ) = SleepStateDetector.Inputs(
        nowMs = now, minuteOfDay = minuteOfDay, hrReadings = hrFeed(now, bpm), hrResting = 63,
        stepsLast15Min = steps15, mlMealLikely = null, nightStartMin = 1410, nightEndMin = NIGHT_END,
        stepsToday = watch, sleepInStepsThreshold = sleepInSteps, sleepInWindowMin = LIE_IN,
        stepsTodayPhone = phone
    )

    private fun asleep(now: Long) = State(state = SleepState.SLEEPING, enteredAtMs = now - 6 * 60 * MIN, lastFreshHrSampleMs = now - MIN)

    /** Run five-minute cycles from [startMin]; returns (minute of day it woke or null, wake reason). */
    private fun run(
        startMin: Int, cycles: List<Triple<Double, Int, Int>>, sleepInSteps: Int = 75, steps15: List<Int>? = null
    ): Pair<Int?, String?> {
        var s = asleep(T0)
        var t = T0
        var m = startMin
        for ((i, c) in cycles.withIndex()) {
            val (bpm, watch, phone) = c
            val r = T.evaluate(s, inputs(t, m, bpm, watch, phone, steps15?.get(i) ?: 0, sleepInSteps))
            s = r.newState
            if (s.state == SleepState.AWAKE) return m to r.wakeReason
            t += 5 * MIN; m += 5
        }
        return null to null
    }

    @Test fun `watch steps wake in the lie-in while the phone total is higher and still`() {
        // Watch steps climb while the phone stays parked at a higher total.
        val day = listOf(
            Triple(64.0, 120, 900), Triple(64.0, 120, 900), Triple(64.0, 120, 900), Triple(67.0, 131, 900),
            Triple(78.0, 197, 900), Triple(82.0, 197, 900), Triple(83.0, 197, 900), Triple(78.0, 265, 961),
            Triple(81.0, 315, 1039)
        )
        val (woke, reason) = run(503, day)
        assertThat(woke).isNotNull()
        assertThat(woke!!).isAtMost(533)                    // by 08:53, not 09:03
        assertThat(reason).isAnyOf("steps", "hr_steps")
    }

    @Test fun `the same morning measured on the larger day total does not wake until the phone moves`() {
        // The pre-fix input: one series, max(watch, phone). Kept as a record of what failed.
        var s = asleep(T0)
        var t = T0
        var m = 503
        val combined = listOf(900, 900, 900, 900, 900, 900, 900)
        for (st in combined) {
            val r = T.evaluate(s, inputs(t, m, 64.0, st, -1))
            s = r.newState
            t += 5 * MIN; m += 5
        }
        assertThat(s.state).isEqualTo(SleepState.SLEEPING)
    }

    @Test fun `HR above the floor with step evidence wakes inside the lie-in below the lie-in threshold`() {
        // Lie-in threshold at the default 250; 130 watch steps in the hour clears the HR rule's 100.
        val day = listOf(
            Triple(64.0, 0, 0), Triple(84.0, 60, 0), Triple(85.0, 130, 0), Triple(86.0, 130, 0),
            Triple(85.0, 135, 0), Triple(85.0, 140, 0), Triple(84.0, 140, 0)
        )
        val (woke, reason) = run(520, day, sleepInSteps = 250)   // 08:40, inside the lie-in
        assertThat(woke).isNotNull()
        assertThat(reason).isEqualTo("hr_steps")
    }

    @Test fun `HR alone inside the lie-in does not wake`() {
        val day = List(8) { Triple(88.0, 10, 0) }
        val (woke, _) = run(520, day, sleepInSteps = 250)
        assertThat(woke).isNull()
    }

    @Test fun `phone steps alone still count`() {
        val day = listOf(
            Triple(64.0, 50, 100), Triple(64.0, 50, 140), Triple(64.0, 50, 190), Triple(64.0, 50, 200),
            Triple(64.0, 50, 205), Triple(64.0, 50, 205)
        )
        val (woke, reason) = run(520, day)
        assertThat(woke).isNotNull()
        assertThat(reason).isEqualTo("steps")
    }

    @Test fun `core night with live HR - steps below the strong threshold and raised HR do not wake`() {
        // 03:00, far from the scheduled wake: the HR rule is not trusted, and 150 steps is under 250.
        val night = List(8) { k -> Triple(86.0, 20 * k, 0) }
        val (woke, _) = run(180, night, sleepInSteps = 250)
        assertThat(woke).isNull()
    }

    @Test fun `steps alone wake before the night end with live HR below the floor - the 2026-11-04 case`() {
        // 07:40 to 08:10, before the 08:12 night end. Up and walking, but HR 70 never clears the 78.75 floor.
        val day = listOf(
            Triple(66.0, 13, 13), Triple(70.0, 13, 13), Triple(70.0, 221, 180), Triple(70.0, 312, 250),
            Triple(70.0, 328, 300), Triple(70.0, 371, 330), Triple(70.0, 426, 380)
        )
        val (woke, reason) = run(460, day, sleepInSteps = 250, steps15 = listOf(0, 0, 208, 300, 303, 129, 106))
        assertThat(woke).isNotNull()
        assertThat(woke!!).isLessThan(NIGHT_END)
        assertThat(reason).isEqualTo("steps")
    }

    @Test fun `core night with live HR - steps above the strong threshold still do not wake`() {
        // 03:00 is more than 90 min before the night end, so steps alone wait for HR.
        // 300 in the last 15 min as well, so only the clock keeps the new route off.
        val night = List(8) { k -> Triple(70.0, 100 * k, 0) }
        val (woke, _) = run(180, night, sleepInSteps = 250, steps15 = List(8) { 300 })
        assertThat(woke).isNull()
    }

    @Test fun `phone step series survives serialisation and old state without it still loads`() {
        val s = asleep(T0)
        s.phoneStepSamples.add(SleepStateDetector.StepSample(T0, 42))
        val back = State.deserialize(s.serialize())
        assertThat(back.phoneStepSamples).containsExactly(SleepStateDetector.StepSample(T0, 42))
        val legacy = """{"state":"SLEEPING","enteredAtMs":1,"stepSamples":[[1,5]],"hrHighStreak":0}"""
        val old = State.deserialize(legacy)
        assertThat(old.state).isEqualTo(SleepState.SLEEPING)
        assertThat(old.stepSamples).hasSize(1)
        assertThat(old.phoneStepSamples).isEmpty()
    }
}
