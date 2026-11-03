package app.aaps.ui.compose.overview.boost

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.overview.LastBgData
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventBucketedDataCreated
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewCalcProgress
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.round
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.pump.tickerFlow
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import java.util.Locale
import kotlin.math.abs

/**
 * ViewModel for the Boost V2 overview screen (Compose port of BoostOverviewV2Fragment).
 *
 * Produces a single [BoostUiState] from:
 *  - [BoostOverviewDataProvider.getBoostStatus] (algorithm tier / DynISF / TDD / V5 telemetry)
 *  - live pump / IOB / profile / target reads
 *
 * Colour decisions are deliberately kept OUT of the state where practical; the state exposes
 * domain enums ([BoostTier][app.aaps.core.interfaces.overview.BoostTier] /
 * [BoostV5State][app.aaps.core.interfaces.overview.BoostV5State] /
 * [ActivityMode][app.aaps.core.interfaces.overview.ActivityMode]) plus level flags
 * ([StatusLevel] for pump ages/levels, [AidStatus] for the loop dot) so the Compose layer maps
 * them to `AapsTheme` colours.
 *
 * Refresh model mirrors the fragment: a debounced merge of the RxBus events the fragment listened
 * to, plus the reactive [OverviewDataCache] StateFlows, plus a 60 s ticker, each recomputing
 * [buildUiState]. The pump comms status line is not part of this state: the route reads it from the
 * shared `PumpCommunicationStatus`, as the main overview does.
 */
@Stable
@AssistedInject
class BoostV2ViewModel(
    @Assisted private val cache: OverviewDataCache,
    private val boostDataProvider: BoostOverviewDataProvider,
    private val loop: Loop,
    private val iobCobCalculator: IobCobCalculator,
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val activePlugin: ActivePlugin,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val trendCalculator: TrendCalculator,
    private val lastBgData: LastBgData,
    private val decimalFormatter: DecimalFormatter,
    private val preferences: Preferences,
    private val dateUtil: DateUtil,
    private val rh: TextResolver,
    private val rxBus: RxBus,
    private val aapsLogger: AAPSLogger,
    private val config: Config,
    private val processedDeviceStatusData: ProcessedDeviceStatusData
) : ViewModel() {

    @AssistedFactory
    interface Factory {

        fun create(cache: OverviewDataCache): BoostV2ViewModel
    }

    /** Cosmetic range selection (hours). 4.0 has no RangeToDisplay preference — see [setRange]. */
    private val _rangeToDisplayHours = MutableStateFlow(DEFAULT_RANGE_HOURS)
    val rangeToDisplayHours: StateFlow<Int> = _rangeToDisplayHours.asStateFlow()

    /**
     * Chart-style toggle: `true` renders the faithful V2 dark chart, `false` the Standard 4.0 Vico
     * graphs. Backed by [BooleanKey.ApsBoostV2ChartFaithful] so the choice persists AND flips live —
     * `observe` re-emits whenever [setChartFaithful] (or Settings) writes the key.
     */
    val chartFaithful: StateFlow<Boolean> = preferences.observe(BooleanKey.ApsBoostV2ChartFaithful)

    /** Persist the chart-style choice; the [chartFaithful] flow re-emits so the UI flips live. */
    fun setChartFaithful(faithful: Boolean) {
        preferences.put(BooleanKey.ApsBoostV2ChartFaithful, faithful)
    }

    // RxBus events that recompute the state. NOTE: the fragment's granular EventUpdateOverviewIobCob
    // / EventUpdateOverviewGraph / EventTempTargetChange / EventRunningModeChange /
    // EventEffectiveProfileSwitchChanged / EventPreferenceChange were removed in AAPS 4.0; that
    // signalling is now carried by the reactive OverviewDataCache StateFlows (see cacheTriggers).
    // The fragment also refreshed on EventPumpStatusChanged for its status line; that line now
    // comes from PumpCommunicationStatus, so the event is not needed here.
    private val rxRefreshTriggers = rxBus.toFlow(EventUpdateOverviewCalcProgress::class).map { }

    // Signals that additionally invalidate the Boost data cache before recomputing. The two RxBus
    // events are the ones the 3.4 fragment invalidated on. Predictions are added because in 4.0 they
    // are published right after the loop has run, so a new Boost decision shows at once instead of
    // waiting out the 30 s cache.
    private val rxInvalidateTriggers = merge(
        rxBus.toFlow(EventBucketedDataCreated::class).map { },
        rxBus.toFlow(EventRefreshOverview::class).map { },
        cache.predictionsFlow.map { }
    ).onEach { boostDataProvider.invalidate() }

    // Reactive cache StateFlows — replace the removed granular RxBus events. Temp target / profile /
    // running mode / tbr / calc progress changes re-emit here.
    private val cacheTriggers = combine(
        cache.calcProgressFlow,
        cache.tempTargetFlow,
        cache.profileFlow,
        cache.runningModeFlow,
        cache.tbrFlow
    ) { _, _, _, _, _ -> }

    // BG / IOB data changes (replace EventUpdateOverviewIobCob / EventUpdateOverviewGraph) so the
    // hero BG and IOB pill refresh as new readings/calculations arrive.
    private val cacheDataTriggers = combine(
        cache.bgReadingsFlow,
        cache.bucketedDataFlow,
        cache.iobGraphFlow
    ) { _, _, _ -> }

    @OptIn(FlowPreview::class)
    val uiState: StateFlow<BoostUiState> =
        merge(
            rxRefreshTriggers,
            rxInvalidateTriggers,
            cacheTriggers,
            cacheDataTriggers,
            tickerFlow(60_000L)
        )
            .onStart { emit(Unit) }
            .debounce(500L)
            .map { buildUiState() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = BoostUiState()
            )

    // ── State production ──────────────────────────────────────────────────────

    private suspend fun buildUiState(): BoostUiState {
        val bs = boostDataProvider.getBoostStatus()
        val units = profileFunction.getUnits()
        val isMgdl = units == GlucoseUnit.MGDL

        // ── Hero: BG / delta / trend / time-ago ──
        val lastBg = lastBgData.lastBg()
        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        val trendArrow = trendCalculator.getTrendArrow(iobCobCalculator.ads) ?: TrendArrow.NONE
        val trendDesc = trendCalculator.getTrendDescription(iobCobCalculator.ads)
        val bgDisplay = profileUtil.fromMgdlToStringInUnits(lastBg?.recalculated)
        val deltaDisplay =
            if (glucoseStatus != null) "${profileUtil.fromMgdlToSignedStringInUnits(glucoseStatus.delta)} · $trendDesc"
            else ""
        val timeAgoDisplay = dateUtil.minOrSecAgo(rh, lastBg?.timestamp)

        // ── IOB pill ──
        val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
        val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
        val totalIob = bolusIob.iob + basalIob.basaliob

        // ── DynISF pill ──
        val dynDisplay = if (bs.dynIsfValue > 0) {
            val converted = profileUtil.fromMgdlToUnits(bs.dynIsfValue)
            if (isMgdl) String.format(Locale.getDefault(), "%.0f", converted)
            else String.format(Locale.getDefault(), "%.1f", converted)
        } else "---"

        // ── TDD pill (priority tddWeighted > tddFromDebug > tdd7d) ──
        val tddValue = when {
            bs.tddWeighted > 0  -> bs.tddWeighted
            bs.tddFromDebug > 0 -> bs.tddFromDebug
            else                -> bs.tdd7d
        }
        val tddDisplay = if (tddValue > 0) String.format(Locale.getDefault(), "%.1fu", tddValue) else "---"

        // ── Profile pill ──
        val profileData = cache.profileFlow.value
        val profileName = profileFunction.getOriginalProfileName()
        val profilePct = profileData?.percentage ?: 100
        val profileModified = profileData?.isModified ?: false

        // ── Target (temp target > algorithm target ≠ profile > profile > 100) ──
        val tempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())
        val hasTempTarget = tempTarget != null
        val profileTargetMgdl = profileFunction.getProfile()?.getTargetMgdl() ?: 0.0
        val targetMgdl: Double = when {
            tempTarget != null                                                                   -> tempTarget.lowTarget
            bs.targetBgMgdl > 0 && profileTargetMgdl > 0 && abs(profileTargetMgdl - bs.targetBgMgdl) > 0.01 -> bs.targetBgMgdl
            else                                                                                 -> profileTargetMgdl.takeIf { it > 0 } ?: 100.0
        }
        val targetDisplay = profileUtil.fromMgdlToStringInUnits(targetMgdl)

        // ── Pump column ──
        val pump = activePlugin.activePump
        val pumpDescription = pump.pumpDescription
        // The reservoir in the user's insulin units, read as the 4.0 status lights read it. On AAPSClient the
        // active pump is the virtual pump (reservoir and battery 50), so the followed pump's reservoir comes from
        // the Nightscout device status and the battery is not shown, as in StatusViewModel.
        val reservoir = if (config.AAPSCLIENT) processedDeviceStatusData.pumpData?.reservoir ?: 0.0
            else profileFunction.getProfile()?.let { pump.reservoirLevel.value.iU(it.insulinConcentration()) } ?: 0.0
        val maxReading = pumpDescription.maxReservoirReading.toDouble()
        val reservoirDisplay = when {
            !config.AAPSCLIENT && pumpDescription.isPatchPump && reservoir >= maxReading -> "${decimalFormatter.to0Decimal(maxReading)}+U"
            reservoir > 0                                          -> "${decimalFormatter.to0Decimal(reservoir)}U"
            else                                                   -> "---"
        }
        val reservoirLevel =
            if (reservoir > 0) levelStatus(reservoir, IntKey.OverviewResWarning, IntKey.OverviewResCritical)
            else StatusLevel.UNSPECIFIED

        val battery = if (config.AAPSCLIENT) null else pump.batteryLevel.value
        val batteryDisplay = if (battery != null) "$battery%" else "---"
        val batteryLevel =
            if (battery != null) levelStatus(battery.toDouble(), IntKey.OverviewBattWarning, IntKey.OverviewBattCritical)
            else StatusLevel.UNSPECIFIED

        val cannulaEvent = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE)
        val sensorEvent = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)
        val cannulaAgeLevel = cannulaEvent?.let {
            ageStatus(it.timestamp, IntKey.OverviewCageWarning, IntKey.OverviewCageCritical)
        } ?: StatusLevel.UNSPECIFIED
        val sensorAgeLevel = sensorEvent?.let {
            ageStatus(it.timestamp, IntKey.OverviewSageWarning, IntKey.OverviewSageCritical)
        } ?: StatusLevel.UNSPECIFIED

        // ── AID (loop) status ──
        val (aidText, aidStatus) =
            if (!pumpDescription.isTempBasalCapable) "NO AID" to AidStatus.ALERT
            else when (loop.runningMode()) {
                RM.Mode.CLOSED_LOOP, RM.Mode.CLOSED_LOOP_LGS                            -> "AID ON" to AidStatus.ACTIVE
                RM.Mode.OPEN_LOOP                                                       -> "OPEN" to AidStatus.ATTENTION
                RM.Mode.SUSPENDED_BY_USER, RM.Mode.SUSPENDED_BY_PUMP, RM.Mode.SUSPENDED_BY_DST -> "PAUSED" to AidStatus.ALERT
                RM.Mode.DISCONNECTED_PUMP                                              -> "DISC" to AidStatus.ALERT
                RM.Mode.DISABLED_LOOP                                                  -> "OFF" to AidStatus.DISABLED
                RM.Mode.SUPER_BOLUS                                                    -> "S.BOLUS" to AidStatus.ATTENTION
                else                                                                   -> "AID ON" to AidStatus.ACTIVE
            }

        // ── Dialog-detail helpers ──
        val lastBgDisplay = if (bs.lastBg > 0) profileUtil.fromMgdlToStringInUnits(bs.lastBg) else "---"
        val targetBgDisplay = if (bs.targetBgMgdl > 0) profileUtil.fromMgdlToStringInUnits(bs.targetBgMgdl) else "---"
        val deviationSensRatio = bs.deviationSensRatio?.let { String.format(Locale.getDefault(), "%.2f", it) } ?: "---"
        val sensNormalTarget = if (bs.sensNormalTarget > 0) profileUtil.fromMgdlToStringInUnits(bs.sensNormalTarget) else "---"

        return BoostUiState(
            // Tier
            tier = bs.tier,
            tierLabel = bs.tierLabel,
            tierReason = bs.tierReason,
            fastCarbProtection = bs.fastCarbProtection,
            // Pump column
            reservoirDisplay = reservoirDisplay,
            batteryDisplay = batteryDisplay,
            cannulaAgeDisplay = formatAgeDaysHours(bs.cannulaAgeDays),
            sensorAgeDisplay = formatAgeDaysHours(bs.sensorAgeDays),
            reservoirLevel = reservoirLevel,
            batteryLevel = batteryLevel,
            cannulaAgeLevel = cannulaAgeLevel,
            sensorAgeLevel = sensorAgeLevel,
            // Hero row
            bgDisplay = bgDisplay,
            bgValueMgdl = lastBg?.recalculated ?: 0.0,
            deltaDisplay = deltaDisplay,
            timeAgoDisplay = timeAgoDisplay,
            exerciseDisplay = bs.activityDetail,
            isActualBg = lastBgData.isActualBg(),
            trendArrow = trendArrow,
            // AID
            aidStatusText = aidText,
            aidStatus = aidStatus,
            // Calculation progress
            calcProgressPct = cache.calcProgressFlow.value,
            // IOB pill
            iobTotal = String.format(Locale.getDefault(), "%.2fu", totalIob),
            iobBolus = String.format(Locale.getDefault(), "%.2f", bolusIob.iob),
            iobBasal = String.format(Locale.getDefault(), "%.2f", basalIob.basaliob),
            // DynISF pill
            dynIsfDisplay = dynDisplay,
            variableSensDisplay = dynDisplay,
            // TDD pill
            tddDisplay = tddDisplay,
            tddWeighted = if (bs.tddWeighted > 0) String.format(Locale.getDefault(), "%.1f", bs.tddWeighted) else "---",
            tddFromDebug = if (bs.tddFromDebug > 0) String.format(Locale.getDefault(), "%.1f", bs.tddFromDebug) else "---",
            tdd7d = String.format(Locale.getDefault(), "%.1f", bs.tdd7d),
            tdd24h = String.format(Locale.getDefault(), "%.1f", bs.tdd24h),
            // Profile pill
            profileName = profileName,
            profilePct = profilePct,
            profileModified = profileModified,
            // Target
            targetDisplay = targetDisplay,
            targetIsTempTarget = hasTempTarget,
            // Activity / exercise
            activityMode = bs.activityMode,
            activityDetail = bs.activityDetail,
            // Boost V5 strip
            isV5Active = bs.isV5Active,
            v5State = bs.v5State,
            v5Score = bs.v5Score,
            v5ScoreDisplay = String.format(Locale.getDefault(), "%.2f", bs.v5Score),
            v5ActionMultDisplay = String.format(Locale.getDefault(), "%.1f", bs.v5ActionMult),
            v5AgeDisplay = "${bs.v5Age}c",
            v5DoseDisplay = String.format(Locale.getDefault(), "%.2f", bs.v5FinalDose),
            v5BudgetDisplay = String.format(Locale.getDefault(), "%.2f", bs.v5Budget),
            v5Brakes = bs.v5Brakes,
            v5GateRaw = bs.v5GateRaw,
            // Algorithm details for dialogs
            deltaAccl = String.format(Locale.getDefault(), "%.1f", bs.deltaAccl),
            insulinDivisor = if (bs.insulinDivisor > 0) bs.insulinDivisor.toString() else "---",
            lastBgDisplay = lastBgDisplay,
            autosensRatio = String.format(Locale.getDefault(), "%.2f", bs.autosensRatio),
            profilePercentage = bs.profilePercentage,
            targetBgDisplay = targetBgDisplay,
            scriptDebugText = bs.scriptDebugText,
            glucoseUnits = units.displayLabel,
            // Deviation-based sensitivity
            deviationSensRatio = deviationSensRatio,
            deviationSensSource = bs.deviationSensSource,
            deviationSensClean = bs.deviationSensClean,
            deviationSensTotal = bs.deviationSensTotal,
            sensNormalTarget = sensNormalTarget
        )
    }

    // ── Screen action hooks ───────────────────────────────────────────────────

    /**
     * Select the graph time range (hours). AAPS 4.0 has no `IntNonKey.RangeToDisplay` preference and
     * the Compose graph is driven by Vico zoom, so this only sets the zoom and the button highlight.
     *
     * It deliberately does not refresh the Boost status. Boost 6.1.2 (86e2093d7a) stopped refreshing
     * the overview on a range change because the range does not alter anything the status shows, and
     * the refresh repeated the 7-day TDD calculation on every tap.
     */
    fun setRange(hours: Int) {
        _rangeToDisplayHours.value = hours
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun levelStatus(level: Double, warnKey: IntPreferenceKey, criticalKey: IntPreferenceKey): StatusLevel {
        val warn = preferences.get(warnKey)
        val critical = preferences.get(criticalKey)
        return when {
            level <= critical -> StatusLevel.CRITICAL
            level <= warn     -> StatusLevel.WARNING
            else              -> StatusLevel.NORMAL
        }
    }

    private fun ageStatus(timestamp: Long, warnKey: IntPreferenceKey, criticalKey: IntPreferenceKey): StatusLevel {
        val warnHours = preferences.get(warnKey)
        val criticalHours = preferences.get(criticalKey)
        val ageHours = (dateUtil.now() - timestamp) / (1000 * 60 * 60)
        return when {
            ageHours >= criticalHours -> StatusLevel.CRITICAL
            ageHours >= warnHours      -> StatusLevel.WARNING
            else                       -> StatusLevel.NORMAL
        }
    }

    private fun formatAgeDaysHours(ageDays: Double): String {
        if (ageDays < 0) return "?"
        val totalHours = (ageDays * 24).toLong()
        val days = totalHours / 24
        val hours = totalHours % 24
        return "${days}d ${hours}h"
    }

    init {
        aapsLogger.debug(LTag.UI, "BoostV2ViewModel initialized")
    }

    companion object {

        private const val DEFAULT_RANGE_HOURS = 6
    }
}
