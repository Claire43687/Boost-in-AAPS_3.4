package app.aaps.plugins.aps.openAPSBoostV5

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Fix 7 — multi-phase meal re-engagement.
 *
 * On a two-phase meal the mid-meal deceleration sends COMMITTED → RECOVERING; RECOVERING previously
 * had no path back to active dosing, so V5 dribbled at 0.4× through the whole second climb (BG ran
 * to 192). Fix 7 adds a RECOVERING → COMMITTED transition when the meal genuinely re-accelerates
 * while still well above target. [step] is a pure function, tested directly.
 */
class MealHypothesisFix7Test {

    private fun recovering(age: Int) =
        MealHypothesisState(MealHypothesis.RECOVERING, ageCycles = age, committedInSession = true)

    @Test fun `re-accelerating well above target re-engages COMMITTED`() {
        val next = step(
            current = recovering(age = 1),
            score = 0.5, eventualBg = 170.0, targetBg = 99.0,  // offset 71 > 20
            delta = 8.0, deltaAccl = 15.0, deltaDeclining = false,
        )
        assertThat(next.state).isEqualTo(MealHypothesis.COMMITTED)
        // Fix 6 preserved: no new CONFIRMED commit-shot, session lock stays set.
        assertThat(next.committedInSession).isTrue()
    }

    @Test fun `re-acceleration near target does NOT re-engage`() {
        val next = step(
            current = recovering(age = 2),
            score = 0.5, eventualBg = 110.0, targetBg = 99.0,  // offset 11 <= 20
            delta = 8.0, deltaAccl = 15.0, deltaDeclining = false,
        )
        assertThat(next.state).isEqualTo(MealHypothesis.RECOVERING)
    }

    @Test fun `same-cycle re-acceleration is blocked by min-age (no flicker)`() {
        val next = step(
            current = recovering(age = 0),
            score = 0.5, eventualBg = 170.0, targetBg = 99.0,
            delta = 8.0, deltaAccl = 15.0, deltaDeclining = false,
        )
        assertThat(next.state).isEqualTo(MealHypothesis.RECOVERING)
    }

    @Test fun `weak rise below accl threshold does NOT re-engage`() {
        val next = step(
            current = recovering(age = 2),
            score = 0.5, eventualBg = 170.0, targetBg = 99.0,
            delta = 4.0, deltaAccl = 6.0, deltaDeclining = false,  // accl 6 < 10
        )
        assertThat(next.state).isEqualTo(MealHypothesis.RECOVERING)
    }

    // ─── No-regression: existing RECOVERING exits unchanged ───

    // This test used to pin that the RECOVERING to IDLE exit also cleared the session lock. That was
    // the defect in dose-path audit item 7: the exit fires on one negative delta, the
    // trough between two phases of a meal, and clearing the lock there let the fast path CONFIRM a
    // second time. With a clock the lock now outlives the exit (see MealSessionLockTest); only a
    // caller with no clock keeps the old clearing.
    @Test fun `declining BG still exits RECOVERING to IDLE and keeps the session lock`() {
        val next = step(
            current = recovering(age = 2),
            score = 0.5, eventualBg = 170.0, targetBg = 99.0,
            delta = -2.0, deltaAccl = 15.0, deltaDeclining = false,  // delta<0 wins
            nowMs = 1_800_000_000_000L,
        )
        assertThat(next.state).isEqualTo(MealHypothesis.IDLE)
        assertThat(next.committedInSession).isTrue()
    }

    @Test fun `with no clock the exit clears the lock as before`() {
        val next = step(
            current = recovering(age = 2),
            score = 0.5, eventualBg = 170.0, targetBg = 99.0,
            delta = -2.0, deltaAccl = 15.0, deltaDeclining = false,
        )
        assertThat(next.state).isEqualTo(MealHypothesis.IDLE)
        assertThat(next.committedInSession).isFalse()
    }

    @Test fun `gentle positive drift stays in RECOVERING`() {
        val next = step(
            current = recovering(age = 2),
            score = 0.5, eventualBg = 170.0, targetBg = 99.0,
            delta = 1.0, deltaAccl = 2.0, deltaDeclining = false,  // no re-accel, delta>=0, score ok
        )
        assertThat(next.state).isEqualTo(MealHypothesis.RECOVERING)
    }

    @Test fun `a real slowdown still backs off to RECOVERING`() {
        val next = step(
            current = committed(),
            score = 0.5, eventualBg = 160.0, targetBg = 99.0,
            delta = 2.0, deltaAccl = -8.0, deltaDeclining = true,  // decel + declining + delta < 3
        )
        assertThat(next.state).isEqualTo(MealHypothesis.RECOVERING)
    }

    // ─── COMMITTED exit needs delta below RECOVERING_ENTRY_MAX_DELTA ───

    private fun committed() = MealHypothesisState(MealHypothesis.COMMITTED, ageCycles = 3, committedInSession = true)

    private fun backOff(delta: Double, deltaAccl: Double = -10.0, deltaDeclining: Boolean = true) = step(
        current = committed(), score = 0.5, eventualBg = 160.0, targetBg = 99.0,
        delta = delta, deltaAccl = deltaAccl, deltaDeclining = deltaDeclining,
    ).state

    @Test fun `deceleration while still rising fast keeps COMMITTED`() {
        // A rise slowing from 10 to 6 per 5 min used to go to RECOVERING on the deceleration alone.
        assertThat(backOff(delta = 6.0, deltaAccl = -12.0)).isEqualTo(MealHypothesis.COMMITTED)
        assertThat(backOff(delta = 4.0, deltaAccl = -12.0)).isEqualTo(MealHypothesis.COMMITTED)
    }

    @Test fun `COMMITTED exit boundary at RECOVERING_ENTRY_MAX_DELTA`() {
        assertThat(backOff(delta = RECOVERING_ENTRY_MAX_DELTA)).isEqualTo(MealHypothesis.COMMITTED)
        assertThat(backOff(delta = RECOVERING_ENTRY_MAX_DELTA - 0.01)).isEqualTo(MealHypothesis.RECOVERING)
        assertThat(backOff(delta = -1.0)).isEqualTo(MealHypothesis.RECOVERING)
    }

    @Test fun `a slow delta alone does not back off without deceleration and decline`() {
        assertThat(backOff(delta = 1.0, deltaAccl = RECOVERING_DECEL_THRESHOLD)).isEqualTo(MealHypothesis.COMMITTED)
        assertThat(backOff(delta = 1.0, deltaDeclining = false)).isEqualTo(MealHypothesis.COMMITTED)
    }

    // ─── RECOVERING re-engages on the rise itself (RECOVERING_REENGAGE_RISE_DELTA) ───

    private fun reEngage(delta: Double, deltaAccl: Double = 0.0, age: Int = 1, eventualBg: Double = 170.0) = step(
        current = recovering(age), score = 0.6, eventualBg = eventualBg, targetBg = 99.0,
        delta = delta, deltaAccl = deltaAccl, deltaDeclining = false,
    )

    @Test fun `a steady fast rise re-engages COMMITTED without acceleration`() {
        // A steady second rise keeps delta_accl under 10, so the acceleration route alone never re-engaged.
        assertThat(reEngage(delta = 5.0, deltaAccl = 4.0).state).isEqualTo(MealHypothesis.RECOVERING)
        val next = reEngage(delta = 10.0, deltaAccl = 8.0)
        assertThat(next.state).isEqualTo(MealHypothesis.COMMITTED)
        assertThat(next.committedInSession).isTrue()
        assertThat(reEngage(delta = 16.0, deltaAccl = 2.0).state).isEqualTo(MealHypothesis.COMMITTED)
    }

    @Test fun `rise re-engage boundary at RECOVERING_REENGAGE_RISE_DELTA`() {
        assertThat(reEngage(delta = RECOVERING_REENGAGE_RISE_DELTA).state).isEqualTo(MealHypothesis.RECOVERING)
        assertThat(reEngage(delta = RECOVERING_REENGAGE_RISE_DELTA + 0.01).state).isEqualTo(MealHypothesis.COMMITTED)
    }

    @Test fun `rise re-engage keeps the offset and min-age gates`() {
        // offset exactly RECOVERING_REENGAGE_OFFSET_MGDL is not above it
        assertThat(reEngage(delta = 12.0, eventualBg = 99.0 + RECOVERING_REENGAGE_OFFSET_MGDL).state)
            .isEqualTo(MealHypothesis.RECOVERING)
        assertThat(reEngage(delta = 12.0, eventualBg = 99.0 + RECOVERING_REENGAGE_OFFSET_MGDL + 0.5).state)
            .isEqualTo(MealHypothesis.COMMITTED)
        assertThat(reEngage(delta = 12.0, age = 0).state).isEqualTo(MealHypothesis.RECOVERING)
    }

    @Test fun `the acceleration route still re-engages below the rise threshold`() {
        assertThat(reEngage(delta = 4.0, deltaAccl = 15.0).state).isEqualTo(MealHypothesis.COMMITTED)
    }

    @Test fun `a two-phase meal is held in COMMITTED on both rises`() {
        // A synthetic two-phase meal on a 5-minute clock: (delta, delta_accl, declining, eventualBG offset).
        val t0 = 1_800_000_000_000L
        val trace = listOf(
            listOf(7.0, -12.0, 0.0, 20.0), listOf(6.0, -10.0, 1.0, 50.0), listOf(5.0, -7.0, 1.0, 90.0),
            listOf(4.0, -12.0, 1.0, 110.0), listOf(2.0, -15.0, 1.0, 70.0), listOf(5.0, 4.0, 0.0, 180.0),
            listOf(10.0, 8.0, 0.0, 260.0), listOf(14.0, 3.0, 0.0, 300.0), listOf(16.0, 2.0, 0.0, 300.0),
        )
        var s = MealHypothesisState(MealHypothesis.CONFIRMED, committedInSession = true, lastAgeMs = t0)
        val states = trace.mapIndexed { i, (d, a, decl, off) ->
            s = step(s, score = 0.5, eventualBg = 100.0 + off, targetBg = 100.0, delta = d, deltaAccl = a,
                deltaDeclining = decl > 0.0, nowMs = t0 + (i + 1) * 300_000L)
            s.state
        }
        val c = MealHypothesis.COMMITTED
        val r = MealHypothesis.RECOVERING
        assertThat(states).containsExactly(c, c, c, c, r, r, c, c, c).inOrder()
        // Never a second CONFIRMED, and the session lock is held throughout.
        assertThat(states).doesNotContain(MealHypothesis.CONFIRMED)
        assertThat(s.committedInSession).isTrue()
    }
}
