package app.aaps.workflow

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.scenes.SceneExpiryCheck
import app.aaps.core.keys.BooleanKey
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import kotlin.test.assertFailsWith

/** Moved from PostCalculationRunnerTest with the loop call itself. */
class CalculationLoopTriggerTest : TestBaseWithProfile() {

    @Mock lateinit var loop: Loop
    @Mock lateinit var sceneExpiryCheck: SceneExpiryCheck

    private fun trigger() = CalculationLoopTrigger(aapsLogger, config, iobCobCalculator, loop, preferences, sceneExpiryCheck)

    private fun newestBg(timestamp: Long) {
        val ads = mock<AutosensDataStore>()
        val bg = mock<InMemoryGlucoseValue>()
        whenever(bg.timestamp).thenReturn(timestamp)
        whenever(iobCobCalculator.ads).thenReturn(ads)
        whenever(ads.actualBg()).thenReturn(bg)
    }

    @Test
    fun `invokes loop on new bg`() = runTest {
        val ads = mock<AutosensDataStore>()
        val bg = mock<InMemoryGlucoseValue>()
        whenever(bg.timestamp).thenReturn(5000L)
        whenever(iobCobCalculator.ads).thenReturn(ads)
        whenever(ads.actualBg()).thenReturn(bg)
        whenever(loop.lastBgTriggeredRun).thenReturn(0L)

        trigger().invokeIfNewBg()

        verify(loop).invoke(any(), any(), any())
    }

    // The chain that reaches this runner does not have to be the chain the new BG started - a BG chain
    // can be cancelled and replaced by one started by another database change (issue #5066). The only
    // gate left is lastBgTriggeredRun, so the two cases below are what keeps the loop from running twice.
    @Test
    fun `invokes loop even when the chain was not started by a new bg`() = runTest {
        val ads = mock<AutosensDataStore>()
        val bg = mock<InMemoryGlucoseValue>()
        whenever(bg.timestamp).thenReturn(BASE + T.mins(5).msecs())
        whenever(iobCobCalculator.ads).thenReturn(ads)
        whenever(ads.actualBg()).thenReturn(bg)
        // A loop run happened for the previous BG five minutes earlier, the newest one was never used
        whenever(loop.lastBgTriggeredRun).thenReturn(BASE)

        trigger().invokeIfNewBg()

        verify(loop).invoke(any(), any(), any())
    }

    @Test
    fun `skips loop when newest bg was already used`() = runTest {
        val ads = mock<AutosensDataStore>()
        val bg = mock<InMemoryGlucoseValue>()
        whenever(bg.timestamp).thenReturn(5000L)
        whenever(iobCobCalculator.ads).thenReturn(ads)
        whenever(ads.actualBg()).thenReturn(bg)
        whenever(loop.lastBgTriggeredRun).thenReturn(5000L)

        trigger().invokeIfNewBg()

        verify(loop, never()).invoke(any(), any(), any())
    }

    @Test
    fun `skips loop when no actual bg is available`() = runTest {
        val ads = mock<AutosensDataStore>()
        whenever(iobCobCalculator.ads).thenReturn(ads)
        whenever(ads.actualBg()).thenReturn(null)

        trigger().invokeIfNewBg()

        verify(loop, never()).invoke(any(), any(), any())
    }

    // ---- loop decision rate (ported from Boost 6.1.2) -------------------------------------------

    @Test
    fun `a one-minute advance does not start a run while the loop holds five minutes`() = runTest {
        val ads = mock<AutosensDataStore>()
        val bg = mock<InMemoryGlucoseValue>()
        whenever(bg.timestamp).thenReturn(BASE + T.mins(1).msecs())
        whenever(iobCobCalculator.ads).thenReturn(ads)
        whenever(ads.actualBg()).thenReturn(bg)
        whenever(loop.lastBgTriggeredRun).thenReturn(BASE)

        trigger().invokeIfNewBg()

        verify(loop, never()).invoke(any(), any(), any())
    }

    @Test
    fun `at native cadence the loop reads the native series and runs on a one-minute advance`() = runTest {
        val ads = mock<AutosensDataStore>()
        val bg = mock<InMemoryGlucoseValue>()
        whenever(bg.timestamp).thenReturn(BASE + T.mins(1).msecs())
        whenever(iobCobCalculator.ads).thenReturn(ads)
        whenever(ads.actualBgNative()).thenReturn(bg)
        whenever(preferences.get(BooleanKey.ApsLoopAtNativeCadence)).thenReturn(true)
        whenever(loop.lastBgTriggeredRun).thenReturn(BASE)

        trigger().invokeIfNewBg()

        verify(ads, never()).actualBg()
        verify(loop).invoke(any(), any(), any())
    }

    // ---- from upstream 4.0.0-rc1, where these tests were on PostCalculationRunner -----------------

    /**
     * The chain was replaced while the loop ran, and the loop had not stored a decision yet. It did
     * nothing for this BG, so the BG must be given back, or the replacing chain skips it and this BG
     * gets no loop run at all.
     */
    @Test
    fun `a loop run cancelled before its decision gives the bg back`() = runTest {
        newestBg(BASE + T.mins(5).msecs())
        whenever(loop.lastBgTriggeredRun).thenReturn(BASE)
        whenever(loop.lastRun).thenReturn(Loop.LastRun().apply { lastAPSRun = 1000L })
        whenever(loop.invoke(any(), any(), any())).doSuspendableAnswer { throw CancellationException("replaced") }

        assertFailsWith<CancellationException> { trigger().invokeIfNewBg() }

        verify(loop).lastBgTriggeredRun = BASE + T.mins(5).msecs()
        verify(loop).lastBgTriggeredRun = BASE
    }

    /**
     * Once the loop stored a decision, the enactment after it cannot be cancelled and may have reached
     * the pump. The BG stays used, so no second automatic run follows for it.
     */
    @Test
    fun `a loop run cancelled after its decision keeps the bg used`() = runTest {
        newestBg(BASE + T.mins(5).msecs())
        whenever(loop.lastBgTriggeredRun).thenReturn(BASE)
        val lastRun = Loop.LastRun().apply { lastAPSRun = 1000L }
        whenever(loop.lastRun).thenReturn(lastRun)
        whenever(loop.invoke(any(), any(), any())).doSuspendableAnswer {
            lastRun.lastAPSRun = 6000L // the decision is stored
            throw CancellationException("replaced")
        }

        assertFailsWith<CancellationException> { trigger().invokeIfNewBg() }

        verify(loop).lastBgTriggeredRun = BASE + T.mins(5).msecs()
        verify(loop, never()).lastBgTriggeredRun = BASE
    }

    /** Where the loop runs, an overdue scene is ended first, so the loop does not dose with its settings. */
    @Test
    fun `on a master an overdue scene is ended before the loop runs`() = runTest {
        whenever(config.APS).thenReturn(true)
        newestBg(5000L)
        whenever(loop.lastBgTriggeredRun).thenReturn(0L)
        val calls = mutableListOf<String>()
        whenever(sceneExpiryCheck.endIfOverdue()).doSuspendableAnswer { calls += "scene" }
        whenever(loop.invoke(any(), any(), any())).doSuspendableAnswer { calls += "loop" }

        trigger().invokeIfNewBg()

        assertThat(calls).containsExactly("scene", "loop").inOrder()
    }

    /** A client receives the end of a scene from the master; it does not end scenes itself. */
    @Test
    fun `a client does not end scenes`() = runTest {
        whenever(config.APS).thenReturn(false)
        val ads = mock<AutosensDataStore>()
        whenever(iobCobCalculator.ads).thenReturn(ads)
        whenever(ads.actualBg()).thenReturn(null)

        trigger().invokeIfNewBg()

        verifyBlocking(sceneExpiryCheck, never()) { endIfOverdue() }
    }

    /** Missing a loop run is worse than ending a scene late. */
    @Test
    fun `a failing scene check does not stop the loop`() = runTest {
        whenever(config.APS).thenReturn(true)
        whenever(sceneExpiryCheck.endIfOverdue()).doSuspendableAnswer { throw IllegalStateException("broken scene") }
        newestBg(5000L)
        whenever(loop.lastBgTriggeredRun).thenReturn(0L)

        trigger().invokeIfNewBg()

        verify(loop).invoke(any(), any(), any())
    }

    private companion object {

        const val BASE = 1_700_000_000_000L
    }
}
