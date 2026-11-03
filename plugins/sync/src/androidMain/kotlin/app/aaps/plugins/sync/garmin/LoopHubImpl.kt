package app.aaps.plugins.sync.garmin

import androidx.annotation.VisibleForTesting
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.HR
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.SC
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.bolus.MasterActionExecutor
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.di.ApplicationScope
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.convertedToPercent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.reactivex.rxjava3.disposables.CompositeDisposable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant

/**
 * Interface to the functionality of the looping algorithm and storage systems.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class LoopHubImpl(
    private val aapsLogger: AAPSLogger,
    private val commandQueue: CommandQueue,
    private val constraintChecker: ConstraintsChecker,
    private val iobCobCalculator: IobCobCalculator,
    private val loop: Loop,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val persistenceLayer: PersistenceLayer,
    private val preferences: Preferences,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val masterActionExecutor: MasterActionExecutor,
    @ApplicationScope private val appScope: CoroutineScope
) : LoopHub {

    val disposable = CompositeDisposable()

    @VisibleForTesting
    var clock: Clock = Clock.systemUTC()

    /** Returns the active insulin profile. */
    override val currentProfile: Profile? get() = runBlocking { profileFunction.getProfile() }

    /** Returns the name of the active insulin profile. */
    override val currentProfileName: String
        get() = runBlocking { profileFunction.getProfileName() }

    /** Returns the glucose unit (mg/dl or mmol/l) as selected by the user. */
    override val glucoseUnit: GlucoseUnit
        get() = GlucoseUnit.fromText(preferences.get(StringKey.GeneralUnits))

    /** Returns the remaining bolus insulin on board. */
    override val insulinOnboard: Double
        get() = runBlocking { iobCobCalculator.calculateIobFromBolus() }.iob

    /** Returns the remaining bolus and basal insulin on board. */
    override val insulinBasalOnboard: Double
        get() = runBlocking { iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended() }.basaliob

    /** Returns the remaining carbs on board. */
    override val carbsOnboard: Double?
        get() = runBlocking { iobCobCalculator.getCobInfo("LoopHubImpl") }.displayCob

    /** Returns true if the pump is connected. */
    override val isConnected: Boolean get() = runBlocking { loop.runningMode() } != RM.Mode.DISCONNECTED_PUMP

    /** Short loop status for the watch face. */
    override val loopStatus: String
        get() = when (runBlocking { loop.runningMode() }) {
            RM.Mode.CLOSED_LOOP       -> "CLOSED"
            RM.Mode.CLOSED_LOOP_LGS   -> "LGS"
            RM.Mode.OPEN_LOOP         -> "OPEN"
            RM.Mode.DISABLED_LOOP     -> "DISABLED"
            RM.Mode.SUPER_BOLUS       -> "SUPERBOLUS"
            RM.Mode.DISCONNECTED_PUMP -> "DISCONN"
            RM.Mode.SUSPENDED_BY_PUMP,
            RM.Mode.SUSPENDED_BY_USER,
            RM.Mode.SUSPENDED_BY_DST  -> "SUSPEND"
            else                      -> "LOOP"   // RESUME / any transient or future mode
        }

    /** Epoch-ms of the last APS run (loop freshness), or null if it has never run. */
    override val lastLoopEpochMs: Long?
        get() = loop.lastRun?.lastAPSRun

    /** Current DynISF / variable sensitivity in the user's glucose units. Prefer the live APS
     *  variable_sens; fall back to the profile ISF; null if neither is available. */
    override val variableSensInUnits: Double?
        get() {
            val mgdl = loop.lastRun?.constraintsProcessed?.variableSens
                ?: currentProfile?.getIsfMgdl("GarminLoopHub")
                ?: return null
            return profileUtil.fromMgdlToUnits(mgdl, glucoseUnit)
        }

    /** Returns true if the current profile is set of a limited amount of time. */
    override val isTemporaryProfile: Boolean
        get() {
            val ps = runBlocking { persistenceLayer.getEffectiveProfileSwitchActiveAt(clock.millis()) }
            return ps != null && ps.originalDuration > 0
        }

    /** Returns the factor by which the basal rate is currently raised (> 1) or lowered (< 1). */
    override val temporaryBasal: Double
        get() {
            return currentProfile?.let {
                val tb = runBlocking { processedTbrEbData.getTempBasalIncludingConvertedExtended(clock.millis()) }
                tb?.convertedToPercent(clock.millis(), it)?.div(100.0)
            } ?: Double.NaN
        }

    override val lowGlucoseMark
        get() = profileUtil.convertToMgdl(
            preferences.get(UnitDoubleKey.OverviewLowMark), glucoseUnit
        )

    override val highGlucoseMark
        get() = profileUtil.convertToMgdl(
            preferences.get(UnitDoubleKey.OverviewHighMark), glucoseUnit
        )

    /** Tells the loop algorithm that the pump is physically connected. */
    override fun connectPump() {
        appScope.launch {
            persistenceLayer.cancelCurrentRunningMode(clock.millis(), Action.RECONNECT, Sources.Garmin)
            commandQueue.cancelTempBasal(enforceNew = true)
        }
    }

    /** Tells the loop algorithm that the pump will be physically disconnected
     *  for the given number of minutes. */
    override fun disconnectPump(minutes: Int) {
        currentProfile?.let { p ->
            appScope.launch {
                loop.handleRunningModeChange(
                    durationInMinutes = minutes,
                    profile = p,
                    newRM = RM.Mode.DISCONNECTED_PUMP,
                    action = Action.DISCONNECT,
                    source = Sources.Garmin,
                    listValues = listOf(ValueWithUnit.Minute(minutes))
                )
            }
        }
    }

    /** Retrieves the glucose values starting at from. */
    override fun getGlucoseValues(from: Instant, ascending: Boolean): List<GV> = runBlocking {
        persistenceLayer.getBgReadingsDataFromTime(from.toEpochMilli(), ascending)
    }

    /** Notifies the system that carbs were eaten and stores the value. */
    override fun postCarbs(carbohydrates: Int) {
        aapsLogger.info(LTag.GARMIN, "post $carbohydrates g carbohydrates")
        val carbsAfterConstraints =
            carbohydrates.coerceAtMost(constraintChecker.getMaxCarbsAllowed().value())
        // Instant carbs now ride the shared executor (one audited path); it logs the user entry + delivers.
        appScope.launch {
            masterActionExecutor.deliverCarbs(
                carbs = carbsAfterConstraints,
                note = null,
                source = Sources.Garmin,
                onError = { aapsLogger.error(LTag.GARMIN, "carbs delivery failed: ${it.comment}") }
            )
        }
    }

    /** Stores hear rate readings that a taken and averaged of the given interval. */
    override fun storeHeartRate(
        samplingStart: Instant, samplingEnd: Instant,
        avgHeartRate: Int,
        device: String?
    ) {
        val hr = HR(
            timestamp = samplingStart.toEpochMilli(),
            duration = samplingEnd.toEpochMilli() - samplingStart.toEpochMilli(),
            dateCreated = clock.millis(),
            beatsPerMinute = avgHeartRate.toDouble(),
            device = device ?: "Garmin",
        )
        appScope.launch {
            persistenceLayer.insertOrUpdateHeartRates(listOf(hr))
        }
    }

    override fun storeSteps(
        timestampMs: Long,
        steps5min: Int, steps10min: Int, steps15min: Int,
        steps30min: Int, steps60min: Int, steps180min: Int,
        device: String?
    ) {
        if (timestampMs <= 0L) return
        // A trailing-window count has a physical ceiling: nobody takes more than about 200 steps a
        // minute, and a sprint is under that. A watch reporting 1919 steps in five minutes is
        // reporting something else, and one was: a Venu 3 sent the cumulative daily
        // counter in all six windows at once, because the device-side reset handling returns the
        // whole counter when it sees a negative delta. Fifteen minutes at 1919 is six times the
        // brisk-walk threshold the activity classifier uses, so the loop would have read continuous
        // vigorous exercise and raised the target accordingly.
        //
        // Clamping rather than rejecting: a clamped value is still wrong but it is bounded and it
        // keeps a genuine burst, whereas dropping the record loses the cycle entirely. The wider
        // windows are also clamped from below by the narrower ones, since a three-hour count cannot
        // be less than the five-minute count inside it.
        val w = GarminStepWindows.sanitise(
            steps5min, steps10min, steps15min, steps30min, steps60min, steps180min
        )
        val s5 = w.s5; val s10 = w.s10; val s15 = w.s15
        val s30 = w.s30; val s60 = w.s60; val s180 = w.s180
        if (s5 != steps5min || s180 != steps180min) {
            aapsLogger.warn(
                LTag.GARMIN,
                "steps clamped from ${device ?: "Garmin"}: " +
                    "5/$steps5min→$s5 10/$steps10min→$s10 15/$steps15min→$s15 " +
                    "30/$steps30min→$s30 60/$steps60min→$s60 180/$steps180min→$s180"
            )
        }
        val sc = SC(
            duration = 300_000L,          // 5-min snapshot (mirrors the wear cadence)
            timestamp = timestampMs,
            steps5min = s5, steps10min = s10, steps15min = s15,
            steps30min = s30, steps60min = s60, steps180min = s180,
            device = device ?: "Garmin",
            dateCreated = clock.millis(),
        )
        appScope.launch {
            persistenceLayer.insertOrUpdateStepsCounts(listOf(sc))
        }
    }

    override fun storeHeartRates(samples: List<Pair<Long, Int>>, device: String?) {
        // Correct HR-model convention: timestamp = END of the minute, duration = 60 000 (matches
        // HealthConnectHrIngest so overlapping rows dedupe by minute bucket). insertOrUpdate matches on
        // id only and new rows have id 0, so a batch replayed on BT reconnect was stored twice and read as
        // fresh samples; minutes this device already has are skipped.
        val dev = device ?: "Garmin"
        val rows = samples.mapNotNull { (endMs, bpm) ->
            if (bpm <= 10 || endMs <= 0L) null
            else HR(
                timestamp = endMs,
                duration = 60_000L,
                dateCreated = clock.millis(),
                beatsPerMinute = bpm.toDouble(),
                device = dev,
            )
        }
        if (rows.isEmpty()) return
        // The 4.0 persistence layer takes a list, so the batch goes in one transaction where 3.4
        // inserted one row per call. The rows stored are the same.
        appScope.launch {
            val storedMinutes = runCatching {
                persistenceLayer.getHeartRatesFromTimeToTime(rows.minOf { it.timestamp }, rows.maxOf { it.timestamp })
            }.getOrDefault(emptyList()).filter { it.device == dev }.mapTo(HashSet()) { it.timestamp / 60_000L }
            val fresh = rows.filter { it.timestamp / 60_000L !in storedMinutes }.distinctBy { it.timestamp / 60_000L }
            if (fresh.isNotEmpty()) persistenceLayer.insertOrUpdateHeartRates(fresh)
        }
    }
}
