package app.aaps.plugins.aps.openAPSBoost

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The Health Connect top-up raises the daily total in one jump. The sleep detector reads the phone's
 * own count, so the jump must not reach it.
 */
class StepServiceHcSeedTest {

    @BeforeEach @AfterEach fun reset() = StepService.resetForTest()

    @Test fun `top-up raises the daily total but not the own count`() {
        StepService.onStepCount(1000)
        StepService.getStepsToday(0L)
        StepService.onStepCount(1400)
        StepService.getStepsToday(0L)   // the loop reads the count before it applies the top-up
        StepService.seedTodayFromHc(4100, 0L)
        assertThat(StepService.getStepsToday(0L)).isEqualTo(4100)
        assertThat(StepService.getOwnStepsToday(0L)).isEqualTo(400)
        StepService.onStepCount(1450)
        assertThat(StepService.getStepsToday(0L)).isEqualTo(4150)
        assertThat(StepService.getOwnStepsToday(0L)).isEqualTo(450)
    }

    /** App restart while asleep: the whole day restored by the top-up used to read as an hour of walking. */
    @Test fun `restart and top-up give no wake-step growth`() {
        val t0 = 1_000_000_000L
        val samples = mutableListOf(SleepStateDetector.StepSample(t0, 9000))   // persisted before the restart
        StepService.onStepCount(52_000)                                       // new process, counter since boot
        samples += SleepStateDetector.StepSample(t0 + 5 * 60_000L, StepService.getOwnStepsToday(0L))
        StepService.seedTodayFromHc(9000, 0L)
        StepService.onStepCount(52_010)
        samples += SleepStateDetector.StepSample(t0 + 10 * 60_000L, StepService.getOwnStepsToday(0L))
        assertThat(SleepStateDetector.stepGrowth(samples, t0 + 10 * 60_000L, SleepStateDetector.WAKE_STEP_LOOKBACK_MIN))
            .isLessThan(SleepStateDetector.RESUME_WAKE_MIN_STEPS)
    }
}
