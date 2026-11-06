package app.aaps.plugins.aps.openAPSBoost

import app.aaps.core.data.model.HR
import app.aaps.plugins.aps.openAPSBoost.SleepStateDetector.SleepState
import app.aaps.plugins.aps.openAPSBoost.SleepStateDetector.State
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pre-window hold shadow. Sleep candidacy may start SLEEP_SCHEDULE_TOLERANCE_MIN (90 min)
 * before nightStart, but live SLEEPING holds only from nightStart, so a SLEEPING state entered in
 * that pre-window is ended on the next cycle by the boundary rule. The shadow holds from the
 * candidacy start and adds a steps wake and an HR-with-steps wake inside the pre-window. Night
 * 22:00 to 07:00, so the pre-window is 20:30 to 22:00 and PRE_SLEEP starts at 21:00.
 */
class SleepPreWindowShadowTest {

    private val T = SleepStateDetector
    private val MIN = 60_000L

    /** T0 is 20:50 local. */
    private val T0 = 1_000_000_000_000L
    private val START_MIN = 20 * 60 + 50

    private fun minuteAt(now: Long) = ((START_MIN + (now - T0) / MIN) % 1440).toInt()

    /** Live one-sample-a-minute feed at [bpm] for the last 30 min. */
    private fun liveHr(now: Long, bpm: Double) =
        (1..30).map { HR(duration = 60_000, timestamp = now - it * MIN, beatsPerMinute = bpm, device = "watch") }

    private fun inputs(now: Long, bpm: Double, stepsToday: Int, steps15: Int, sleepInSteps: Int = 250) =
        SleepStateDetector.Inputs(
            nowMs = now, minuteOfDay = minuteAt(now), hrReadings = liveHr(now, bpm),
            hrResting = 60, stepsLast15Min = steps15, mlMealLikely = null,
            nightStartMin = 1320, nightEndMin = 420, stepsToday = stepsToday,
            sleepInStepsThreshold = sleepInSteps, sleepInWindowMin = 120, cycleSpacingMinutes = 5.0
        )

    private data class Step(val now: Long, val live: SleepStateDetector.Result, val shadow: SleepStateDetector.Result)

    /** Runs live and shadow side by side over [cycles] five-minute cycles from 20:50. */
    private fun simulate(cycles: Int, input: (Long) -> SleepStateDetector.Inputs): List<Step> {
        var live = State()
        var shadow: State? = null
        val out = mutableListOf<Step>()
        for (c in 0 until cycles) {
            val now = T0 + c * 5 * MIN
            val inp = input(now)
            val l = T.evaluate(live, inp)
            val s = T.advancePreWindowShadow(shadow, live, inp)
            out += Step(now, l, s)
            live = l.newState
            shadow = s.newState
        }
        return out
    }

    @Test fun `SLEEPING entered an hour before nightStart holds in live and shadow`() {
        // asleep on the sofa from 20:50: HR 55 against a sleep cap of 69, no steps
        val steps = simulate(cycles = 19) { now -> inputs(now, bpm = 55.0, stepsToday = 6000, steps15 = 0) }  // 20:50 to 22:20
        val firstSleep = steps.indexOfFirst { it.live.newState.state == SleepState.SLEEPING }
        assertThat(minuteAt(steps[firstSleep].now)).isEqualTo(21 * 60)                     // 60 min before nightStart

        // live used to end this sleep as "boundary" at least twice before 22:00; it now holds (item 14)
        val liveBoundaryExits = steps.count { it.live.wakeReason == "boundary" && minuteAt(it.now) < 1320 }
        assertThat(liveBoundaryExits).isEqualTo(0)

        // the shadow enters on the same cycle and holds through nightStart without a transition
        assertThat(steps.indexOfFirst { it.shadow.newState.state == SleepState.SLEEPING }).isEqualTo(firstSleep)
        for (st in steps.drop(firstSleep)) {
            assertThat(st.shadow.newState.state).isEqualTo(SleepState.SLEEPING)
            assertThat(st.shadow.wakeReason).isNull()
        }
    }

    @Test fun `evening doze then getting up releases the shadow on HR and steps`() {
        // asleep 20:50 to 21:30, then up and about: HR 85 (wake floor 75) and 100 steps every 5 min
        val steps = simulate(cycles = 15) { now ->
            val up = minuteAt(now) >= 21 * 60 + 30
            val walked = if (up) (((now - T0) / MIN - 40) / 5 + 1).toInt() * 100 else 0
            inputs(now, bpm = if (up) 85.0 else 55.0, stepsToday = 6000 + walked, steps15 = if (up) 150 else 0)
        }
        val wake = steps.first { it.shadow.wakeReason != null }
        assertThat(wake.shadow.wakeReason).isEqualTo("pw_hr_steps")
        assertThat(wake.shadow.newState.state).isEqualTo(SleepState.AWAKE)
        // HR sustain (two cycles) and then the five-minute wake hysteresis: awake by 21:45
        assertThat(minuteAt(wake.now)).isAtMost(21 * 60 + 45)
    }

    @Test fun `evening doze then getting up releases the shadow on steps alone`() {
        // HR stays low (watch reads a resting rate), but 100 steps every 5 min from 21:30
        val steps = simulate(cycles = 15) { now ->
            val up = minuteAt(now) >= 21 * 60 + 30
            val walked = if (up) (((now - T0) / MIN - 40) / 5 + 1).toInt() * 100 else 0
            inputs(now, bpm = 55.0, stepsToday = 6000 + walked, steps15 = if (up) 150 else 0)
        }
        val wake = steps.first { it.shadow.wakeReason != null }
        assertThat(wake.shadow.wakeReason).isEqualTo("pw_steps")
        assertThat(minuteAt(wake.now)).isAtMost(21 * 60 + 50)
    }

    @Test fun `the walk to bed does not end the sleep it preceded`() {
        // 400 steps between 20:00 and 20:35 sit inside the 60-min lookback when SLEEPING starts at
        // 21:00; only steps since the entry count in the pre-window, so the shadow holds
        // the walk is already in the step samples when the evening starts
        var shadow = State(
            stepSamples = mutableListOf(
                SleepStateDetector.StepSample(T0 - 50 * MIN, 6000),
                SleepStateDetector.StepSample(T0 - 15 * MIN, 6400)
            )
        )
        var live = shadow.copy(stepSamples = shadow.stepSamples.toMutableList())
        var sleptAt: Int? = null
        var maxLookbackWhileAsleep = 0
        for (c in 0 until 13) {
            val now = T0 + c * 5 * MIN
            val inp = inputs(now, bpm = 55.0, stepsToday = 6400, steps15 = 0)
            val s = T.advancePreWindowShadow(shadow, live, inp)
            live = T.evaluate(live, inp).newState
            shadow = s.newState
            if (sleptAt == null && shadow.state == SleepState.SLEEPING) sleptAt = minuteAt(now)
            if (sleptAt != null) {
                assertThat(shadow.state).isEqualTo(SleepState.SLEEPING)
                maxLookbackWhileAsleep = maxOf(maxLookbackWhileAsleep, SleepStateDetector.stepGrowth(shadow.stepSamples, now, 60))
            }
        }
        assertThat(sleptAt).isEqualTo(21 * 60)
        // the walk was inside the 60-min lookback while the shadow slept, so counting over the full
        // lookback would have reached the 250-step threshold
        assertThat(maxLookbackWhileAsleep).isAtLeast(250)
    }

    @Test fun `live outputs are identical with and without the shadow over a scripted night`() {
        // 20:50 to 08:00: sofa doze, a short spell up at 21:30, back down, through the night with a
        // drought from 02:00 to 03:00 and a burst, up at 06:40 with HR and steps
        fun night(now: Long): SleepStateDetector.Inputs {
            val m = minuteAt(now)
            val upEvening = m in (21 * 60 + 30) until (21 * 60 + 45)
            val upMorning = m in (6 * 60 + 40) until (12 * 60)
            val k = ((now - T0) / MIN / 5).toInt()
            val stepsToday = when {
                m >= 20 * 60 -> 6000 + (if (m >= 21 * 60 + 30) minOf(m - (21 * 60 + 30), 15) * 20 else 0)
                upMorning -> (m - (6 * 60 + 40)) * 15
                else -> 0
            }
            val base = inputs(now, bpm = if (upEvening || upMorning) 88.0 else 54.0, stepsToday = stepsToday,
                              steps15 = if (upEvening || upMorning) 150 else 0)
            val drought = m in (2 * 60) until (3 * 60)
            return if (drought) base.copy(hrReadings = emptyList()) else base.copy(hrReadings = base.hrReadings.drop(k % 2))
        }
        val cycles = (11 * 60 + 10) / 5

        // live alone
        val alone = mutableListOf<String>()
        var a = State()
        for (c in 0 until cycles) {
            val r = T.evaluate(a, night(T0 + c * 5 * MIN))
            alone += "${r.newState.serialize()}|${r.transitioned}|${r.wakeReason}|${r.debug}"
            a = r.newState
        }

        // live with the shadow advanced on the same Inputs and the same live prev every cycle
        val withShadow = mutableListOf<String>()
        var b = State()
        var sh: State? = null
        var disagreements = 0
        for (c in 0 until cycles) {
            val inp = night(T0 + c * 5 * MIN)
            val before = b.serialize()
            val r = T.evaluate(b, inp)
            val s = T.advancePreWindowShadow(sh, b, inp)
            assertThat(b.serialize()).isEqualTo(before)                 // the live prev is not mutated
            withShadow += "${r.newState.serialize()}|${r.transitioned}|${r.wakeReason}|${r.debug}"
            if (s.newState.state != r.newState.state) disagreements++
            b = r.newState
            sh = s.newState
        }

        assertThat(withShadow).isEqualTo(alone)
        assertThat(disagreements).isEqualTo(0)                          // live now runs the shadow's rule (item 14)
    }

    @Test fun `outside the pre-window the shadow rule is the live rule`() {
        // a night that starts after nightStart: no pre-window SLEEPING, so the two never differ
        var live = State()
        var shadow: State? = null
        for (c in 0 until 60) {
            val now = T0 + 90 * MIN + c * 5 * MIN                    // 22:20 onwards
            val inp = inputs(now, bpm = 55.0, stepsToday = 6000, steps15 = 0)
            val l = T.evaluate(live, inp)
            val s = T.advancePreWindowShadow(shadow, live, inp)
            assertThat(s.newState.serialize()).isEqualTo(l.newState.serialize())
            live = l.newState
            shadow = s.newState
        }
    }

    @Test fun `shadow state round trip and old persisted JSON`() {
        val st = State(
            state = SleepState.SLEEPING, sleepCandidateSinceMs = null, wakeCandidateSinceMs = T0 - 3 * MIN,
            enteredAtMs = T0 - 40 * MIN, lastFreshHrSampleMs = T0 - MIN, sleepEntryReason = "hr",
            stepSamples = mutableListOf(SleepStateDetector.StepSample(T0 - 10 * MIN, 6000), SleepStateDetector.StepSample(T0, 6010)),
            hrHighStreak = 1
        )
        assertThat(State.deserializeOrNull(st.serialize())).isEqualTo(st)

        // nothing persisted under the shadow key (an install upgraded from a build without it)
        assertThat(State.deserializeOrNull("")).isNull()
        assertThat(State.deserializeOrNull("not json")).isNull()

        // the live blob as older builds wrote it, without stepSamples or hrHighStreak, still reads,
        // and a shadow seeded from it starts equal to it without sharing its step list
        val old = """{"state":"PRE_SLEEP","sleepCandidateSinceMs":null,"wakeCandidateSinceMs":null,"enteredAtMs":123,"lastFreshHrSampleMs":0}"""
        val live = State.deserialize(old)
        assertThat(live.state).isEqualTo(SleepState.PRE_SLEEP)
        assertThat(State.deserializeOrNull(old)).isEqualTo(live)
        val inp = inputs(T0, bpm = 55.0, stepsToday = 6000, steps15 = 0)
        val seeded = T.advancePreWindowShadow(null, live, inp)
        assertThat(live.stepSamples).isEmpty()                          // the live list was not touched
        assertThat(seeded.newState.stepSamples).hasSize(1)
        assertThat(seeded.newState.state).isEqualTo(T.evaluate(live, inp).newState.state)
    }

    @Test fun `tag format`() {
        val r = SleepStateDetector.Result(State(state = SleepState.SLEEPING), transitioned = false, debug = "")
        assertThat(T.preWindowShadowTag(r)).isEqualTo("sleepPw=SLEEPING; ")
        val w = SleepStateDetector.Result(State(state = SleepState.AWAKE), transitioned = true, debug = "", wakeReason = "pw_steps")
        assertThat(T.preWindowShadowTag(w)).isEqualTo("sleepPw=AWAKE,pw_steps; ")
    }
}
