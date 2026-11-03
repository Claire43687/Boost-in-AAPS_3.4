package app.aaps.workflow

import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.scenes.SceneExpiryCheck
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException

/**
 * Starts a loop run from a MAIN calculation when the newest glucose value has not been used yet.
 *
 * [PrepareGraphDataRunner] calls it as soon as IOB/COB and autosens are published, before the
 * IOB/autosens graph data is built, so a reading reaches the loop without waiting for work that only
 * draws a picture. Boost 6.1.2 made the same change on 3.4, where the basal graph worker had run
 * ahead of the loop.
 * Upstream 4.0 invoked the loop from [PostCalculationRunner], after the whole prepare phase.
 *
 * The check is on purpose not tied to what started this chain: a chain started by a new BG can be
 * cancelled and replaced by a chain started by another database change before it gets here, and the
 * replacement must not lose the pending BG trigger (issue #5066).
 *
 * [Loop.lastBgTriggeredRun] is written before the call, so each BG timestamp can drive at most one
 * automatic loop run. One exception gives the BG back: a run that is cancelled before it stored a
 * decision, for example because a profile switch replaced this chain while the loop ran. Such a run
 * did nothing for this BG, so the chain that replaced this one runs the loop for it. Once a decision
 * is stored the BG stays used: the enactment after it cannot be cancelled and may have reached the
 * pump, and a second automatic run for the same BG must not follow. (Upstream 4.0.0-rc1, fdd0bfd893.)
 *
 * Before the loop decides, a scene whose time is over is ended (upstream a853e617c4), so the decision
 * does not use the scene's profile or target after its end.
 *
 * With [BooleanKey.ApsLoopAtNativeCadence] on, the value comes from the native-cadence series and the
 * loop follows the sensor. Off, the loop holds five minutes between runs whatever the sensor
 * delivers; see [shouldTrigger].
 */
@Inject
class CalculationLoopTrigger(
    private val aapsLogger: AAPSLogger,
    private val config: Config,
    private val iobCobCalculator: IobCobCalculator,
    private val loop: Loop,
    private val preferences: Preferences,
    private val sceneExpiryCheck: SceneExpiryCheck
) {

    suspend fun invokeIfNewBg() {
        endOverdueScene()
        val nativeCadence = preferences.get(BooleanKey.ApsLoopAtNativeCadence)
        val glucoseValue = (if (nativeCadence) iobCobCalculator.ads.actualBgNative() else iobCobCalculator.ads.actualBg()) ?: return
        if (!shouldTrigger(glucoseValue.timestamp, loop.lastBgTriggeredRun, nativeCadence)) return
        val previousTrigger = loop.lastBgTriggeredRun
        val decisionBefore = loop.lastRun?.lastAPSRun
        loop.lastBgTriggeredRun = glucoseValue.timestamp
        try {
            loop.invoke("Calculation for $glucoseValue", true)
        } catch (e: CancellationException) {
            if (loop.lastRun?.lastAPSRun == decisionBefore) loop.lastBgTriggeredRun = previousTrigger
            throw e
        }
    }

    /**
     * Ends a scene whose time is over, see [SceneExpiryCheck]. Only where the loop runs: the master
     * owns the scene, and a client receives its end. A failure is logged and the loop runs anyway,
     * since missing one loop run is worse than ending a scene late.
     */
    private suspend fun endOverdueScene() {
        if (!config.APS) return
        try {
            sceneExpiryCheck.endIfOverdue()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            aapsLogger.error(LTag.WORKER, "Ending an overdue scene failed", e)
        }
    }

    companion object {

        /**
         * Shortest advance in glucose time that starts another five-minute cycle. A sensor does not
         * deliver on exact multiples of five minutes, so requiring a full five would reject a
         * reading arriving a second early and push that cycle out to ten. Half a step of tolerance
         * is the same allowance the bucketing uses when it decides which grid point a reading
         * belongs to.
         */
        val MIN_FIVE_MINUTE_ADVANCE: Long = T.mins(4).plus(T.secs(30)).msecs()

        /**
         * Whether a new glucose value should start a loop run.
         *
         * Two conditions. The value has to be newer than the one already acted on, which is the
         * long-standing guard against running twice on one reading. Beyond that, a loop that is not
         * following the sensor has to hold the five-minute interval itself.
         *
         * It cannot leave that to the bucketing. Whether the newest bucketed timestamp advances
         * only every five minutes on a fast feed depends on how the grid anchor is chosen and
         * carried between rebuilds, which has changed more than once. Holding the interval here
         * makes the decision rate a property of this decision rather than a side effect of how
         * glucose happens to be stored.
         *
         * @param glucoseTimestamp   timestamp of the value being offered
         * @param lastTriggeredRun   timestamp of the value the loop last ran on, 0 if never
         * @param nativeCadence      true to run once per reading, false to hold five minutes
         */
        fun shouldTrigger(glucoseTimestamp: Long, lastTriggeredRun: Long, nativeCadence: Boolean): Boolean {
            if (glucoseTimestamp <= lastTriggeredRun) return false
            if (nativeCadence || lastTriggeredRun == 0L) return true
            return glucoseTimestamp - lastTriggeredRun >= MIN_FIVE_MINUTE_ADVANCE
        }
    }
}
