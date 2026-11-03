package app.aaps.plugins.aps.openAPSBoost

import android.Manifest
import android.content.Context
import android.content.Intent
import app.aaps.core.data.aps.SMBDefaults
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.getPassedDurationToTimeInMinutes
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.HR
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TT
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.OapsProfileBoost
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PermissionGroup
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.rx.events.EventCalibrationDetected
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.withClick
import app.aaps.plugins.aps.getBoostDosing
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.extensions.plannedRemainingMinutes
import app.aaps.core.objects.extensions.target
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.icons.IcPluginOpenAPS
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.core.utils.MidnightUtils
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.BoostBuildInfo
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.compose.OpenAPSComposeContent
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import app.aaps.plugins.aps.keys.ApsIntentKey
import app.aaps.plugins.aps.openAPSBoostV5.OpenAPSBoostV5Plugin
import app.aaps.plugins.aps.openAPSSMB.GlucoseStatusCalculatorSMB
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import app.aaps.plugins.aps.openAPSBoostV5.AutoConfigLedger
import app.aaps.plugins.aps.openAPSBoostV5.MealHypothesis

/**
 * implausible-TDD guard for dynamic ISF. Minimum blended TDD, as a fraction of the TDD the
 * user's own profile ISF implies via the 1800 rule (profileISF ~= 1800/TDD). Below this the insulin
 * history is treated as incomplete and dynamic ISF is NOT derived — the profile ISF is used instead.
 *
 * 0.35 is deliberately liberal. The fallback direction is safe (profileSens is the user's configured
 * value), so a false positive costs one cycle of dynamic responsiveness and nothing else, whereas a
 * false NEGATIVE paralyses dosing: a TDD far below implied gives a dynamic ISF many times the profile
 * ISF, and the loop delivers nothing. Every activation is logged (LTag.APS) and lands
 * in consoleError, so the true false-positive rate is auditable across the cohort before tightening.
 */
private const val DYNISF_MIN_TDD_FRACTION = 0.35

/**
 * True when [tdd] is too low to be believed given what [profileSens] implies, so dynamic ISF must NOT
 * be derived from it. Extracted as a pure function so the guard is unit-testable independently of the
 * plugin's DI graph. Returns false when [profileSens] is non-positive (no reference to judge against —
 * fail OPEN there, because the existing `tdd > 0` check still applies downstream).
 */
internal fun tddImplausibleForProfile(tdd: Double, profileSens: Double): Boolean {
    if (profileSens <= 0.0) return false
    val impliedTdd = 1800.0 / profileSens
    return tdd < impliedTdd * DYNISF_MIN_TDD_FRACTION
}

// @SingleIn(AppScope::class) MUST stay adjacent to this declaration. 2026-07-30..08-04: a KDoc, a
// constant and a helper function were inserted between the two, which silently moved the annotation
// onto the constant and left the engine UNSCOPED. OpenAPSBoostV5Plugin injects it and reads
// lastAPSResult back through the same reference, so every cycle built its result on one instance and
// the loop read null from another — "NO APS SELECTED OR PROVIDED RESULT", nothing enacted, for five
// days. (Under Dagger the annotation was @Singleton; Metro's equivalent is @SingleIn(AppScope::class).)
// Not in the plugin list : "Boost" (OpenAPSBoostV5Plugin) is the one selectable Boost
// plugin and runs this engine, which keeps key 232 unused. A database that had this plugin selected
// has no enabled APS plugin after the update, so PluginStore enables the default, which is Boost.
@SingleIn(AppScope::class)
open class OpenAPSBoostPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    private val rxBus: RxBus,
    private val constraintsChecker: ConstraintsChecker,
    override val rh: ResourceHelper,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val config: Config,
    private val activePlugin: ActivePlugin,
    private val iobCobCalculator: IobCobCalculator,
    private val hardLimits: HardLimits,
    preferences: Preferences,
    protected val dateUtil: DateUtil,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val persistenceLayer: PersistenceLayer,
    private val glucoseStatusCalculatorSMB: GlucoseStatusCalculatorSMB,
    private val bgQualityCheck: BgQualityCheck,
    notificationManager: NotificationManager,
    private val tddCalculator: TddCalculator,
    private val determineBasalBoost: DetermineBasalBoost,
    // Layer A ML retrofit — both models are @Singleton, lazy-loaded from APK
    // assets on first inference. Safe to inject unconditionally; absence of the
    // asset is handled inside the model classes (returns null score).
    private val boostRiskModel: BoostRiskModel,
    private val boostMealModel: BoostMealModel,
    private val profiler: Profiler,
    private val apsResultProvider: () -> APSResult,
    // V5 silent shadow — V1 hands the cycle's RT + inputs to V5 after determine_basal
    // returns so V5 can compute a parallel decision against the same data. V5 itself
    // is hidden from the plugin list and not user-selectable; this callback is the
    // only way V5 sees a cycle's data. A provider (() -> T under Metro) avoids a hard DI cycle.
    private val boostV5Plugin: () -> OpenAPSBoostV5Plugin,
    // Health Connect HR ingest — bridges Garmin Connect / Wear OS HR streams via Android
    // Health Connect into AAPS's local HR table. Pulled each Boost cycle (throttled internally).
    private val healthConnectHrIngest: HealthConnectHrIngest,
    // Activity-load SHADOW — HC steps → single-source daily totals for the step baseline.
    private val healthConnectStepsIngest: HealthConnectStepsIngest,
    // AAPS 4.0: pump rates are reported in pump units and converted with the insulin concentration.
    private val ch: ConcentrationHelper,
    // Application context: Health Connect grant button and the phone step-counter registration.
    private val context: Context
) : PluginBaseWithPreferences(
    PluginDescription()
        .mainType(PluginType.APS)
        .composeContent { plugin ->
            OpenAPSComposeContent(
                apsPlugin = plugin as APS,
                rxBus = rxBus,
                rh = rh,
                dateUtil = dateUtil
            )
        }
        .icon(IcPluginOpenAPS)
        .pluginName(ApsStrings.openaps_boost)
        .preferencesVisibleInSimpleMode(false)
        .showInList { config.APS }
        .description(ApsStrings.description_boost),
    ownPreferences = emptyList(),
    aapsLogger, rh, preferences, notificationManager
), APS, PluginConstraints {


    // Site and reservoir age telemetry: siteAge= and resAge= on every cycle, read-only.
    private val deviceAgeTag = DeviceAgeTag { site ->
        persistenceLayer.getLastTherapyRecordUpToNow(if (site) TE.Type.CANNULA_CHANGE else TE.Type.INSULIN_CHANGE)?.timestamp
    }

    /** The volume-weighted dose shadow, held on preferences as the other shadows are. It logs
     *  an alternative blend and reaches nothing on the dose path. */
    private val vwaTddShadow by lazy {
        BoostVwaTddShadow(
            loadState = { preferences.getBoostDosing(StringKey.ApsBoostVwaTddShadowState) },
            saveState = { preferences.put(StringKey.ApsBoostVwaTddShadowState, it) },
            logInfo = { aapsLogger.debug(LTag.APS, it) },
        )
    }

    /** One cycle of the volume-weighted shadow; null when it abstains. */
    private suspend fun vwaShadowCycle(tdd7DKnown: Double?): BoostVwaTddShadow.Result? {
        val tdd7D = tdd7DKnown ?: tddCalculator.averageTDD(tddCalculator.calculate(7, allowMissingDays = true))?.data?.totalAmount
        val now = System.currentTimeMillis()
        val sinceAnchorH = ((now - vwaTddShadow.dayAnchorMs(now)) / 3_600_000L).coerceIn(0L, 24L)
        val deliveredToday = if (sinceAnchorH > 0L) tddCalculator.calculateDaily(-sinceAnchorH, 0L)?.totalAmount else 0.0
        // Read one day of stored history per cycle until the curve stands on the participant rather
        // than on the population. Costs 48 window totals on a cycle and stops after seven days.
        // The shadow takes a plain callback; TddCalculator is suspend in AAPS 4.0.
        vwaTddShadow.warmFromHistory(now) { startH, endH ->
            runBlocking { tddCalculator.calculateDaily(startH, endH) }?.totalAmount
        }
        return vwaTddShadow.compute(nowMs = now, deliveredSinceDayStart = deliveredToday, tdd7D = tdd7D)
    }

    companion object {
        /**
         * Picks the sensitivity ratio that scales basal / targets / CR in determine_basal.
         * TDD-DynISF and traditional oref autosens are alternative adaptation mechanisms — never both:
         *  - useTdd ON  → the TDD model (isfResultRatio = 24H/7D) owns sensitivity.
         *  - useTdd OFF + autosensWhenNoTdd → traditional oref autosens drives it (the fix).
         *  - useTdd OFF + !autosensWhenNoTdd → isfResultRatio, which is 1.0 (or a temp-target ratio) now
         *    that the curve ratio is no longer produced without TDD (see reconcileSensitivitySettings).
         * variable_sens (the curve ISF) is a separate lever and is unaffected by this choice.
         */
        internal fun selectSensitivityRatio(
            useTdd: Boolean,
            autosensWhenNoTdd: Boolean,
            isfResultRatio: Double,
            orefAutosensRatio: Double
        ): Double = when {
            useTdd            -> isfResultRatio
            autosensWhenNoTdd -> orefAutosensRatio
            else              -> isfResultRatio
        }

        /** Sensitivity settings after [reconcileSensitivitySettings], with what was changed. */
        internal data class SensitivitySettings(
            val adjustSens: Boolean,
            val velocityPct: Double,
            val clearedAdjustSens: Boolean,
            val zeroedVelocity: Boolean,
            val restoredVelocity: Boolean
        ) {
            val changed: Boolean get() = clearedAdjustSens || zeroedVelocity || restoredVelocity
        }

        /**
         * Settings that must not be on together.
         *
         * Without TDD-based ISF there is no 24h/7D ratio, so "TDD sensitivity adjustment" fell back to
         * sensNormalTarget / variableSens, which is the DynISF curve at the current BG rather than a
         * sensitivity signal. determine_basal then treated it as autosens: at BG 130 it read 1.19 and
         * lowered the target from 104 to 97, on top of the curve already strengthening ISF, so the BG
         * level was counted twice in the dose. With TDD off, the adjustment is therefore forced off and
         * BG impact on ISF is set to 0, so profile ISF is used as is, which is what the TDD switch
         * describes. The adjustment is also cleared once when the engine first runs under V6 after V1.
         * When TDD-based ISF is switched on, BG impact is set back to [velocityOnPct] once, on that
         * transition only, so a value the user then chooses with TDD on is kept.
         */
        internal fun reconcileSensitivitySettings(
            useTdd: Boolean,
            adjustSens: Boolean,
            velocityPct: Double,
            switchedToV6: Boolean,
            tddJustEnabled: Boolean = false,
            velocityOnPct: Double = 100.0
        ): SensitivitySettings {
            val clearAdjust = adjustSens && (!useTdd || switchedToV6)
            val zeroVelocity = !useTdd && velocityPct != 0.0
            val restoreVelocity = useTdd && tddJustEnabled && velocityPct != velocityOnPct
            return SensitivitySettings(
                adjustSens = adjustSens && !clearAdjust,
                velocityPct = when {
                    zeroVelocity    -> 0.0
                    restoreVelocity -> velocityOnPct
                    else            -> velocityPct
                },
                clearedAdjustSens = clearAdjust,
                zeroedVelocity = zeroVelocity,
                restoredVelocity = restoreVelocity
            )
        }

        /** True when TDD-based ISF is on now and was recorded off. No record ("") is not a transition. */
        internal fun isTddJustEnabled(lastUseTdd: String, useTdd: Boolean): Boolean =
            useTdd && lastUseTdd == false.toString()

        /**
         * ISF at target after autosens, for a static profile ISF. Stock oref divides the
         * profile ISF by the autosens ratio and uses the result for predictions and for the dose. Boost
         * builds every sensitivity from the ISF at target, so the ratio is applied there; applied later,
         * in determine_basal, it reached the current BGI and missed the dosing sensitivity. Not applied
         * with TDD-based ISF (TDD owns sensitivity), with the no-TDD autosens switch off, when a temp
         * target has set its own ratio (stock lets that replace autosens rather than stack), or for a
         * ratio that is not positive.
         */
        internal fun autosensAdjustedIsf(
            sensNormalTarget: Double,
            useTdd: Boolean,
            autosensWhenNoTdd: Boolean,
            tempTargetRatio: Double,
            orefAutosensRatio: Double
        ): Double =
            if (useTdd || !autosensWhenNoTdd || tempTargetRatio != 1.0 || orefAutosensRatio <= 0.0) sensNormalTarget
            else sensNormalTarget / orefAutosensRatio

        /** True on the first run under V6 when the engine last ran as V1, or has no record ("" counts as V1). */
        internal fun isSwitchToV6(lastEngineMode: String, v5Active: Boolean): Boolean =
            v5Active && lastEngineMode != ENGINE_MODE_V6

        /**
         * Whether the active TempTarget is the post-exercise recovery target Boost inserted, the only
         * one the hypo-rebound auto-cancel may remove. The check used to be the ACTIVITY
         * reason alone, which is also the reason AAPS gives a target set from its activity button, so
         * a high target set to sit out a rescue rebound was cancelled within one cycle and Boost
         * re-engaged: in the field it then gave a 2.175 U V6 shot at 192 mg/dL forty minutes after a
         * low of 44. Matching the start time Boost recorded at insert means a user's target is never
         * touched.
         */
        internal fun isBoostRecoveryTt(activeTtStartMs: Long?, activeTtReason: TT.Reason?, boostRecoveryTtStartMs: Long?): Boolean =
            activeTtStartMs != null && boostRecoveryTtStartMs != null &&
                activeTtReason == TT.Reason.ACTIVITY && activeTtStartMs == boostRecoveryTtStartMs

        internal const val ENGINE_MODE_V1 = "v1"
        internal const val ENGINE_MODE_V6 = "v6"

        /** Outcome of the V6-override dose caps: the dose to deliver plus the reason-line breadcrumb ("" when uncapped). */
        internal data class V6OverrideCaps(val dose: Double, val capNote: String)

        /**
         * V6-override dose caps (pure — unit-tested directly):
         *  - non-meal-state cap: in IDLE/OBSERVING/RECOVERING V6 never out-doses V1;
         *  - post-rescue meal-state cap: inside the post-rescue window
         *    (recentLowBG45Min < [DetermineBasalBoost.POST_RESCUE_LOW_THRESHOLD_MGDL]) the meal-state
         *    exemption is suppressed, so CONFIRMED/COMMITTED are ALSO capped at V1's would-dose.
         *
         * Pattern: severe hypo, unannounced rescue carbs, rebound. V6 CONFIRMED could deliver more than
         * V1's 45-min post-rescue tier guard allowed the base engine, because the meal-state exemption
         * discarded that restraint.
         *
         * DB backtest: 20.4% of meal-state cycles are post-rescue; 27% of the insulin this
         * cap removes sits directly ahead of a second low < 70 (vs 14-19% for every other lever
         * evaluated). Cost side: 10% genuine post-hypo meals, median 0.15U under-delivery, zero
         * double-dips. Verdict SHIP.
         *
         * WHY inherit V1 (alignment is load-bearing): the 75 mg/dL / 45-min window is deliberately the
         * SAME constant + source value as V1's post-rescue tier guard (DetermineBasalBoost Fix A v2),
         * so whenever this cap binds, v1WouldDose is by construction the hypo-restrained dose — the cap
         * inherits V1's restraint instead of inventing a second, divergent notion of "post-rescue".
         */
        internal fun applyV6OverrideCaps(
            inMealState: Boolean,
            inPostRescueWindow: Boolean,
            v5FinalDose: Double,
            v1WouldDose: Double,
            recentLowBG45Min: Double
        ): V6OverrideCaps {
            val dose = if (inMealState && !inPostRescueWindow) v5FinalDose else minOf(v5FinalDose, v1WouldDose)
            val capNote = when {
                dose >= v5FinalDose -> ""
                inMealState         -> ", post-rescue capped from ${Round.roundTo(v5FinalDose, 0.001)}U to V1's ${Round.roundTo(v1WouldDose, 0.001)}U (45-min low ${Round.roundTo(recentLowBG45Min, 1.0)})"
                else                -> ", non-meal-capped from ${Round.roundTo(v5FinalDose, 0.001)}U"
            }
            return V6OverrideCaps(dose, capNote)
        }

        /**
         * Whether Boost's tiers may run this cycle, before the temp-target and lie-in checks.
         *  - nightSleepPeriod: the night-mode period (toggle-dependent, as before).
         *  - inNightWindow: the configured night window as a clock fact, whatever the night-mode toggle
         *    says. With the toggle off the tiers previously ran all night with the
         *    detector reading SLEEPING.
         *  - v6Active && detectorSleeping: V6 stands down while SLEEPING, and the dose that replaced it
         *    was V1's tiered SMB. Closing the gate makes it base oref instead. Covers sleep outside the
         *    window (early nights, lie-ins).
         * The detector can only close the gate. It cannot reopen it inside the window, because a
         * batched heart-rate upload reads as a wake there (core-night AWAKE up to 48% of cycles by user).
         */
        internal fun boostGateOpen(nightSleepPeriod: Boolean, inNightWindow: Boolean, v6Active: Boolean, detectorSleeping: Boolean): Boolean =
            !nightSleepPeriod && !inNightWindow && !(v6Active && detectorSleeping)

        /** The sleep signals [calculateBoostActivity] uses, after the boundary-exit hold. */
        internal data class SleepSignals(
            val detectorSleeping: Boolean,
            val detectorAsleep: Boolean,
            val nightSleepPeriod: Boolean,
            val boundaryHold: Boolean
        )

        /** Minutes the sleep exclusions are held after a boundary exit: the detector's sleep hysteresis
         *  plus one five-minute cycle, the shortest time in which it can re-enter SLEEPING. */
        internal fun boundaryExitHoldMin(sleepHysteresisMin: Int): Int = sleepHysteresisMin.coerceAtLeast(0) + 5

        /**
         * Sleep signals with the boundary-exit hold (audit #18). The detector can enter
         * SLEEPING up to 90 min before the night window opens but holds it only from the window start,
         * so a pre-window sleep is ended by the boundary rule on the next cycle and re-entered after the
         * hysteresis. Each such exit left one cycle reading AWAKE, which opened the INACTIVE raise and,
         * for V1 users with sleep-driven night mode, the Boost gate. The same rule ends the lie-in in
         * the morning; holding there delays the gate by one hysteresis period, which is the
         * conservative direction. For [holdMin] minutes after a boundary exit the user is treated as
         * SLEEPING for the gate and the INACTIVE exclusion, and the night/sleep period is held where it
         * was sleep-driven (night mode and auto-by-sleep both on). Genuine wakes (steps, HR, resume)
         * are not held. The detector itself is unchanged.
         */
        internal fun sleepSignals(
            state: SleepStateDetector.SleepState,
            nightSleepPeriodRaw: Boolean,
            nightModeEnabled: Boolean,
            autoBySleep: Boolean,
            nowMs: Long,
            lastBoundaryExitMs: Long?,
            holdMin: Int
        ): SleepSignals {
            val sleeping = state == SleepStateDetector.SleepState.SLEEPING
            val hold = !sleeping && lastBoundaryExitMs != null &&
                nowMs >= lastBoundaryExitMs && nowMs - lastBoundaryExitMs < holdMin * 60_000L
            return SleepSignals(
                detectorSleeping = sleeping || hold,
                detectorAsleep = sleeping || state == SleepStateDetector.SleepState.PRE_SLEEP || hold,
                nightSleepPeriod = nightSleepPeriodRaw || (hold && nightModeEnabled && autoBySleep),
                boundaryHold = hold
            )
        }

        /**
         * Why the learned pre-meal target must not apply this cycle, or null when it may (
         * audit #8). The target lowers to 72 mg/dL in the hour before a learned meal time; it was
         * applying while asleep and under a user's own temp target. It applies only outside the
         * configured night window, with the detector neither SLEEPING nor PRE_SLEEP, with no temp
         * target active and outside the post-rescue window.
         */
        internal fun preMealTargetBlock(
            inNightWindow: Boolean,
            sleepState: SleepStateDetector.SleepState,
            tempTargetActive: Boolean,
            postRescueWindow: Boolean
        ): String? = when {
            inNightWindow -> "night window"
            sleepState == SleepStateDetector.SleepState.SLEEPING ||
                sleepState == SleepStateDetector.SleepState.PRE_SLEEP -> "asleep"
            tempTargetActive -> "temp target"
            postRescueWindow -> "post-rescue"
            else -> null
        }

        /** The meal-time learner records a session only when it starts outside the configured night
         *  window with the detector AWAKE. Overnight sessions in the field were rises
         *  without logged carbs, mostly while the detector read SLEEPING or PRE_SLEEP. */
        internal fun mealSessionRecordable(inNightWindow: Boolean, sleepState: SleepStateDetector.SleepState): Boolean =
            !inNightWindow && sleepState == SleepStateDetector.SleepState.AWAKE

        /** What the V6 primer's temp-basal route does with the base engine's temp. */
        internal enum class PrimerTbrAction { SKIP_PROTECTIVE, SUBSUMED, APPLY }

        /**
         * The temp the base engine leaves running this cycle: its own [baseRate] when it set one, else
         * the temp already running when it said "no temp required" (rate null), else null for
         * scheduled basal. (audit #10: reading only the returned rate missed a protective
         * zero temp V1 was keeping, and the primer replaced it.)
         */
        internal fun effectiveBaseTempRate(baseRate: Double?, currentTempRate: Double, currentTempDurationMin: Int): Double? =
            baseRate ?: if (currentTempDurationMin > 0) currentTempRate else null

        /** A protective low or zero temp always wins; a base temp at or above the primer subsumes it. */
        internal fun primerTbrAction(effectiveBaseRate: Double?, curBasal: Double, primerRate: Double): PrimerTbrAction = when {
            effectiveBaseRate != null && effectiveBaseRate < curBasal    -> PrimerTbrAction.SKIP_PROTECTIVE
            effectiveBaseRate != null && effectiveBaseRate >= primerRate -> PrimerTbrAction.SUBSUMED
            else                                                         -> PrimerTbrAction.APPLY
        }

        /**
         * The SMB Tier 8 (regular oref1) would size from the same inputs, rebuilt at the seam because
         * determine_basal returns only the tier that fired and has no register room for another field.
         * Mirrors DetermineBasalBoost: maxBolus from basal x the UAM minutes when IOB > -0.2 (else the
         * SMB minutes), rounded to 0.1 U; the dose is insulinReq / (100 / Boost_InsulinReq), floored to
         * the bolus increment. It omits Tier 8's own gates (G3 hold, post-rescue scaling, cumulative
         * cap), so callers take the lower of this and V1's would-dose, which carries them.
         */
        internal fun baseOrefSmb(
            insulinReq: Double,
            iob: Double,
            currentBasal: Double,
            maxUamSmbBasalMinutes: Int,
            maxSmbBasalMinutes: Int,
            boostInsulinReqPct: Double,
            bolusIncrement: Double
        ): Double {
            if (insulinReq <= 0.0 || boostInsulinReqPct <= 0.0 || bolusIncrement <= 0.0) return 0.0
            val minutes = if (iob > -0.2) maxUamSmbBasalMinutes else maxSmbBasalMinutes
            val maxBolus = Math.round(currentBasal * minutes / 60.0 * 10.0) / 10.0
            val roundSmbTo = 1.0 / bolusIncrement
            return Math.floor(minOf(insulinReq / (100.0 / boostInsulinReqPct), maxBolus) * roundSmbTo) / roundSmbTo
        }

        /** The SMB to deliver when V6 is active but returned no decision (audit #9): V1's
         *  dose, capped at base oref, so a V6 failure cannot hand the pump a Boost tier dose. */
        internal fun v6UnavailableSmb(v1Units: Double?, orefSmb: Double): Double? =
            if (v1Units != null && v1Units > orefSmb) orefSmb else v1Units

        /**
         * The most a confirm-tranche release may add on this cycle (audit #1): what is left
         * of the maxIOB headroom and of the CONFIRMED per-cycle cap after the cycle's own dose. Both
         * bounds are what the confirm shot itself was held to, so the held remainder can never take a
         * cycle above a dose the engine would have been allowed to give in one go. Never negative.
         */
        internal fun trancheReleaseCeiling(cycleDoseU: Double, maxIobU: Double, iobU: Double, confirmedCapU: Double): Double =
            maxOf(0.0, minOf(maxIobU - iobU - cycleDoseU, confirmedCapU - cycleDoseU))

        // Target hard limits as Boost 6.1.2 applies them [mg/dL]. The 3.4 fork lowered the profile
        // limits to 72 mg/dL (upstream 80); AAPS 4.0 moved to ranges with an 80 mg/dL floor and a
        // 180.2 / 180.16 top. The engine keeps the 6.1.2 values so a profile or temp target is clamped
        // exactly as before.
        internal val BOOST_LIMIT_MIN_BG = 72.0..180.0
        internal val BOOST_LIMIT_MAX_BG = 72.0..200.0
        internal val BOOST_LIMIT_TARGET_BG = 72.0..200.0
        internal val BOOST_LIMIT_TEMP_MIN_BG = 72.0..180.0
        internal val BOOST_LIMIT_TEMP_MAX_BG = 72.0..270.0
        internal val BOOST_LIMIT_TEMP_TARGET_BG = 72.0..200.0
    }

    // last values
    override var lastAPSRun: Long = 0
    override val algorithm = APSResult.Algorithm.BOOST
    override var lastAPSResult: APSResult? = null

    // ---- Calibration SMB block ----
    // AAPS 4.0's bus is a Flow; the subscription is a job on the plugin scope, cancelled on stop.
    @Volatile private var calibrationJob: Job? = null
    @Volatile private var calibrationBlockedUntil: Long = 0L

    override suspend fun onStart() {
        super.onStart()
        aapsLogger.info(LTag.APS, "Boost engine starting, version ${BoostBuildInfo.BOOST_VERSION}")
        BoostStepSensor.register(context, aapsLogger)
        calibrationJob?.cancel()
        calibrationJob = pluginScope.launch {
            try {
                rxBus.toFlow(EventCalibrationDetected::class).collect {
                    calibrationBlockedUntil = dateUtil.now() + 15 * 60_000L
                    aapsLogger.debug(LTag.APS, "Boost SMB block set: calibration detected, blocked until ${dateUtil.dateAndTimeString(calibrationBlockedUntil)}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "EventCalibrationDetected error", e)
            }
        }
        // In 3.4, CompatDBHelper fired EventCalibrationDetected when the first therapy event of a
        // database change was a finger-stick BG (Dexcom, Glunovo and Intelligo write one per
        // calibration). AAPS 4.0 has no such helper; its database change flow is watched here
        // instead, with the same first-event rule.
        calibrationSourceJob?.cancel()
        calibrationSourceJob = pluginScope.launch {
            try {
                persistenceLayer.observeChanges(TE::class).collect { changed ->
                    if (changed.firstOrNull()?.type == TE.Type.FINGER_STICK_BG_VALUE) {
                        aapsLogger.debug(LTag.APS, "Firing EventCalibrationDetected")
                        rxBus.send(EventCalibrationDetected())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "Finger-stick watch error", e)
            }
        }
    }

    @Volatile private var calibrationSourceJob: Job? = null

    override suspend fun onStop() {
        calibrationJob?.cancel()
        calibrationJob = null
        calibrationSourceJob?.cancel()
        calibrationSourceJob = null
        super.onStop()
    }

    // ---- Boost-specific preference getters ----

    /**
     * Writes the result of [reconcileSensitivitySettings] back to preferences so the settings screen
     * shows what the engine uses, and records which plugin ran the engine. Runs before any ISF read.
     */
    private fun applySensitivitySettingsReconcile(v5Active: Boolean) {
        val lastMode = preferences.getBoostDosing(StringKey.ApsBoostLastEngineMode)
        val lastUseTdd = preferences.getBoostDosing(StringKey.ApsBoostLastUseTdd)
        val useTdd = preferences.getBoostDosing(BooleanKey.ApsBoostUseTdd)
        val velocityOnPct = DoubleKey.ApsBoostDynIsfVelocity.defaultValue
        val r = reconcileSensitivitySettings(
            useTdd = useTdd,
            adjustSens = preferences.getBoostDosing(BooleanKey.ApsBoostAdjustSensitivity),
            velocityPct = preferences.getBoostDosing(DoubleKey.ApsBoostDynIsfVelocity),
            switchedToV6 = isSwitchToV6(lastMode, v5Active),
            tddJustEnabled = isTddJustEnabled(lastUseTdd, useTdd),
            velocityOnPct = velocityOnPct
        )
        if (r.clearedAdjustSens) preferences.put(BooleanKey.ApsBoostAdjustSensitivity, false)
        if (r.zeroedVelocity || r.restoredVelocity) preferences.put(DoubleKey.ApsBoostDynIsfVelocity, r.velocityPct)
        val mode = if (v5Active) ENGINE_MODE_V6 else ENGINE_MODE_V1
        if (lastMode != mode) preferences.put(StringKey.ApsBoostLastEngineMode, mode)
        if (lastUseTdd != useTdd.toString()) preferences.put(StringKey.ApsBoostLastUseTdd, useTdd.toString())
        if (r.changed) {
            val what = listOfNotNull(
                if (r.clearedAdjustSens) "TDD sensitivity adjustment switched off" else null,
                if (r.zeroedVelocity) "BG impact on ISF set to 0%" else null,
                if (r.restoredVelocity) "BG impact on ISF set to ${velocityOnPct.toInt()}%" else null
            ).joinToString(" and ")
            val why = when {
                !useTdd            -> "TDD-based ISF is off"
                r.restoredVelocity -> "TDD-based ISF was switched on"
                else               -> "V6 was enabled"
            }
            aapsLogger.info(LTag.APS, "Boost sensitivity settings: $what ($why)")
            notificationManager.post(NotificationId.BOOST_MESSAGE, "Boost: $what, because $why", level = NotificationLevel.LOW)
        }
    }

    // Dynamic ISF
    // NOTE: all these Boost getters use preferences.getBoostDosing(...) (not .get) so Simple Mode
    // does NOT mask the user's / auto-config's stored dosing settings to factory defaults. The keys
    // keep defaultedBySM = true (they stay HIDDEN in Simple Mode); only the doser's read is unmasked.
    // See BoostDosingPreferences.kt.
    private val dynIsfNormalTarget; get() = profileUtil.convertToMgdlDetect(preferences.getBoostDosing(UnitDoubleKey.ApsBoostDynIsfNormalTarget, profileUtil))
    private val dynIsfVelocity; get() = preferences.getBoostDosing(DoubleKey.ApsBoostDynIsfVelocity) / 100.0
    private val dynIsfBgCap; get() = profileUtil.convertToMgdlDetect(preferences.getBoostDosing(UnitDoubleKey.ApsBoostDynIsfBgCap, profileUtil))

    // Boost SMB
    private val boostBolus; get() = preferences.getBoostDosing(DoubleKey.ApsBoostBolus)
    private val boostMaxIob; get() = preferences.getBoostDosing(DoubleKey.ApsBoostMaxIob)
    private val boostInsulinReqPct; get() = preferences.getBoostDosing(DoubleKey.ApsBoostInsulinReqPct)
    private val boostScale; get() = preferences.getBoostDosing(DoubleKey.ApsBoostScale)
    private val enableCircadianIsf; get() = preferences.getBoostDosing(BooleanKey.ApsBoostEnableCircadianIsf)
    private val allowBoostWithHighTt; get() = preferences.getBoostDosing(BooleanKey.ApsBoostAllowWithHighTt)

    // Boost time window retired — boostActive now derives from the night-mode period
    // (isInNightSleepPeriod). ApsBoostStartTime/EndTime are no longer read.
    private val sleepInHours; get() = preferences.getBoostDosing(DoubleKey.ApsBoostSleepInHours)

    // Step counting thresholds
    private val inactivitySteps; get() = preferences.getBoostDosing(IntKey.ApsBoostInactivitySteps)
    private val inactivityPct; get() = preferences.getBoostDosing(DoubleKey.ApsBoostInactivityPct)
    private val sleepInSteps; get() = preferences.getBoostDosing(IntKey.ApsBoostSleepInSteps)
    private val activitySteps5; get() = preferences.getBoostDosing(IntKey.ApsBoostActivitySteps5)
    private val activitySteps15; get() = preferences.getBoostDosing(IntKey.ApsBoostActivitySteps15)
    private val activitySteps30; get() = preferences.getBoostDosing(IntKey.ApsBoostActivitySteps30)
    private val activitySteps60; get() = preferences.getBoostDosing(IntKey.ApsBoostActivitySteps60)
    private val activityPct; get() = preferences.getBoostDosing(DoubleKey.ApsBoostActivityPct)

    // Heart rate integration
    private val hrIntegrationEnabled; get() = preferences.getBoostDosing(BooleanKey.ApsBoostHrIntegrationEnabled)
    private val hrMaxBpm; get() = preferences.getBoostDosing(IntKey.ApsBoostHrMaxBpm)
    private val hrRestingBpm; get() = preferences.getBoostDosing(IntKey.ApsBoostHrRestingBpm)
    private val hrWindowMinutes; get() = preferences.getBoostDosing(IntKey.ApsBoostHrWindowMinutes)
    private val hrStressDetection; get() = preferences.getBoostDosing(BooleanKey.ApsBoostHrStressDetection)

    // Post-exercise recovery
    private val postExerciseRecoveryEnabled; get() = preferences.getBoostDosing(BooleanKey.ApsBoostPostExerciseRecoveryEnabled)
    private val postExerciseRecoveryHours; get() = preferences.getBoostDosing(DoubleKey.ApsBoostPostExerciseRecoveryHours)
    private val postExerciseRecoveryTarget; get() = profileUtil.convertToMgdlDetect(preferences.getBoostDosing(UnitDoubleKey.ApsBoostPostExerciseRecoveryTarget, profileUtil))
    private val postExerciseRecoveryScale; get() = preferences.getBoostDosing(DoubleKey.ApsBoostPostExerciseRecoveryScale)
    private val postExerciseMinDuration; get() = preferences.getBoostDosing(IntKey.ApsBoostPostExerciseMinDuration)

    // ---- Feed-health edge detection (F4/F6) ----
    // HR: fresh→dark transitions + one waking-hours notification per dark episode.
    private val hrFeedDarkTracker = HrFeedDarkTracker()
    // Steps: boostSteps_feed transitions, reason-line only (no notification).
    @Volatile private var lastStepsFeed: String? = null

    private val confirmTranche by lazy { app.aaps.plugins.aps.openAPSBoostV5.ConfirmTrancheController() }

    // ---- Post-exercise recovery state ----
    @Volatile private var recoveryWindowEnd: Long = 0L
    @Volatile private var wasExerciseActive: Boolean = false
    @Volatile private var exerciseStartTime: Long = 0L
    @Volatile private var lastExerciseStateAtTransition: String = "ACTIVE"
    // Start time of the recovery TempTarget Boost itself inserted, so the hypo-rebound auto-cancel
    // can tell it apart from one the user set. Null after a restart, which leaves Boost's own target
    // to expire on its own (the safe direction).
    @Volatile private var boostRecoveryTtStartMs: Long? = null
    @Volatile private var activeRecoveryScale: Double = 0.5
    @Volatile private var activeRecoveryTargetOffset: Double = 0.0

    // When the sleep detector last left SLEEPING by its clock boundary (wakeReason "boundary"). Read
    // by [sleepSignals] to hold the sleep exclusions for one hysteresis period. In memory only: after
    // a restart there is no hold, which is the behaviour before.
    @Volatile private var lastSleepBoundaryExitMs: Long? = null

    // ---- Lifecycle ----

    // specialEnableCondition / specialShowInListCondition (temp-basal-capable pump) have no AAPS 4.0
    // equivalent: SafetyPlugin.isLoopInvocationAllowed now forces the running mode to DISABLED_LOOP
    // for a pump that cannot do temp basals, for every APS plugin.

    /**
     * True when THIS engine should run/constrain: either plain "Boost" (V1) is the selected APS,
     * OR the selectable "Boost V5" plugin is (it delegates to [runEngine]). Without this, when V5
     * is the active APS, V1's own isEnabled() is false → runEngine's guard would post "disabled"
     * and the maxIOB/maxBasal constraint methods would silently stop applying. (
     */
    private fun engineActive(): Boolean =
        isEnabled() || runCatching { boostV5Plugin().isEnabled() }.getOrDefault(false)

    // 3.4 supportsDynamicIsf() is usingDynamicIsf() in AAPS 4.0.
    override fun usingDynamicIsf() = true

    // The phone step counter needs ACTIVITY_RECOGNITION. Boost 3.4 asked for it through the app's
    // permission notifications; AAPS 4.0 asks for what each plugin declares here.
    override fun requiredPermissions(): List<PermissionGroup> = listOf(
        PermissionGroup(
            permissions = listOf(Manifest.permission.ACTIVITY_RECOGNITION),
            rationaleTitle = ApsStrings.boost_permission_activity_title,
            rationaleDescription = ApsStrings.boost_permission_activity_description
        )
    )

    override fun getIsfMgdl(profile: Profile, caller: String): Double? {
        // Boost computes ISF dynamically inside determine_basal via getIsfByProfile()
        // Return null to fall back to profile ISF for external callers
        return null
    }

    override fun getSensitivityOverviewString(): String? = null

    // preprocessPreferences (hiding the SMB-with-COB/low-TT/after-carbs switches while SMB-always is
    // on) has no AAPS 4.0 hook; the Compose screen applies each key's own visibility rules.

    // ---- ISF Pre-calculation ----
    // Mirrors the IsfCalculatorImpl from 3.2, computing sensNormalTarget and variable_sens
    // These are then passed to DetermineBasalBoost which uses them in getIsfByProfile()

    data class BoostIsfResult(
        val sensNormalTarget: Double,
        val variableSens: Double,
        val bgCapped: Double,
        val insulinDivisor: Int,
        val dynIsfVelocity: Double,
        val ratio: Double,
        val tdd: Double,
        val isfDebug: String = "",
        val vwaTddShadow: BoostVwaTddShadow.Result? = null
    )

    private suspend fun calculateBoostIsf(
        profileSens: Double,
        profilePercent: Int,
        targetBg: Double,
        insulinDivisor: Int,
        glucoseValue: Double,
        isTempTarget: Boolean,
        orefAutosensRatio: Double
    ): BoostIsfResult {
        val autosensMax = preferences.get(DoubleKey.AutosensMax)
        val autosensMin = preferences.get(DoubleKey.AutosensMin)
        // Read-time guard for the reconcile above: with TDD off, BG has no impact on ISF and there is
        // no sensitivity adjustment, whatever is stored.
        val useTdd = preferences.getBoostDosing(BooleanKey.ApsBoostUseTdd)
        val velocity = if (useTdd) dynIsfVelocity else 0.0
        val bgCap = dynIsfBgCap
        val bgNormalTarget = dynIsfNormalTarget
        val highTtRaisesSens = preferences.get(BooleanKey.ApsAutoIsfHighTtRaisesSens)
        val lowTtLowersSens = preferences.get(BooleanKey.ApsAutoIsfLowTtLowersSens)
        val halfBasalTarget = SMBDefaults.half_basal_exercise_target

        val globalScale = 100.0 / profilePercent.toDouble()

        var sensNormalTarget = profileSens
        var ratio = 1.0
        var vwaShadowResult: BoostVwaTddShadow.Result? = null
        var tdd = 0.0
        val bgCurrent = if (glucoseValue > bgCap) bgCap + ((glucoseValue - bgCap) / 3.0) else glucoseValue

        val debug = StringBuilder()

        // TDD-based ISF calculation
        val adjustSens = useTdd && preferences.getBoostDosing(BooleanKey.ApsBoostAdjustSensitivity)

        // The seven-day dose, kept for the volume-weighted shadow below, which reads it itself when off.
        var tdd7DForVwa: Double? = null
        if (useTdd) {
            // Fetch all TDD components — use allowMissingDays=true so partial data still works
            val tdd7D = tddCalculator.averageTDD(tddCalculator.calculate(7, allowMissingDays = true))?.data?.totalAmount
            tdd7DForVwa = tdd7D
            val tdd1D = tddCalculator.averageTDD(tddCalculator.calculate(1, allowMissingDays = true))?.data?.totalAmount
            val tddLast24H = tddCalculator.calculateDaily(-24, 0)?.totalAmount
            val tddLast4H = tddCalculator.calculateDaily(-4, 0)?.totalAmount
            val tddLast8to4H = tddCalculator.calculateDaily(-8, -4)?.totalAmount

            debug.append("TDD data: 7D=${tdd7D?.let { Round.roundTo(it, 0.1) } ?: "null"}")
            debug.append(" | 1D=${tdd1D?.let { Round.roundTo(it, 0.1) } ?: "null"}")
            debug.append(" | 24H=${tddLast24H?.let { Round.roundTo(it, 0.1) } ?: "null"}")
            debug.append(" | 4H=${tddLast4H?.let { Round.roundTo(it, 0.1) } ?: "null"}")
            debug.append(" | 8-4H=${tddLast8to4H?.let { Round.roundTo(it, 0.1) } ?: "null"}")

            // Require ALL critical components — same safety gate as standard DynISF
            if (tdd7D != null && tdd1D != null && tddLast24H != null && tddLast4H != null && tddLast8to4H != null && tdd7D > 0) {
                val tddWeightedFromLast8H = ((1.4 * tddLast4H) + (0.6 * tddLast8to4H)) * 3
                debug.append("\nWeighted8H=${Round.roundTo(tddWeightedFromLast8H, 0.1)} (4H×1.4 + 8-4H×0.6)×3")

                if (tddWeightedFromLast8H < (0.75 * tdd7D)) {
                    // Recent insulin usage significantly below 7D average —
                    // pull the 7D average down toward recent reality before blending
                    val adjusted7D = tddWeightedFromLast8H + ((tddWeightedFromLast8H / tdd7D) * (tdd7D - tddWeightedFromLast8H))
                    tdd = (adjusted7D * 0.34) + (tdd1D * 0.33) + (tddWeightedFromLast8H * 0.33)
                    debug.append("\nW8H < 75% of 7D → adjusted7D=${Round.roundTo(adjusted7D, 0.1)} (7D ${Round.roundTo(tdd7D, 0.1)} pulled toward W8H)")
                } else {
                    // Standard blend
                    tdd = (tddWeightedFromLast8H * 0.33) + (tdd7D * 0.34) + (tdd1D * 0.33)
                    debug.append("\nStandard blend (W8H×.33 + 7D×.34 + 1D×.33)")
                }
                debug.append("\nBlended TDD=${Round.roundTo(tdd, 0.1)}")

                // Adjustment factor from Boost DynISF preferences (default 100%)
                val dynIsfAdjust = preferences.get(IntKey.ApsBoostDynIsfAdjustmentFactor).toDouble().coerceIn(1.0, 300.0)
                tdd *= dynIsfAdjust / 100.0
                debug.append("\nFinal TDD=${Round.roundTo(tdd, 0.1)} (adj factor ${dynIsfAdjust.toInt()}%)")

                // Safety: TDD must be positive and produce a sane ISF
                val logTerm = ln((bgNormalTarget / insulinDivisor) + 1.0)
                // IMPLAUSIBLE-TDD GUARD. The old condition was `tdd > 0` only, and the
                // comment above claimed it ensured "a sane ISF" — it did not. A TDD of 0.1 U/day
                // passes, and 1800/(tdd × logTerm) then explodes. Observed in the field on a
                // cross-fork migration: a fresh AAPS database reported TDD 3.1–4.1 U/day against a
                // true ~20, dynamic ISF reached 5550–8944 mg/dL/U against a profile ISF of 100
                // (~88×), insulinReq computed at or below zero, and the loop delivered NOTHING for
                // 3.5 h while BG climbed to 276 — 19 consecutive zero temp basals, no lows, no alarm.
                //
                // The floor is anchored on the profile's OWN implied TDD via the 1800 rule
                // (profileISF ≈ 1800/TDD), so it is self-scaling per user and needs no magic ISF
                // multiplier: a U200 user, a child and a high-TDD adult are all handled by their own
                // profile. Falling back is SAFE-SIGNED — profileSens is the value the user/clinician
                // configured, so a false positive costs dynamic responsiveness for that cycle and
                // nothing else, which is why the fraction is set liberally rather than tightly.
                val impliedTdd = if (profileSens > 0) 1800.0 / profileSens else 0.0
                val tddImplausible = tddImplausibleForProfile(tdd, profileSens)
                if (tddImplausible) {
                    // Leave sensNormalTarget at profileSens (its initial value) — do NOT derive.
                    debug.append(
                        "\n⚠ dynISF=PROFILE-FALLBACK: TDD ${Round.roundTo(tdd, 0.1)} U/day is below " +
                            "${Round.roundTo(impliedTdd * DYNISF_MIN_TDD_FRACTION, 0.1)} " +
                            "(${(DYNISF_MIN_TDD_FRACTION * 100).toInt()}% of the ${Round.roundTo(impliedTdd, 0.1)} " +
                            "implied by profile ISF ${Round.roundTo(profileSens, 0.1)}) — insulin history looks " +
                            "incomplete; using profile ISF instead of a derived one this cycle"
                    )
                    aapsLogger.info(
                        LTag.APS,
                        "Boost dynISF profile-fallback: tdd=${Round.roundTo(tdd, 0.1)} < " +
                            "${Round.roundTo(impliedTdd * DYNISF_MIN_TDD_FRACTION, 0.1)} implied-floor; using profileSens=$profileSens"
                    )
                }
                if (tdd > 0 && logTerm > 0 && !tddImplausible) {
                    sensNormalTarget = 1800.0 / (tdd * logTerm)
                    sensNormalTarget *= globalScale
                    debug.append("\nTDD ISF at target: ${Round.roundTo(sensNormalTarget, 0.1)} mg/dl/U (profile was ${Round.roundTo(profileSens, 0.1)})")

                    if (adjustSens && tddLast24H > 0) {
                        ratio = max(min(tddLast24H / tdd7D, autosensMax), autosensMin)
                        sensNormalTarget /= ratio
                        debug.append("\nSens ratio: ${Round.roundTo(ratio, 0.01)} (24H/7D = ${Round.roundTo(tddLast24H, 0.1)}/${Round.roundTo(tdd7D, 0.1)}) → ISF=${Round.roundTo(sensNormalTarget, 0.1)}")
                    }

                } else {
                    debug.append("\n⚠ TDD calculation produced invalid values (tdd=$tdd, logTerm=$logTerm) — using profile ISF")
                    aapsLogger.warn(LTag.APS, "Boost TDD ISF: invalid tdd=$tdd or logTerm=$logTerm, falling back to profile ISF")
                }
            } else {
                debug.append("\n⚠ TDD data incomplete — using profile ISF")
                aapsLogger.debug(LTag.APS, "Boost: TDD parts missing (7D=$tdd7D 1D=$tdd1D 24H=$tddLast24H 4H=$tddLast4H 8-4H=$tddLast8to4H)")
            }
        } else {
            debug.append("TDD-based ISF: disabled (using profile ISF ${Round.roundTo(profileSens, 0.1)})")
        }

        // Volume-weighted dose shadow. Computes an alternative blend from the insulin delivered so
        // far today against the participant's own delivery curve, and logs it. It does not touch tdd,
        // sensNormalTarget or anything downstream: the candidate failed one of the four
        // targets it was judged on, and the only route from here to dosing is a within-person trial. It runs whether or not the TDD-based ISF is on or
        // complete, since it needs only the seven-day dose ( it was silent for everyone
        // with TDD-based ISF off), and a failure in it leaves the cycle as it was.
        vwaShadowResult = try {
            vwaShadowCycle(tdd7DForVwa)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            aapsLogger.error(LTag.APS, "VwaTdd shadow failed (swallowed)", t)
            null
        }
        vwaShadowResult?.let { debug.append("\n${it.debugLine}") }

        // Temp target sensitivity adjustment
        if (isTempTarget && ((highTtRaisesSens && targetBg > bgNormalTarget) || (lowTtLowersSens && targetBg < bgNormalTarget))) {
            val c = (halfBasalTarget - bgNormalTarget).toDouble()
            if (c * (c + targetBg - bgNormalTarget) > 0.0) {
                ratio = c / (c + targetBg - bgNormalTarget)
                ratio = max(min(ratio, autosensMax), autosensMin)
                sensNormalTarget /= ratio
                debug.append("\nTT adjustment: ratio=${Round.roundTo(ratio, 0.01)} → ISF=${Round.roundTo(sensNormalTarget, 0.1)}")
                aapsLogger.debug(LTag.APS, "Boost ISF adjusted by ${1.0 / ratio} due to TT of ${targetBg.toInt()}")
            }
        }

        // Autosens on a static profile ISF: fold the ratio into the ISF at target, which every sensitivity
        // the engine uses is built from (predictions, current BGI and the dosing sensitivity), as stock
        // oref does with profile.sens / sensitivityRatio.
        val autosensSens = autosensAdjustedIsf(
            sensNormalTarget = sensNormalTarget,
            useTdd = useTdd,
            autosensWhenNoTdd = preferences.getBoostDosing(BooleanKey.ApsBoostAutosensWhenNoTdd),
            tempTargetRatio = ratio,
            orefAutosensRatio = orefAutosensRatio
        )
        if (autosensSens != sensNormalTarget) {
            debug.append("\nAutosens: ratio=${Round.roundTo(orefAutosensRatio, 0.01)} → ISF at target ${Round.roundTo(sensNormalTarget, 0.1)} to ${Round.roundTo(autosensSens, 0.1)}")
            sensNormalTarget = autosensSens
        }

        // Calculate variable_sens using log formula
        val sbg = ln((bgCurrent / insulinDivisor) + 1.0)
        val scaler = ln((bgNormalTarget / insulinDivisor) + 1.0) / sbg
        val variableSens = sensNormalTarget * (1 - (1 - scaler) * velocity)

        debug.append("\nVariable ISF at BG ${Round.roundTo(glucoseValue, 1.0)}: ${Round.roundTo(variableSens, 0.1)} (velocity=${Round.roundTo(velocity * 100, 1.0)}%)")

        aapsLogger.debug(LTag.APS, "Boost ISF: $debug")

        return BoostIsfResult(
            sensNormalTarget = Round.roundTo(sensNormalTarget, 0.1),
            variableSens = Round.roundTo(variableSens, 0.1),
            bgCapped = bgCurrent,
            insulinDivisor = insulinDivisor,
            dynIsfVelocity = velocity,
            ratio = Round.roundTo(ratio, 0.01),
            tdd = tdd,
            isfDebug = debug.toString(),
            vwaTddShadow = vwaShadowResult
        )
    }

    // ---- Boost Time Window & Activity Detection ----
    // Mirrors logic from DetermineBasalAdapterBoostJS.setData()

    data class BoostActivityResult(
        val boostActive: Boolean,
        val profileSwitch: Int,
        val minBg: Double,
        val maxBg: Double,
        val targetBg: Double,
        val activityState: String = "none",
        val debugReason: String = "",
        // True when the step-based sleep-in (lie-in) gate is suppressing Boost this cycle. Cached by
        // the caller so isNightModeActiveImpl() applies night-mode SMB rules during a lie-in.
        val sleepInActive: Boolean = false,
        // Which step feeds are live this cycle (F1): "phone+wear" | "phone" | "wear" |
        // "none" — written to RT.boostSteps_feed every cycle so a dark feed is visible in NS.
        val stepsFeed: String = "none",
        // The configured night window as a clock fact this cycle (read whatever the night-mode toggle
        // says). The pre-meal target and the meal-time learner key on it.
        val inNightWindow: Boolean = false
    )

    private suspend fun calculateBoostActivity(
        now: Long,
        tempTargetSet: Boolean,
        targetBg: Double,
        minBg: Double,
        maxBg: Double,
        profilePercent: Int,
        v6Active: Boolean
    ): BoostActivityResult {
        val debug = StringBuilder()
        val midnight = now - MidnightUtils.milliSecFromMidnight(now)
        val sleepInMillis = (3600000.0 * sleepInHours).toLong()

        // Boost is active whenever the user is NOT in their night/sleep period, EXCLUDING night mode's
        // BG gate: a nocturnal high (incl. a sensor spike) must NOT re-enable Boost, or a full V6
        // meal-amplified SMB could fire while asleep. `ApsBoostStartTime`/
        // `ApsBoostEndTime` are retired. Since the configured night window
        // closes the gate whatever the night-mode toggle says, and so does SLEEPING under V6; see
        // [boostGateOpen]. Carbs on board do not reopen it: inside the window they only lift night
        // mode's SMB suppression, so the SMB is base oref, as in PRE_SLEEP.
        val nightEndMs = midnight + parseTimeToMillisOrDefault(preferences.getBoostDosing(StringKey.ApsBoostNightModeEnd), "07:00")
        // The configured night window as a clock fact, read WHATEVER ApsBoostNightModeEnabled says.
        // Needs no HR, no steps and no detector.
        val nightStartMs = midnight + parseTimeToMillisOrDefault(preferences.getBoostDosing(StringKey.ApsBoostNightModeStart), "22:00")
        val inNightWindow = NightWindow.contains(now, nightStartMs, nightEndMs)
        // Sleep signals with the boundary-exit hold (audit #18): for one hysteresis period
        // after the detector leaves SLEEPING by its clock boundary, the plugin keeps treating the user
        // as asleep. See [sleepSignals].
        val sleepSig = sleepSignals(
            state = sleepStateCached.state,
            nightSleepPeriodRaw = isInNightSleepPeriod(),
            nightModeEnabled = preferences.getBoostDosing(BooleanKey.ApsBoostNightModeEnabled),
            autoBySleep = preferences.getBoostDosing(BooleanKey.ApsBoostNightModeAutoBySleep),
            nowMs = now,
            lastBoundaryExitMs = lastSleepBoundaryExitMs,
            holdMin = boundaryExitHoldMin(preferences.getBoostDosing(IntKey.ApsBoostSleepHysteresisMin))
        )
        val detectorSleeping = sleepSig.detectorSleeping
        val nightSleepPeriod = sleepSig.nightSleepPeriod

        var boostActive = boostGateOpen(nightSleepPeriod, inNightWindow, v6Active, detectorSleeping)
        var disableReason = ""
        if (!boostActive) disableReason = when {
            inNightWindow    -> "Night window (clock)"
            nightSleepPeriod -> "Night/sleep period (night mode active by time or HR/steps)"
            else             -> "Sleeping (V6 stands down; base oref SMB)"
        }

        val nowTime = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
        debug.append("Boost gate: night/sleep=${!boostActive} (window=$inNightWindow period=$nightSleepPeriod v6Sleeping=${v6Active && detectorSleeping}) | Now: ${nowTime.format(DateTimeFormatter.ofPattern("HH:mm"))}")
        if (sleepSig.boundaryHold) debug.append(" | sleep boundary-exit hold")

        // Disable boost if high temp target and not allowed
        if (boostActive && tempTargetSet && !allowBoostWithHighTt && targetBg > dynIsfNormalTarget) {
            boostActive = false
            disableReason = "High temp target ($targetBg > ${dynIsfNormalTarget})"
            aapsLogger.debug(LTag.APS, "Boost disabled due to high temptarget of $targetBg")
        }

        var recentSteps5Min = StepService.getRecentStepCount5Min()
        var recentSteps15Min = StepService.getRecentStepCount15Min()
        var recentSteps30Min = StepService.getRecentStepCount30Min()
        var recentSteps60Min = StepService.getRecentStepCount60Min()
        // F3 fix: the live exercise classifier read PHONE StepService ONLY, so watch
        // steps (wear AND Garmin, which land in the SC table) never influenced exercise state — a
        // pre-existing gap that Garmin workstream B forced into the open. Blend the freshest watch
        // SC row's trailing windows in ADDITIVELY (max): can only ADD activity detection, never
        // remove protection. NOTE: this is a dosing-behaviour change (exercise state → activity
        // target/gating) — backtest before relying on it beyond on-device validation.
        run {
            val recentSc = try {
                persistenceLayer.getStepsCountFromTimeToTime(dateUtil.now() - 15 * 60_000L, dateUtil.now())
            } catch (t: Throwable) { emptyList() }
            recentSc.maxByOrNull { it.timestamp }?.let { sc ->
                recentSteps5Min = maxOf(recentSteps5Min, sc.steps5min)
                recentSteps15Min = maxOf(recentSteps15Min, sc.steps15min)
                recentSteps30Min = maxOf(recentSteps30Min, sc.steps30min)
                recentSteps60Min = maxOf(recentSteps60Min, sc.steps60min)
            }
        }

        debug.append("\nSteps: 5m=$recentSteps5Min 15m=$recentSteps15Min 30m=$recentSteps30Min 60m=$recentSteps60Min")

        // ── Step-source availability guard (F1) ──
        // 0 steps from a feed that never reported is NOT sedentary — it's unknown. Available =
        // phone pedometer LIVE this boot OR a wear SC row within the WearStepSource freshness
        // window. When unavailable: no INACTIVE profile drop, no steps-based sleep-in, and the
        // outage is breadcrumbed (debugReason + boostSteps_feed RT). See StepFeed KDoc.
        val wearScRecent = try {
            persistenceLayer.getStepsCountFromTimeToTime(now - WearStepSource.FRESH_MS, now)
        } catch (t: Throwable) {
            emptyList()
        }
        val stepFeed = StepFeed.State(
            phoneLive = StepService.feedState() == StepService.FeedState.LIVE,
            wearAgeMs = WearStepSource.latest(wearScRecent)?.let { now - it.timestamp }
        )
        val stepsAvailable = stepFeed.available
        if (!stepsAvailable) debug.append("\n${stepFeed.unavailableNote()}")

        // Steps-based sleep-in (lie-in). MERGE folded this into SleepStateDetector — when
        // night-mode-auto-by-sleep is ON, the detector HOLDS SLEEPING through the lie-in window
        // (nightEnd → nightEnd+sleepInHours) and releases on the SAME sleepInSteps threshold. This
        // standalone gate is the FAILSAFE. It engages unless the detector is ACTIVELY holding sleep
        // (auto-by-sleep ON *and* state == SLEEPING) — so it also covers the detector's documented
        // false-AWAKE mode during the lie-in (previously the gate stood down for the whole window
        // whenever auto-by-sleep was on, leaving a dawn false-AWAKE unprotected). sleepStateCached is
        // last cycle's state (this cycle's is evaluated later); one-cycle (~5 min) lag is acceptable
        // for a lie-in backstop.
        // Anchored to the same morning end as the detector's lie-in: the configured end, or the learned
        // wake when later (up to the band). Anchoring to the configured end ended this check while the
        // detector's own lie-in ran on, so the two disagreed for up to 90 min.
        val lieInEndOfNightMs = sleepEvalCached?.inputs?.nightEndMin?.let { m -> midnight + m * 60_000L } ?: nightEndMs
        val sleepInActive = StepFeed.sleepInActive(stepsAvailable, now, lieInEndOfNightMs, sleepInMillis, recentSteps60Min, sleepInSteps)
        val autoBySleepActive = preferences.get(BooleanKey.ApsBoostNightModeAutoBySleep)
        val nightModeEnabled = preferences.getBoostDosing(BooleanKey.ApsBoostNightModeEnabled)
        // Sleep as the INACTIVE branch must see it. SLEEPING is confirmed sleep; PRE_SLEEP is the
        // wind-down before it, and the user is in bed for both. This covers the CORE NIGHT, which
        // sleepInActive cannot reach — that window opens AT night end, so before a 07:00 end
        // it is false by construction.
        val detectorAsleep = sleepSig.detectorAsleep
        // inNightWindow (computed above) is what makes "INACTIVE never fires overnight" hold by default
        // instead of depending on the user having enabled night mode.
        // Audit trail for the fix: record when the step test alone WOULD have raised the
        // profile but sleep blocked it, so the suppression is visible in NS rather than silent.
        if (StepFeed.inactivityStepsMet(stepsAvailable, profilePercent, recentSteps60Min, inactivitySteps) &&
            (sleepInActive || detectorAsleep || inNightWindow)
        ) {
            debug.append("\nInactivity SUPPRESSED by sleep (60m steps $recentSteps60Min < $inactivitySteps; " +
                "nightWindow=$inNightWindow sleepIn=$sleepInActive detector=${sleepStateCached.state}) → profile held at 100%")
        }
        // A genuine wake (steps or HR + steps) since the start of this morning's wake grace window.
        val genuineWakeThisMorning = lastGenuineWakeMs >= lieInEndOfNightMs - SleepStateDetector.SLEEP_SCHEDULE_TOLERANCE_MIN * 60_000L
        if (boostActive && StepFeed.lieInFailsafeEngages(sleepInActive, nightModeEnabled, autoBySleepActive, detectorSleeping, genuineWakeThisMorning)) {
            boostActive = false
            disableReason = "Sleep-in failsafe (60m steps $recentSteps60Min < threshold $sleepInSteps, within ${sleepInHours}h of night end; auto-by-sleep=$autoBySleepActive detector=${sleepStateCached.state})"
            aapsLogger.debug(LTag.APS, "Boost disabled due to lie-in (failsafe; auto-by-sleep=$autoBySleepActive detector=${sleepStateCached.state})")
        }

        // ---- HR-augmented classification (opt-in, additive only) ----
        val hrClassification: HrActivityCalculator.HrClassificationResult? =
            if (hrIntegrationEnabled) {
                val windowMs = hrWindowMinutes * 60_000L
                val hrReadings = persistenceLayer.getHeartRatesFromTime(now - windowMs)
                HrActivityCalculator.classify(
                    hrReadings = hrReadings,
                    nowMillis = now,
                    hrWindowMinutes = hrWindowMinutes,
                    hrMax = hrMaxBpm,
                    // Use learned daytime baseline if banked (≥7 nights); fallback to configured.
                    // This gives a more accurate Karvonen HRR for exercise classification.
                    hrResting = hrLearnedDaytimeBpmCached ?: hrRestingBpm,
                    stepsLast15Min = recentSteps15Min,
                    stressDetection = hrStressDetection,
                    aapsLogger = aapsLogger,
                ).takeIf { it.hrZone != HrActivityCalculator.HrZone.NONE } // null if no HR data
            } else null

        // Exercise and HR classification run whatever the Boost gate says. They lower the
        // profile or raise the target, so an exercise session inside the night window keeps its
        // reduced profile and raised target, and an evening session crossing night start is not cut
        // off (which also started post-exercise recovery early). Only the INACTIVE raise, which adds
        // insulin, still needs the gate open.
        val classification = classifyBoostActivity(
            BoostActivityInputs(
                raiseGateOpen = boostActive,
                isActive = (activitySteps5 > 0 && recentSteps5Min > activitySteps5)
                    || (activitySteps15 > 0 && recentSteps15Min > activitySteps15)
                    || (activitySteps30 > 0 && recentSteps30Min > activitySteps30)
                    || (activitySteps60 > 0 && recentSteps60Min > activitySteps60),
                hr = hrClassification,
                hrIntegrationEnabled = hrIntegrationEnabled,
                hrStressDetection = hrStressDetection,
                inactivityEligible = StepFeed.inactivityEligible(
                    stepsAvailable, profilePercent, recentSteps60Min, inactivitySteps,
                    sleepInActive = sleepInActive, asleep = detectorAsleep,
                    inNightWindow = inNightWindow
                ),
                stepsAvailable = stepsAvailable,
                tempTargetSet = tempTargetSet,
                profilePercent = profilePercent,
                activityPct = activityPct,
                inactivityPct = inactivityPct,
                minBg = minBg,
                maxBg = maxBg,
                targetBg = targetBg,
                recentSteps60Min = recentSteps60Min,
                inactivitySteps = inactivitySteps
            )
        )
        debug.append(classification.debug)
        aapsLogger.debug(LTag.APS, "Boost activity: ${classification.state} profile ${classification.profileSwitch}% target ${classification.targetBg}")
        val activityMinBg = classification.minBg
        val activityMaxBg = classification.maxBg
        val activityTargetBg = classification.targetBg
        val currentProfileSwitch = classification.profileSwitch
        val activityState = classification.state

        if (boostActive) {
            debug.append("\n✓ BOOST ACTIVE ($activityState)")
        } else {
            debug.append("\n✗ BOOST INACTIVE: $disableReason")
        }

        return BoostActivityResult(
            boostActive = boostActive,
            profileSwitch = currentProfileSwitch,
            minBg = activityMinBg,
            maxBg = activityMaxBg,
            targetBg = activityTargetBg,
            activityState = activityState,
            debugReason = debug.toString(),
            sleepInActive = sleepInActive,
            stepsFeed = stepFeed.label,
            inNightWindow = inNightWindow
        )
    }

    private fun parseTimeToMillis(timeStr: String): Long {
        return try {
            val time = LocalTime.parse(timeStr, DateTimeFormatter.ofPattern("H:mm"))
            (time.hour * 3600000L) + (time.minute * 60000L)
        } catch (_: DateTimeParseException) {
            try {
                val time = LocalTime.parse(timeStr, DateTimeFormatter.ofPattern("HH:mm"))
                (time.hour * 3600000L) + (time.minute * 60000L)
            } catch (_: DateTimeParseException) {
                aapsLogger.error(LTag.APS, "Failed to parse time: $timeStr, defaulting to 0")
                0L
            }
        }
    }

    /** [parseTimeToMillis] but falling back to [defaultStr] — NOT silently to midnight — on a
     *  malformed pref. Midnight-on-typo moved the whole night window (night "ending" at 00:00 put
     *  the sleep-in anchor at midnight and Boost fully active from 00:00 while asleep). */
    private fun parseTimeToMillisOrDefault(timeStr: String, defaultStr: String): Long {
        return try {
            val time = LocalTime.parse(timeStr, DateTimeFormatter.ofPattern("H:mm"))
            (time.hour * 3600000L) + (time.minute * 60000L)
        } catch (_: DateTimeParseException) {
            try {
                val time = LocalTime.parse(timeStr, DateTimeFormatter.ofPattern("HH:mm"))
                (time.hour * 3600000L) + (time.minute * 60000L)
            } catch (_: DateTimeParseException) {
                aapsLogger.error(LTag.APS, "Malformed night-mode time '$timeStr' — using default $defaultStr")
                parseTimeToMillis(defaultStr)
            }
        }
    }

    /** Returns minute-of-day [0..1439] for a "HH:mm" or "H:mm" string. Defaults to 0 on parse error. */
    private fun parseTimeToMinutesOfDay(timeStr: String): Int =
        (parseTimeToMillis(timeStr) / 60_000L).toInt()

    /** [parseTimeToMinutesOfDay] falling back to [defaultStr] on a malformed value, as the gate does. */
    private fun parseTimeToMinutesOfDayOrDefault(timeStr: String, defaultStr: String): Int =
        (parseTimeToMillisOrDefault(timeStr, defaultStr) / 60_000L).toInt()

    /**
     * Clamp a learned minute-of-day to within ±[bandMin] of the configured minute-of-day, on the
     * 24h circle. Returns [configured] when [learned] is null (no/insufficient learned data). This
     * caps how far the learned sleep window can drift from the user's configured times — the safety
     * bound that, with the genuine-wake-only training in SleepHistoryTracker, stops the night-window
     * collapse (each night ratcheting the wake earlier).
     */
    /** Max minutes the learned sleep window may move from the configured night start/end. */
    private val LEARNED_WINDOW_BAND_MIN = 90

    private fun clampToConfiguredBand(learned: Int?, configured: Int, bandMin: Int, allowEarlier: Boolean = true): Int {
        if (learned == null) return configured
        // signed circular delta in [-720, 719] (positive = learned is LATER than configured)
        val delta = ((learned - configured + 1440 + 720) % 1440) - 720
        // allowEarlier=false makes the nudge one-sided: learning may only move the bound LATER than the
        // configured time, never earlier. Used for night-END (wake), where moving earlier would lift
        // sleep protection before the user's own setting — the unsafe direction — and is a poor fit for a
        // highly-variable waker (learned point-mean sits early; real-time detection catches genuine early
        // wakes anyway). Night-START keeps the symmetric band (later-to-bed drift is expected + safe).
        val loBand = if (allowEarlier) -bandMin else 0
        val clamped = delta.coerceIn(loBand, bandMin)
        return ((configured + clamped) % 1440 + 1440) % 1440
    }

    /** Format a minute-of-day [0..1439] as "HH:mm" for telemetry. */
    private fun formatClockMin(min: Int): String {
        val m = ((min % 1440) + 1440) % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }

    // ---- Main invoke ----

    // Not called: this engine is not in the plugin list, and the Boost plugin calls [runEngine].
    override suspend fun invoke(initiator: String, tempBasalFallback: Boolean) = withContext(Dispatchers.Default) {
        runEngine(initiator, tempBasalFallback)
    }

    /**
     * The Boost dosing engine. Computes the base decision (oref sizing, basal, predictions and the
     * safety gates), then lets V6's observe-confirm-commit decision replace the SMB at the seam below.
     */
    suspend fun runEngine(initiator: String, tempBasalFallback: Boolean) {
        aapsLogger.debug(LTag.APS, "runEngine from $initiator tempBasalFallback: $tempBasalFallback")
        // Trigger Health Connect HR ingest. Throttled internally; no-op if disabled.
        healthConnectHrIngest.syncIfDue()
        // Health Connect STEPS ingest (activity-load shadow). Throttled internally.
        healthConnectStepsIngest.syncIfDue()
        lastAPSResult = null
        val glucoseStatus = glucoseStatusCalculatorSMB.glucoseStatusData
        val profile = profileFunction.getProfile()
        val pump = activePlugin.activePump
        if (profile == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(CoreUiStrings.no_profile_set)))
            aapsLogger.debug(LTag.APS, rh.gs(CoreUiStrings.no_profile_set))
            return
        }
        if (!engineActive()) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(ApsStrings.openapsma_disabled)))
            aapsLogger.debug(LTag.APS, rh.gs(ApsStrings.openapsma_disabled))
            return
        }
        if (glucoseStatus == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(ApsStrings.openapsma_no_glucose_data)))
            aapsLogger.debug(LTag.APS, rh.gs(ApsStrings.openapsma_no_glucose_data))
            return
        }

        applySensitivitySettingsReconcile(v5Active = true)

        val inputConstraints = ConstraintObject(0.0, aapsLogger)

        if (!hardLimits.checkHardLimits(profile.iCfg.dia, InterfacesStrings.profile_dia, hardLimits.diaRange())) return
        if (!hardLimits.checkHardLimits(
                profile.getIcTimeFromMidnight(MidnightUtils.secondsFromMidnight()),
                InterfacesStrings.profile_carbs_ratio_value,
                hardLimits.icRange()
            )
        ) return
        if (!hardLimits.checkHardLimits(profile.getIsfMgdl("OpenAPSBoostPlugin"), InterfacesStrings.profile_sensitivity_value, HardLimits.LIMIT_ISF)) return
        if (!hardLimits.checkHardLimits(profile.getMaxDailyBasal(), CoreUiStrings.profile_max_daily_basal_value, 0.02, hardLimits.maxBasal())) return
        if (!hardLimits.checkHardLimits(ch.fromPump(pump.baseBasalRate), CoreUiStrings.current_basal_value, 0.01, hardLimits.maxBasal())) return

        // End of checks, start gathering data

        val smbEnabled = preferences.get(BooleanKey.ApsUseSmb)
        val allowAllBgSources = true // Boost: always allow all BG sources (forced on; user toggle removed)
        val advancedFiltering = allowAllBgSources || constraintsChecker.isAdvancedFilteringEnabled().also { inputConstraints.copyReasons(it) }.value()
        val now = dateUtil.now()
        // Sleep state for THIS cycle, before the SMB constraint and the Boost gate read it. It used
        // to run at the end of the cycle, so the gate always saw the previous cycle's state and a
        // wake reached dosing one cycle late.
        sleepEvalCached = evaluateSleep(now)

        val tb = processedTbrEbData.getTempBasalIncludingConvertedExtended(now)
        val currentTemp = CurrentTemp(
            duration = tb?.plannedRemainingMinutes ?: 0,
            rate = tb?.convertedToAbsolute(now, profile) ?: 0.0,
            minutesrunning = tb?.getPassedDurationToTimeInMinutes(now)
        )

        var minBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetLowMgdl(), 0.1), InterfacesStrings.profile_low_target, BOOST_LIMIT_MIN_BG)
        var maxBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetHighMgdl(), 0.1), InterfacesStrings.profile_high_target, BOOST_LIMIT_MAX_BG)
        var targetBg = hardLimits.verifyHardLimits(profile.getTargetMgdl(), CoreUiStrings.temp_target_value, BOOST_LIMIT_TARGET_BG)
        var isTempTarget = false
        persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())?.let { tempTarget ->
            isTempTarget = true
            minBg = hardLimits.verifyHardLimits(tempTarget.lowTarget, CoreUiStrings.temp_target_low_target, BOOST_LIMIT_TEMP_MIN_BG)
            maxBg = hardLimits.verifyHardLimits(tempTarget.highTarget, CoreUiStrings.temp_target_high_target, BOOST_LIMIT_TEMP_MAX_BG)
            targetBg = hardLimits.verifyHardLimits(tempTarget.target(), CoreUiStrings.temp_target_value, BOOST_LIMIT_TEMP_TARGET_BG)
        }

        var autosensResult = AutosensResult()
        if (constraintsChecker.isAutosensModeEnabled().value()) {
            val autosensData = iobCobCalculator.getLastAutosensDataWithWaitForCalculationFinish("OpenAPSBoostPlugin")
            if (autosensData == null) {
                rxBus.send(EventResetOpenAPSGui(rh.gs(ApsStrings.openaps_no_as_data)))
                return
            }
            autosensResult = autosensData.autosensResult
        } else autosensResult.sensResult = "autosens disabled"

        val iobArray = iobCobCalculator.calculateIobArrayForSMB(autosensResult, SMBDefaults.exercise_mode, SMBDefaults.half_basal_exercise_target, isTempTarget)
        val mealData = iobCobCalculator.getMealDataWithWaitingForCalculationFinish()
        val profilePercent = if (profile is ProfileSealed.EPS) profile.value.originalPercentage else 100
        val microBolusAllowedByConstraints = constraintsChecker.isSMBModeEnabled(ConstraintObject(tempBasalFallback.not(), aapsLogger)).also { inputConstraints.copyReasons(it) }.value()
        val microBolusAllowed = if (dateUtil.now() < calibrationBlockedUntil) {
            val remainingMin = (calibrationBlockedUntil - dateUtil.now()) / 60_000
            aapsLogger.debug(LTag.APS, "Boost SMB blocked: calibration detected ${remainingMin}min ago, ${15 - remainingMin}min elapsed of 15")
            false
        } else microBolusAllowedByConstraints
        val flatBGsDetected = bgQualityCheck.state == BgQualityCheck.State.FLAT

        // ---- Boost-specific calculations ----

        // 1. Activity detection & boost time window
        val activityResult = calculateBoostActivity(now, isTempTarget, targetBg, minBg, maxBg, profilePercent, v6Active = true)
        // Publish the step-based sleep-in state for next cycle's night-mode evaluation.
        sleepInActiveCached = activityResult.sleepInActive

        // 1b. Post-exercise recovery transition detection
        // HR-aware: all exercise states (aerobic, resistance) trigger recovery, not just "ACTIVE".
        // Recovery window duration, target BG, and SMB scale are adjusted per exercise type.
        if (postExerciseRecoveryEnabled) {
            val exerciseStateSet = setOf("ACTIVE", "VIGOROUS_AEROBIC", "MODERATE_AEROBIC", "LIGHT_AEROBIC", "RESISTANCE")
            val isCurrentlyActive = activityResult.activityState in exerciseStateSet
            if (isCurrentlyActive && !wasExerciseActive) {
                exerciseStartTime = now
                aapsLogger.debug(LTag.APS, "Boost post-exercise: exercise started (${activityResult.activityState}) at ${dateUtil.dateAndTimeString(exerciseStartTime)}")
            } else if (!isCurrentlyActive && wasExerciseActive) {
                val exerciseDurationMin = (now - exerciseStartTime) / 60_000L
                aapsLogger.debug(LTag.APS, "Boost post-exercise: exercise ended (was $lastExerciseStateAtTransition) after ${exerciseDurationMin}min")
                if (exerciseDurationMin >= postExerciseMinDuration) {
                    // Adjust recovery parameters based on exercise type (HR-classified or step-only).
                    // Multipliers are evidence-based relative to the user's configured baseline:
                    //   VIGOROUS_AEROBIC  — high immediate hypo risk: longer window, more SMB suppression
                    //   RESISTANCE        — delayed hypo risk + acute BG rise: longest window, less SMB
                    //                       suppression (BG runs high initially), slightly higher target
                    //   LIGHT_AEROBIC     — minimal glycogen depletion: shorter window, less suppression
                    //   ACTIVE/MODERATE   — baseline (no multiplier)
                    val (windowMultiplier, targetOffsetMgdl, scaleMultiplier) = when (lastExerciseStateAtTransition) {
                        "VIGOROUS_AEROBIC" -> Triple(1.25, 0.0,  0.8)
                        "RESISTANCE"       -> Triple(1.5,  10.0, 1.2)
                        "LIGHT_AEROBIC"    -> Triple(0.5,  0.0,  1.4)
                        else               -> Triple(1.0,  0.0,  1.0)
                    }
                    val recoveryMillis = (postExerciseRecoveryHours * 3600_000L * windowMultiplier).toLong()
                    val recoveryTargetMgdl = postExerciseRecoveryTarget + targetOffsetMgdl
                    activeRecoveryScale = (postExerciseRecoveryScale * scaleMultiplier).coerceIn(0.1, 1.0)
                    activeRecoveryTargetOffset = targetOffsetMgdl
                    recoveryWindowEnd = now + recoveryMillis
                    aapsLogger.debug(LTag.APS, "Boost post-exercise [$lastExerciseStateAtTransition]: window=${recoveryMillis / 60_000}min target=${recoveryTargetMgdl.toInt()}mg/dL SMBscale=$activeRecoveryScale")
                    if (persistenceLayer.getTemporaryTargetActiveAt(now) == null) {
                        boostRecoveryTtStartMs = now
                        val tt = TT(
                            timestamp = now,
                            duration = recoveryMillis,
                            reason = TT.Reason.ACTIVITY,
                            lowTarget = recoveryTargetMgdl,
                            highTarget = recoveryTargetMgdl
                        )
                        // AAPS 4.0: a suspend insert in place of the Rx subscription, with the same
                        // success and failure log lines. It completes before the cycle continues.
                        try {
                            persistenceLayer.insertAndCancelCurrentTemporaryTarget(
                                temporaryTarget = tt,
                                action = Action.TT,
                                source = Sources.Aaps,
                                note = rh.gs(R.string.boost_post_exercise_recovery_title),
                                listValues = listOf(
                                    ValueWithUnit.TETTReason(TT.Reason.ACTIVITY),
                                    ValueWithUnit.Mgdl(recoveryTargetMgdl),
                                    ValueWithUnit.Minute(TimeUnit.MILLISECONDS.toMinutes(recoveryMillis).toInt())
                                )
                            )
                            aapsLogger.debug(LTag.APS, "Boost post-exercise: TempTarget inserted (${recoveryTargetMgdl.toInt()} mg/dL for ${TimeUnit.MILLISECONDS.toMinutes(recoveryMillis)}min)")
                        } catch (e: Exception) {
                            aapsLogger.error(LTag.APS, "Boost post-exercise: failed to insert TempTarget", e)
                        }
                    } else {
                        aapsLogger.debug(LTag.APS, "Boost post-exercise: TempTarget already active — skipping insert")
                    }
                } else {
                    aapsLogger.debug(LTag.APS, "Boost post-exercise: exercise too brief (${exerciseDurationMin}min < ${postExerciseMinDuration}min) — no recovery")
                }
            }
            if (isCurrentlyActive) lastExerciseStateAtTransition = activityResult.activityState
            wasExerciseActive = isCurrentlyActive
        }

        // 2. Insulin peak / divisor calculation
        // AAPS 4.0 carries the insulin configuration on the profile (iCfg) rather than on an active
        // insulin plugin; the peak is the same quantity.
        val insulinPeak = profile.iCfg.peak.coerceIn(30, 75)
        val insulinDivisor = if (insulinPeak < 60) (90 - insulinPeak) + 30 else (90 - insulinPeak) + 40

        // 3. ISF pre-calculation
        // Profile switch inversely scales ISF: 80% profile → 125% ISF (more sensitive),
        // 120% profile → 83% ISF (more resistant).
        val profileScale = activityResult.profileSwitch.toDouble() / 100.0
        val scaledProfileSens = profile.getIsfMgdl("OpenAPSBoostPlugin") / profileScale
        val isfResult = calculateBoostIsf(
            profileSens = scaledProfileSens,
            profilePercent = activityResult.profileSwitch,
            targetBg = targetBg,
            insulinDivisor = insulinDivisor,
            glucoseValue = glucoseStatus.glucose,
            isTempTarget = isTempTarget,
            orefAutosensRatio = autosensResult.ratio
        )

        // 4. Sensitivity ratio that drives basal / target / CR scaling in determine_basal.
        //    Two DISTINCT levers feed DetermineBasalBoost:
        //      • variable_sens (ISF, from isfResult) — the DynISF curve; always carries BG/velocity
        //        sensitivity and is used as `sens` directly (no autosens division — dynISF mode).
        //      • autosensResult.ratio — scales basal (`current_basal * sensitivityRatio`), shifts
        //        targets, and adjusts remainingCATime/CR (DetermineBasalBoost ~383-400, 601).
        //    TDD-DynISF and traditional oref autosens are ALTERNATIVE adaptation mechanisms — never
        //    stack both (would double-count sensitivity). So:
        //      • useTdd ON  → the TDD model (24H/7D, in isfResult.ratio) owns sensitivity; autosens off.
        //      • useTdd OFF → ISF is the profile-anchored curve; traditional oref autosens (already in
        //        autosensResult.ratio, computed above and gated by ApsUseAutosens) should drive
        //        basal/target/CR. Mirrors reference DynISF SMB (sens=variable_sens; autosens scales
        //        basal only). Gated by ApsBoostAutosensWhenNoTdd — default OFF (legacy: the curve
        //        ratio scales basal) until validated on the oref-vs-curve shadow telemetry logged below.
        val orefAutosensRatio = autosensResult.ratio     // real oref autosens (1.0 when autosens disabled)
        val useTdd = preferences.getBoostDosing(BooleanKey.ApsBoostUseTdd)
        val autosensWhenNoTdd = preferences.getBoostDosing(BooleanKey.ApsBoostAutosensWhenNoTdd)
        autosensResult.ratio = selectSensitivityRatio(useTdd, autosensWhenNoTdd, isfResult.ratio, orefAutosensRatio)

        // 5. Adjust basal if profile switch from activity
        val currentBasal = if (activityResult.profileSwitch != 100) {
            val adjusted = ch.fromPump(pump.baseBasalRate) * profileScale
            aapsLogger.debug(LTag.APS, "Basal adjusted to $adjusted")
            adjusted
        } else ch.fromPump(pump.baseBasalRate)

        // 6. Recent BG nadir + braking signal (for fast-carb detection)
        val now60MinAgo = System.currentTimeMillis() - 60 * 60 * 1000L
        val now45MinAgo = System.currentTimeMillis() - 45 * 60 * 1000L
        val recentBgReadings = persistenceLayer.getBgReadingsDataFromTimeToTime(now60MinAgo, System.currentTimeMillis(), true)
        val recentLowBG = recentBgReadings.minOfOrNull { it.value }?.toDouble() ?: 999.0
        // v4.4.4 hotfix Fix A v2 (ported to V1): 45-min rolling minimum used only by
        // Fix A post-rescue tier gating. See V3MLG3 plugin/determine_basal for backtest rationale.
        val recentLowBG45Min = recentBgReadings.filter { it.timestamp >= now45MinAgo }.minOfOrNull { it.value }?.toDouble() ?: 999.0
        // Braking product: max(|delta2| × (delta2 - delta1)) across consecutive triplets
        // where delta2 < 0 (still falling) and delta2 > delta1 (deceleration).
        // High values indicate rapid carb absorption arresting a fall — fast-carb signal
        // even when BG never went below the low threshold.
        val recentBrakingProduct = recentBgReadings.windowed(3).mapNotNull { w ->
            val delta1 = w[1].value - w[0].value
            val delta2 = w[2].value - w[1].value
            val improvement = delta2 - delta1
            if (delta2 < 0 && improvement > 0) Math.abs(delta2) * improvement else null
        }.maxOrNull()?.toDouble() ?: 0.0

        // 6b. Auto-cancel recovery TempTarget during hypo rescue rebound
        // When a hypo has occurred (recentLowBG < 100) and BG is now rising above it,
        // a post-exercise recovery TempTarget is counterproductive — it suppresses Boost
        // via the high-TT check, preventing the algorithm from responding to the carb overshoot.
        // Cancel the recovery TT and reset target to profile so Boost can re-engage.
        if (recentLowBG < 100.0 && glucoseStatus.glucose > recentLowBG + 20) {
            val activeTt = persistenceLayer.getTemporaryTargetActiveAt(now)
            if (isBoostRecoveryTt(activeTt?.timestamp, activeTt?.reason, boostRecoveryTtStartMs)) {
                aapsLogger.debug(LTag.APS, "Boost: cancelling recovery TempTarget — hypo rebound detected (recentLow=${recentLowBG.toInt()}, BG now ${glucoseStatus.glucose.toInt()})")
                try {
                    persistenceLayer.cancelCurrentTemporaryTargetIfAny(
                        timestamp = now,
                        action = Action.TT_REMOVED,
                        source = Sources.Aaps,
                        note = "Auto-cancelled: hypo rebound (low ${recentLowBG.toInt()} mg/dL)",
                        listValues = listOf(ValueWithUnit.TETTReason(TT.Reason.ACTIVITY))
                    )
                } catch (e: Exception) {
                    aapsLogger.error(LTag.APS, "Boost: failed to cancel recovery TempTarget", e)
                }
                // Also clear the recovery window so SMB reduction doesn't persist
                recoveryWindowEnd = 0L
                boostRecoveryTtStartMs = null
                // Reset targets back to profile values
                isTempTarget = false
                minBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetLowMgdl(), 0.1), InterfacesStrings.profile_low_target, BOOST_LIMIT_MIN_BG)
                maxBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetHighMgdl(), 0.1), InterfacesStrings.profile_high_target, BOOST_LIMIT_MAX_BG)
                targetBg = hardLimits.verifyHardLimits(profile.getTargetMgdl(), CoreUiStrings.temp_target_value, BOOST_LIMIT_TARGET_BG)
            }
        }

        // 7. Step counts
        val recentSteps5Min = StepService.getRecentStepCount5Min()
        val recentSteps15Min = StepService.getRecentStepCount15Min()
        val recentSteps30Min = StepService.getRecentStepCount30Min()
        val recentSteps60Min = StepService.getRecentStepCount60Min()

        // 7b. V6 anticipatory pre-meal low target (shadow-first).
        // Learned habitual meal times (from V5 CONFIRMED commits) lower the target ~45-60 min
        // before a meal so insulinReq is already elevated when carbs land. Exercise and
        // post-exercise recovery OVERRIDE this (activity raises the target; we only fire when not
        // active and not recovering). LOWER-ONLY: never raises a target. Shadow gate — when
        // ApsBoostV6PreMealTarget is OFF we only log "WOULD apply" for NS validation, no change.
        var v6MinBg = activityResult.minBg
        var v6MaxBg = activityResult.maxBg
        var v6TargetBg = activityResult.targetBg
        var v6PreMealReason: String? = null
        run {
            val nowLocal = java.time.LocalTime.now()
            val nowMin = nowLocal.hour * 60 + nowLocal.minute
            val offsetMs = java.time.ZoneId.systemDefault().rules.getOffset(java.time.Instant.now()).totalSeconds * 1000L
            val leadMaxMin = preferences.getBoostDosing(DoubleKey.ApsBoostV6PreMealLeadMin).toInt()
            // Drop learned meal sessions that fall inside the configured night window (
            // audit #8). Sessions recorded overnight before the learner was gated can otherwise form a
            // mode and pull the lowered target into the night. Persisted only when something was dropped.
            val filtered = MealTimeLearner.withoutNightEvents(
                mealTimeHistoryCached,
                parseTimeToMinutesOfDayOrDefault(preferences.getBoostDosing(StringKey.ApsBoostNightModeStart), "22:00"),
                parseTimeToMinutesOfDayOrDefault(preferences.getBoostDosing(StringKey.ApsBoostNightModeEnd), "07:00"),
                offsetMs
            )
            if (filtered.events.size != mealTimeHistoryCached.events.size) {
                aapsLogger.info(LTag.APS, "V6 meal-time learner: dropped ${mealTimeHistoryCached.events.size - filtered.events.size} learned sessions inside the night window")
                mealTimeHistoryCached = filtered
                preferences.put(StringKey.ApsBoostMealTimeHistory, filtered.serialize())
            }
            val hit = MealTimeLearner.preMealWindow(mealTimeHistoryCached, nowMin, offsetMs, leadMaxMin) ?: return@run
            val exerciseNow = activityResult.activityState in setOf("ACTIVE", "VIGOROUS_AEROBIC", "MODERATE_AEROBIC", "LIGHT_AEROBIC", "RESISTANCE", "STRESS")
            val inRecovery = postExerciseRecoveryEnabled && now < recoveryWindowEnd
            if (exerciseNow || inRecovery) {
                v6PreMealReason = "V6 pre-meal SUPPRESSED (${if (exerciseNow) "exercise" else "recovery"}); "
                return@run
            }
            preMealTargetBlock(
                inNightWindow = activityResult.inNightWindow,
                sleepState = sleepStateCached.state,
                tempTargetActive = isTempTarget,
                postRescueWindow = recentLowBG45Min < DetermineBasalBoost.POST_RESCUE_LOW_THRESHOLD_MGDL
            )?.let { why ->
                v6PreMealReason = "V6 pre-meal SUPPRESSED ($why); "
                return@run
            }
            val preMealTarget = preferences.getBoostDosing(DoubleKey.ApsBoostV6PreMealTargetMgdl)
            val mealClock = formatClockMin(hit.mode.centreMin)
            v6PreMealReason = if (preferences.getBoostDosing(BooleanKey.ApsBoostV6PreMealTarget)) {
                if (preMealTarget < v6TargetBg) {   // lower-only
                    v6MinBg = minOf(v6MinBg, preMealTarget)
                    v6MaxBg = minOf(v6MaxBg, preMealTarget)
                    v6TargetBg = preMealTarget
                    "V6 pre-meal ACTIVE target=${preMealTarget.toInt()} (learned ~$mealClock, ${hit.minutesBeforeMeal}min before, ${hit.mode.distinctDays}d); "
                } else {
                    "V6 pre-meal skipped (target ${preMealTarget.toInt()} ≥ current ${v6TargetBg.toInt()}); "
                }
            } else {
                "V6 pre-meal WOULD apply ${preMealTarget.toInt()} (learned ~$mealClock, ${hit.minutesBeforeMeal}min before, ${hit.mode.distinctDays}d); "
            }
        }

        // ---- Build the OapsProfileBoost ----

        val oapsProfile = OapsProfileBoost(
            // variable_sens already carries the sensitivity adaptation: TDD when that is on, autosens
            // (autosensAdjustedIsf) when it is off. The engine must not divide by the ratio again.
            dynIsfMode = true,
            // Standard oref1 fields
            dia = 0.0,
            min_5m_carbimpact = 0.0,
            max_iob = constraintsChecker.getMaxIOBAllowed().also { inputConstraints.copyReasons(it) }.value(),
            max_daily_basal = profile.getMaxDailyBasal(),
            max_basal = constraintsChecker.getMaxBasalAllowed(profile).also { inputConstraints.copyReasons(it) }.value(),
            min_bg = v6MinBg,
            max_bg = v6MaxBg,
            target_bg = v6TargetBg,
            carb_ratio = profile.getIc(),
            sens = profile.getIsfMgdl("OpenAPSBoostPlugin"),
            autosens_adjust_targets = false,
            max_daily_safety_multiplier = preferences.get(DoubleKey.ApsMaxDailyMultiplier),
            current_basal_safety_multiplier = preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier),
            lgsThreshold = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.ApsLgsThreshold)).toInt(),
            high_temptarget_raises_sensitivity = preferences.get(BooleanKey.ApsAutoIsfHighTtRaisesSens),
            low_temptarget_lowers_sensitivity = preferences.get(BooleanKey.ApsAutoIsfLowTtLowersSens),
            sensitivity_raises_target = true,
            resistance_lowers_target = true,
            adv_target_adjustments = true,
            exercise_mode = SMBDefaults.exercise_mode,
            half_basal_exercise_target = SMBDefaults.half_basal_exercise_target,
            maxCOB = SMBDefaults.maxCOB,
            skip_neutral_temps = pump.setNeutralTempAtFullHour(),
            remainingCarbsCap = SMBDefaults.remainingCarbsCap,
            enableUAM = constraintsChecker.isUAMEnabled().also { inputConstraints.copyReasons(it) }.value(),
            A52_risk_enable = SMBDefaults.A52_risk_enable,
            SMBInterval = preferences.get(IntKey.ApsMaxSmbFrequency),
            enableSMB_with_COB = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithCob),
            enableSMB_with_temptarget = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithLowTt),
            allowSMB_with_high_temptarget = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithHighTt),
            enableSMB_always = smbEnabled && preferences.get(BooleanKey.ApsUseSmbAlways) && advancedFiltering,
            enableSMB_after_carbs = smbEnabled && preferences.get(BooleanKey.ApsUseSmbAfterCarbs) && advancedFiltering,
            maxSMBBasalMinutes = preferences.get(IntKey.ApsMaxMinutesOfBasalToLimitSmb),
            maxUAMSMBBasalMinutes = preferences.get(IntKey.ApsUamMaxMinutesOfBasalToLimitSmb),
            bolus_increment = pump.pumpDescription.bolusStep,
            carbsReqThreshold = preferences.get(IntKey.ApsCarbsRequestThreshold),
            current_basal = currentBasal,
            temptargetSet = isTempTarget,
            autosens_max = preferences.get(DoubleKey.AutosensMax),
            out_units = if (profileFunction.getUnits() == GlucoseUnit.MMOL) "mmol/L" else "mg/dl",

            // Dynamic ISF fields
            dynISFBgCap = dynIsfBgCap,
            dynISFBgCapped = isfResult.bgCapped,
            sensNormalTarget = isfResult.sensNormalTarget,
            normalTarget = dynIsfNormalTarget,
            dynISFvelocity = isfResult.dynIsfVelocity,
            insulinPeak = insulinPeak,
            variable_sens = isfResult.variableSens,
            insulinDivisor = insulinDivisor,
            TDD = isfResult.tdd,

            // Boost SMB fields
            boostActive = activityResult.boostActive,
            profileSwitch = activityResult.profileSwitch,
            boost_bolus = if (postExerciseRecoveryEnabled && now < recoveryWindowEnd) {
                val scaled = boostBolus * activeRecoveryScale
                aapsLogger.debug(LTag.APS, "Boost post-exercise recovery [$lastExerciseStateAtTransition]: boost_bolus $boostBolus → $scaled (scale=$activeRecoveryScale)")
                scaled
            } else boostBolus,
            boost_maxIOB = boostMaxIob,
            Boost_InsulinReq = boostInsulinReqPct,
            boost_scale = if (postExerciseRecoveryEnabled && now < recoveryWindowEnd) {
                val scaled = boostScale * activeRecoveryScale
                aapsLogger.debug(LTag.APS, "Boost post-exercise recovery [$lastExerciseStateAtTransition]: boost_scale $boostScale → $scaled (scale=$activeRecoveryScale)")
                scaled
            } else boostScale,
            enableCircadianISF = enableCircadianIsf,
            allowBoost_with_high_temptarget = allowBoostWithHighTt,

            // Step counter data
            recentSteps5Minutes = recentSteps5Min,
            recentSteps15Minutes = recentSteps15Min,
            recentSteps30Minutes = recentSteps30Min,
            recentSteps60Minutes = recentSteps60Min,

            // Fast-carb rebound detection
            recentLowBG = recentLowBG,
            recentBrakingProduct = recentBrakingProduct,

            // Debug context
            boostDebugReason = activityResult.debugReason,
            isfDebugReason = isfResult.isfDebug,

            // V5/V6 exercise inputs (F2) — fill so OpenAPSBoostV5Plugin's decide() sees
            // the live activity state. These fields were DEAD on the live path since the V6 plugin
            // split: only the retired OpenAPSBoostV3MLG3Plugin ever set them, so V6's meal-score
            // exercise damping (MealSignalScore.notExercisingTerm), the fastConfirm !exercising gate
            // (MealHypothesis.step), and the AggressionBudget post-exercise damper had NEVER engaged
            // live — the exercise-into-correction hypo class this batch targets. Mapping mirrors
            // V3MLG3's exact block (OpenAPSBoostV3MLG3Plugin, v5_* assignments) via the shared
            // helpers below ([v5ExerciseActive]/[v5InPostExerciseWindow]).
            v5_exerciseActive = v5ExerciseActive(activityResult.activityState),
            v5_inPostExerciseWindow = v5InPostExerciseWindow(postExerciseRecoveryEnabled, now, recoveryWindowEnd),
            v5_exerciseSubclass = activityResult.activityState,
        )

        aapsLogger.debug(LTag.APS, ">>> Invoking determine_basal Boost <<<")
        aapsLogger.debug(LTag.APS, "Glucose status:     $glucoseStatus")
        aapsLogger.debug(LTag.APS, "Current temp:       $currentTemp")
        aapsLogger.debug(LTag.APS, "IOB data:           ${iobArray.joinToString()}")
        aapsLogger.debug(LTag.APS, "Profile:            $oapsProfile")
        aapsLogger.debug(LTag.APS, "Autosens data:      $autosensResult")
        aapsLogger.debug(LTag.APS, "Meal data:          $mealData")
        aapsLogger.debug(LTag.APS, "MicroBolusAllowed:  $microBolusAllowed")
        aapsLogger.debug(LTag.APS, "flatBGsDetected:    $flatBGsDetected")
        aapsLogger.debug(LTag.APS, "BoostActive:        ${activityResult.boostActive}")

        // v4.4.3 hotfix Fix B (ported to V1): compute cumulative SMB volume delivered
        // in the last 60 min. Pulled from PersistenceLayer rather than synthesised from IOB —
        // IOB is decay-adjusted, but the rolling-window cap wants raw delivered amounts.
        // Filters to BS.Type.SMB so manual user boluses don't contribute.
        val cumulativeSmbCap60Min = preferences.getBoostDosing(DoubleKey.ApsBoostCumulativeSmbCap60Min)
        // v12 ML uses both the 60-min volume (already used by the cap) and the
        // minutes-since-last-SMB. Query a wider 12-hr window once and derive both.
        val (recentSmbVolume60Min, timeSinceLastSmbMin) = try {
            val twelveHrAgo = now - 12 * 60 * 60 * 1000L
            val smbs = persistenceLayer.getBolusesFromTimeToTime(twelveHrAgo, now, ascending = false)
                .filter { it.type == BS.Type.SMB && it.isValid }
            val sixtyMinAgo = now - 60 * 60 * 1000L
            val sum60 = smbs.filter { it.timestamp >= sixtyMinAgo }.sumOf { it.amount }
            val tSince = smbs.firstOrNull()?.let {
                kotlin.math.min(720.0, (now - it.timestamp) / 60_000.0)
            } ?: 720.0
            Pair(sum60, tSince)
        } catch (e: Exception) {
            // FAIL CLOSED: a DB failure must not disarm the anti-stacking cap — treat the
            // 60-min volume as AT the cap so both V1's internal cap and the V5-override re-check
            // suppress SMB this cycle (basal control continues; SMBs resume when the query recovers).
            // Previously fell open to 0.0 ("cap will not engage this cycle") — the backstop vanished
            // exactly when state was already degraded. tSince stays 720 (neutral ML feature).
            aapsLogger.error(LTag.APS, "Boost V1 recent SMB volume query failed (${e.message}) — FAIL CLOSED: treating 60-min volume as at-cap; SMB suspended this cycle")
            Pair(if (cumulativeSmbCap60Min > 0.0) cumulativeSmbCap60Min else 0.0, 720.0)
        }
        aapsLogger.debug(LTag.APS, "Boost V1 cumulative SMB last 60min: ${recentSmbVolume60Min}U / cap ${cumulativeSmbCap60Min}U | minutes-since-last-SMB: ${timeSinceLastSmbMin}")

        // v12 ML lookback ring buffer — restore from storage once per process so the
        // lag0..lag5 windowed features survive an AAPS restart instead of cold-starting
        // (zero-imputed) for the first ~6 cycles. No-op after the first cycle.
        determineBasalBoost.loadMlRingBufferOnce(preferences.getBoostDosing(StringKey.ApsBoostMlRingBuffer))

        // ── Post-rescue TIGHT-RAMP trial arm (crossover) ──
        // Enrolment is an explicit per-user preference; the arm is a pure function of a
        // once-generated install seed and the LOCAL day index, so the offline analysis can
        // reproduce every day's assignment from the seed alone. Treatment days cap the
        // post-rescue scale at 0.60 and apply it across the whole window; control days run the
        // shipped guard untouched. Neither arm can dose above today's behaviour.
        val tightRampCap: Double? = if (preferences.getBoostDosing(BooleanKey.ApsBoostPostRescueTightRampTrial)) {
            var seed = preferences.getBoostDosing(StringKey.ApsBoostTrialSeed)
            if (seed.isEmpty()) {                       // first cycle after enrolment
                seed = java.util.UUID.randomUUID().toString()
                preferences.put(StringKey.ApsBoostTrialSeed, seed)
                aapsLogger.info(LTag.APS, "Post-rescue tight-ramp trial: seed generated, enrolled")
            }
            val dayIndex = java.time.Instant.ofEpochMilli(now)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
            if (DetermineBasalBoost.tightRampArm(seed, dayIndex)) DetermineBasalBoost.TIGHT_RAMP_CAP else null
        } else null

        determineBasalBoost.determine_basal(
            glucose_status = glucoseStatus,
            currenttemp = currentTemp,
            iob_data_array = iobArray,
            profile = oapsProfile,
            autosens_data = autosensResult,
            meal_data = mealData,
            microBolusAllowed = microBolusAllowed,
            currentTime = now,
            flatBGsDetected = flatBGsDetected,
            // Layer A ML retrofit — models compute scores that are emitted to NS
            // via RT.mlHypoRisk / RT.mlMealLikely. Dosing behaviour unchanged.
            riskModel = boostRiskModel,
            mealModel = boostMealModel,
            recentSmbVolume60Min = recentSmbVolume60Min,
            cumulativeSmbCap60Min = cumulativeSmbCap60Min,
            recentLowBG45Min = recentLowBG45Min,
            timeSinceLastSmbMin = timeSinceLastSmbMin,
            postRescueTightRampCap = tightRampCap
        ).also {
            // Trial arm tag, every cycle (not only when the guard fires), so the analysis can
            // count exposure on days the guard never engaged. enrolled,arm,cap.
            it.reason.append(
                "prTrial=${if (preferences.getBoostDosing(BooleanKey.ApsBoostPostRescueTightRampTrial)) 1 else 0}," +
                    "${if (tightRampCap != null) "tight" else "control"},${tightRampCap ?: 0.0}; ")
            // Volume-weighted dose shadow. Read-only: the blend it proposes and the working
            // behind it, so the paired estimates accumulate from the first cycle.
            isfResult.vwaTddShadow?.let { v ->
                it.boostVwa_blend = Round.roundTo(v.vwaBlend, 0.01)
                it.boostVwa_projection = Round.roundTo(v.projection, 0.01)
                it.boostVwa_expected = Round.roundTo(v.expectedToday, 0.01)
                it.boostVwa_delivered = Round.roundTo(v.deliveredToday, 0.01)
                it.boostVwa_dayFraction = Round.roundTo(v.dayFraction, 0.001)
                it.boostVwa_calibratedTdd = Round.roundTo(v.calibratedTdd, 0.01)
                it.boostVwa_curveDays = v.curveDays
                it.boostVwa_usedPrevDay = v.usedPreviousDay
            }
            // Persist the v12 ML lookback ring buffer (updated in-place during this
            // cycle's inference) so the lag features survive a process restart.
            preferences.put(StringKey.ApsBoostMlRingBuffer, determineBasalBoost.serializeMlRingBuffer())
            // V5 — runs BEFORE EventAPSCalculationFinished. In SHADOW mode (toggle off) V5 only
            // attaches boostV5_* telemetry. In ACTIVE mode (ApsBoostV5ActiveDosing) V5's finalDose
            // REPLACES V1's SMB on cycles V1 permits one (microBolusAllowed) — V1 still owns basal,
            // predictions and every safety gate; the overridden rT.units flows through downstream
            // pump/bolus constraints unchanged. runShadow returns null on internal error → V1's
            // dose is left intact. V5 can never raise the dose above its own caps + maxIOB clamp.
            // Prior-cycle sleep state (updated end-of-invoke). Passed into V5 so the fast-carb
            // fast-path is gated OFF overnight, and reused below for the dose-level sleep gate.
            val v5Asleep = sleepStateCached.state == SleepStateDetector.SleepState.SLEEPING
            // Post-rescue window (hoisted above runShadow so V5's composed-floor
            // shadow can read it) — the SAME source value (recentLowBG45Min, computed once in step 6
            // above and passed into determine_basal) and the SAME shared threshold as V1's Fix A v2
            // post-rescue tier guard, so this flag is true exactly when V1's own dose is the
            // hypo-restrained one. Logged every cycle as boostV5_postRescueWindow (shadow and active)
            // so the live review can audit windows without CGM reconstruction.
            val inPostRescueWindow = recentLowBG45Min < DetermineBasalBoost.POST_RESCUE_LOW_THRESHOLD_MGDL
            it.boostV5_postRescueWindow = inPostRescueWindow
            // Cumulative-cap telemetry: the rolling-60-min anti-stacking cap and the
            // volume it compares against were previously invisible in NS — a cap suppression looked
            // identical to a zero-dose decision ( forensic). Written EVERY Boost cycle
            // (shadow + active); the same recentSmbVolume60Min already computed above (fail-closed
            // to at-cap on DB error, so the telemetry mirrors the operative value exactly).
            it.boostV5_cumulativeCapU = cumulativeSmbCap60Min
            it.boostV5_smbVol60Min = recentSmbVolume60Min
            val v5decision = try {
                boostV5Plugin().runShadow(
                    rT = it,
                    glucoseStatus = glucoseStatus,
                    iobArray = iobArray,
                    oapsProfile = oapsProfile,
                    pumpBolusStep = activePlugin.activePump.pumpDescription.bolusStep,
                    activeMode = true,
                    microBolusAllowed = microBolusAllowed,
                    flatBGsDetected = flatBGsDetected,
                    asleep = v5Asleep,
                    postRescueWindow = inPostRescueWindow
                )
            } catch (t: Throwable) {
                aapsLogger.error(LTag.APS, "V5 shadow invocation failed", t)
                null
            }
            // Acceleration-based early-meal-detection SHADOW. Signal digging over all Boost
            // data found BG ACCELERATION (curvature) is the one signal worth adding: it detects an
            // unannounced meal ~5 min before the delta-based confirm (and improves the forecaster). accel =
            // shortAvgDelta − longAvgDelta (>0 = the rise is accelerating). Flags a would-be EARLIER
            // meal-confirm when acceleration + a rise are present but the state has not CONFIRMED yet.
            // READ-ONLY — logs accelMeal=; delivers NOTHING (shadow-first; a live earlier-confirm is a
            // dosing change → two-test bar). Banks the on-device lead + false-alarm to price it.
            runCatching {
                val accel = glucoseStatus.shortAvgDelta - glucoseStatus.longAvgDelta
                val rising = glucoseStatus.delta > 0.0 || glucoseStatus.shortAvgDelta > 0.0
                val preConfirm = v5decision?.mealHypothesis == null ||
                    v5decision.mealHypothesis == MealHypothesis.IDLE ||
                    v5decision.mealHypothesis == MealHypothesis.OBSERVING
                val trig = if (accel > 2.0 && rising && preConfirm) 1 else 0
                it.reason.append("accelMeal=$trig,${Round.roundTo(accel, 0.1)},${Round.roundTo(glucoseStatus.shortAvgDelta, 0.1)}," +
                    "${Round.roundTo(glucoseStatus.longAvgDelta, 0.1)},${glucoseStatus.glucose.toInt()},${v5decision?.mealHypothesis ?: "?"}; ")
            }.onFailure { t -> aapsLogger.error(LTag.APS, "Accel-meal shadow failed (swallowed — dosing untouched)", t) }
            // Site and reservoir age. READ-ONLY telemetry; failure swallowed.
            runCatching { it.reason.append(deviceAgeTag.tag(now)) }
                .onFailure { t -> aapsLogger.error(LTag.APS, "Device-age tag failed (swallowed, dosing untouched)", t) }
            // Sleep gate: do NOT let V5 drive the SMB while SLEEPING — fall back to V1's
            // (oref1/Boost) SMB, which already respects night mode. V5 still computes its shadow
            // telemetry above (runShadow ran), so the V5-vs-V1 comparison continues overnight; only
            // the dose override is suppressed. Robust to overnight CGM artifacts tripping V5.
            // Anti-stacking hard gate: the rolling-60-min cumulative SMB cap is enforced inside V1
            // (DetermineBasalBoost), but the V5 override below replaces it.units AFTER determine_basal
            // returns — so re-check the SAME cap here (same prior-volume semantics as V1) or V5 could
            // deliver on a cycle V1 suspended for cumulative volume. (Review, MEDIUM.)
            val cumulativeCapReached = cumulativeSmbCap60Min > 0.0 && recentSmbVolume60Min >= cumulativeSmbCap60Min
            // Set when the confirm tranche ran inside the override block this cycle; a hold left over
            // on any other cycle is dropped after the if-chain.
            var trancheRan = false
            // Boost-inactive gate: the V6/V5 override may replace the SMB ONLY when Boost
            // is active this cycle. When boostActive is false — night/sleep period, high temp target, or
            // the step-based sleep-in has fired — fall back to V1's base oref1 SMB (which respects night
            // mode + its own hypo/minGuard gates) instead of the amplified V5 dose. Without this, a
            // genuinely-asleep, zero-step cycle could still receive a full V5 meal-amplified bolus,
            // because v5Asleep reflects ONLY the HR sleep-state machine, never the boost-window gate.
            // V1 low-glucose suspend: V6 checks its own 30-min prediction
            // against the LGS setting, which can pass on a cycle V1 suspended on its 4-hour minGuardBG, its own
            // threshold or its eventualBG. The override is skipped there, so V1's zero temp and no SMB stand.
            val v1LowSuspend = it.reason.contains(DetermineBasalBoost.LOW_SUSPEND_TAG)
            if (microBolusAllowed && v5decision != null && !v5Asleep && !cumulativeCapReached && !v1LowSuspend && activityResult.boostActive) {
                val v1WouldDose = it.units ?: 0.0
                // Non-meal-state cap: V6 may only OUT-dose V1 when it holds a meal
                // hypothesis (CONFIRMED/COMMITTED). In IDLE/OBSERVING/RECOVERING the V5 state caps
                // don't apply (applyStateDoseCap passes them through) and IDLE's 1.0× multiplier can
                // front a multi-unit correction that bypasses V1's per-SMB sizing — Capping at V1's would-dose makes IDLE match
                // its own spec ("standard oref dose; no meal hypothesis"); genuine meal rises still
                // get full V6 dosing via OBSERVING→CONFIRMED.
                // velocity-budget floor exemption: when the ACTIVE velocity-budget floor
                // lifted this cycle's dose (velocityBudgetExempt), treat it as a meal state so the
                // floored hold can out-dose V1 on the budget≈0 high tail (V1 doses ~0 there). Safe by
                // construction: the exempt dose is committedCap + maxIOB bounded, the floor requires
                // !postRescueWindow, and the cumulative-60min / boost-active / sleep gates all still run.
                // V1-acceleration primer (bolus mode): the OBSERVING primer is already folded
                // into finalDose and floored/netted in-engine, so exempt it from the non-meal v1-cap
                // (it must out-dose V1's OBSERVING dose — that IS the reclaimed early insulin). The
                // primer's own floors (recentLow≥80, !postRescue, awake, !exercise, maxIOB) ran in-engine;
                // the cumulative-60min/sleep/boost-active seam guards above still apply.
                val inMealState = v5decision.mealHypothesis == MealHypothesis.CONFIRMED ||
                    v5decision.mealHypothesis == MealHypothesis.COMMITTED ||
                    v5decision.velocityBudgetExempt ||
                    (v5decision.primerBolusU > 0.0 && !v5decision.primerUseTempBasal)
                // Post-rescue meal-state cap: inside the post-rescue window the meal-state
                // exemption is suppressed and CONFIRMED/COMMITTED are ALSO capped at V1's would-dose —
                // which is hypo-restrained by V1's aligned tier guard (same value, same threshold; see
                // applyV6OverrideCaps KDoc for the evidence:
                // 27% of the removed insulin sits ahead of a second low <70 vs 14-19% for other levers;
                // cost 10% genuine post-hypo meals at 0.15U median under-delivery).
                val caps = applyV6OverrideCaps(inMealState, inPostRescueWindow, v5decision.finalDose, v1WouldDose, recentLowBG45Min)
                var overrideDose = caps.dose
                // confirm tranche. Split the confirm commitment: part now, the rest ten
                // minutes later only if the rise continues. The confirm shot is currently the same
                // size whether the excursion reaches 20 mg/dL or 100, and the trace separates those
                // two ends at 0.730 at the confirming cycle against 0.893 ten minutes on, a paired
                // gain of +0.162 [+0.066, +0.264].
                //
                // The release is added after every cap above, so (audit #1) it is held
                // to the same bounds as the rest of the dose: released only in COMMITTED (a CONFIRMED
                // cycle replaces the hold), never when a phase-3 hard gate fired or inside the
                // post-rescue window, and clamped to the maxIOB headroom and the CONFIRMED cap left after
                // this cycle's dose. A cycle on which this block does not run drops the hold (below the
                // if-chain), so a hold never outlives a cycle on which the engine decided against V6.
                if (preferences.getBoostDosing(BooleanKey.ApsBoostV5ConfirmTranche)) {
                    trancheRan = true
                    confirmTranche.immediateFraction = preferences.getBoostDosing(DoubleKey.ApsBoostV5TrancheFraction)
                    confirmTranche.releaseThreshold = preferences.getBoostDosing(DoubleKey.ApsBoostV5TrancheThreshold)
                    val before = overrideDose
                    var gateNote = ""
                    overrideDose = if (v5decision.mealHypothesis == MealHypothesis.CONFIRMED) {
                        confirmTranche.onConfirm(now, glucoseStatus.glucose, before)
                    } else {
                        val release = confirmTranche.onCycleBounded(
                            now, glucoseStatus.glucose,
                            app.aaps.plugins.aps.openAPSBoostV5.ConfirmTrancheController.ReleaseBounds(
                                inMealState = v5decision.mealHypothesis == MealHypothesis.COMMITTED,
                                hardGateFired = v5decision.phase3.reductions.hardGateFired != null,
                                postRescueWindow = inPostRescueWindow,
                                ceilingU = trancheReleaseCeiling(
                                    cycleDoseU = before,
                                    maxIobU = minOf(oapsProfile.boost_maxIOB, oapsProfile.max_iob),
                                    iobU = iobArray.firstOrNull()?.iob ?: 0.0,
                                    confirmedCapU = preferences.getBoostDosing(DoubleKey.ApsBoostV5ConfirmedCapU)
                                )
                            )
                        )
                        gateNote = release.note
                        before + release.units
                    }
                    it.reason.append("tranche=${Round.roundTo(before, 0.001)},"
                        + "${Round.roundTo(overrideDose, 0.001)},"
                        + "${Round.roundTo(confirmTranche.heldU(), 0.001)},"
                        + "${confirmTranche.probeProbability(glucoseStatus.glucose)?.let { p -> Round.roundTo(p, 0.001) } ?: "-"},"
                        + "${v5decision.mealHypothesis}; ")
                    if (gateNote.isNotEmpty()) it.reason.append("trancheGate=$gateNote; ")
                }
                it.units = overrideDose
                it.reason.append("V6-ACTIVE drove SMB ${Round.roundTo(overrideDose, 0.001)}U (base would=${Round.roundTo(v1WouldDose, 0.001)}U, state=${v5decision.mealHypothesis}${caps.capNote}); ")
                aapsLogger.info(LTag.APS, "V6-ACTIVE override: SMB ${v1WouldDose} → ${overrideDose} state=${v5decision.mealHypothesis}${caps.capNote}")
                // composed brake-floor breadcrumb: when the Advanced toggle is ON,
                // decision.floorWouldAdd carries the uplift the floor actually APPLIED (active
                // semantics — see V5Decision KDoc). Log the un-floored→floored delivery so a
                // floored cycle is auditable straight from the reason line. Whenever the uplift
                // is > 0 the floored dose is inside every seam cap by construction (RECOVERING
                // v1-bound + !postRescueWindow are conditions of the floor itself), so
                // overrideDose == v5decision.finalDose here and X→Y is the delivered truth.
                val floorUplift = if (preferences.getBoostDosing(BooleanKey.ApsBoostV5ComposedFloorActive)) v5decision.floorWouldAdd ?: 0.0 else 0.0
                if (floorUplift > 0.0) {
                    it.reason.append("brake-floor applied: ${Round.roundTo(v5decision.finalDose - floorUplift, 0.001)}→${Round.roundTo(v5decision.finalDose, 0.001)} U; ")
                    aapsLogger.info(LTag.APS, "V6 brake-floor applied: ${Round.roundTo(v5decision.finalDose - floorUplift, 0.001)}→${Round.roundTo(v5decision.finalDose, 0.001)} U")
                }
                // velocity-budget floor breadcrumb: when ON, decision.velocityBudgetWouldAdd
                // carries the uplift actually APPLIED (out-dosing V1 on the budget≈0 tail via the
                // non-meal-cap exemption). overrideDose == v5decision.finalDose here (the exemption made
                // inMealState true), so X→Y is the delivered truth.
                val vbUplift = if (preferences.getBoostDosing(BooleanKey.ApsBoostV5VelocityBudgetActive)) v5decision.velocityBudgetWouldAdd ?: 0.0 else 0.0
                if (vbUplift > 0.0) {
                    it.reason.append("velocity-budget floor applied: ${Round.roundTo(v5decision.finalDose - vbUplift, 0.001)}→${Round.roundTo(v5decision.finalDose, 0.001)} U (base insulinReq≈0); ")
                    aapsLogger.info(LTag.APS, "V6 velocity-budget floor applied: ${Round.roundTo(v5decision.finalDose - vbUplift, 0.001)}→${Round.roundTo(v5decision.finalDose, 0.001)} U")
                }
                // ===== V1-acceleration early primer delivery + breadcrumb =====
                if (v5decision.primerBolusU > 0.0) {
                    if (v5decision.primerUseTempBasal) {
                        // RETRACTABLE temp-basal fallback (hypo-prone routing): deliver ~primerBolusU over
                        // a short window as a raise ABOVE scheduled basal — additive-only. NEVER fires when
                        // the base engine is suspending/reducing (it.rate < current_basal): a protective
                        // low/zero temp always wins. Never lowers what the base engine already planned
                        // (max), and never shortens its duration. Expires (retracts) if the meal fades.
                        val primerTbrDurationMin = 30
                        val curBasal = oapsProfile.current_basal
                        // (audit #10): when V1 says "no temp required" it returns no rate and
                        // leaves the running temp in place, which can be a protective zero or low temp.
                        // The decision reads that running temp, not only the returned rate.
                        val baseRate = effectiveBaseTempRate(it.rate, currentTemp.rate, currentTemp.duration)
                        val extraRate = v5decision.primerBolusU * (60.0 / primerTbrDurationMin)
                        val primerRate = curBasal + extraRate
                        when (primerTbrAction(baseRate, curBasal, primerRate)) {
                            // Base engine suspending/reducing — a protective low/zero temp always wins.
                            PrimerTbrAction.SKIP_PROTECTIVE ->
                                it.reason.append("primer=tbr-skipped(base-temp ${Round.roundTo(baseRate ?: 0.0, 0.001)}<basal ${Round.roundTo(curBasal, 0.001)}${if (it.rate == null) ",running" else ""}); ")
                            // Base engine already delivering ≥ the primer rate — primer adds nothing; do
                            // NOT touch its rate/duration (extending a high base temp would over-deliver).
                            PrimerTbrAction.SUBSUMED ->
                                it.reason.append("primer=tbr-subsumed(base ${Round.roundTo(baseRate ?: 0.0, 0.001)}≥primer ${Round.roundTo(primerRate, 0.001)}U/h${if (it.rate == null) ",running" else ""}); ")
                            // Primer genuinely raises above the base plan → apply the retractable temp.
                            PrimerTbrAction.APPLY -> {
                                it.rate = primerRate
                                it.duration = kotlin.math.max(it.duration ?: 0, primerTbrDurationMin)
                                it.reason.append("primer=tbr,${Round.roundTo(v5decision.primerBolusU, 0.001)}U→${Round.roundTo(primerRate, 0.001)}U/h×${it.duration}m; ")
                                aapsLogger.info(LTag.APS, "V6 primer (temp-basal): ${v5decision.primerBolusU}U → ${primerRate}U/h x ${it.duration}min")
                            }
                        }
                    } else {
                        // Bolus mode: already folded into the SMB above (finalDose) + exempted from the
                        // non-meal cap. Just log the reclaimed early insulin.
                        // if auto-config RECOMMENDED the retractable temp-basal route and the
                        // user has overridden it to a bolus, say so in the reason. The override is always
                        // honoured — auto-config never makes a setting unreachable — but an overridden
                        // routing must be VISIBLE in the data, because it is the difference between a
                        // primer the loop can unwind and one it cannot.
                        val routeOverridden = preferences.getBoostDosing(BooleanKey.ApsBoostV5PrimerTbrFallback) &&
                            preferences.getBoostDosing(BooleanKey.ApsBoostV5PrimerBolusMode)
                        it.reason.append("primer=bolus,${Round.roundTo(v5decision.primerBolusU, 0.001)}U" +
                            (if (routeOverridden) ";primerRoute=bolus-USER-OVERRIDE(recommended=tbr)" else "") + "; ")
                        aapsLogger.info(LTag.APS, "V6 primer (bolus): ${v5decision.primerBolusU}U folded into SMB" +
                            if (routeOverridden) " [user override of recommended temp-basal routing]" else "")
                    }
                }
                // primer sizing telemetry. Emitted whenever the primer GATE opened — including
                // when the state factors sized it to nothing and it rounded to 0U (primerBolusU == 0, so
                // the block above is skipped). Without this the shadow cannot tell "gate never opened"
                // from "gate opened, correctly sized to zero", which is the whole point of the rework.
                if (v5decision.primerScaleDebug.isNotEmpty()) {
                    it.reason.append("primerScale=${v5decision.primerScaleDebug}" +
                        (if (v5decision.primerBolusU <= 0.0) ",ROUNDED_TO_ZERO" else "") + "; ")
                }
            } else if (v5decision != null && cumulativeCapReached) {
                it.units = 0.0
                it.reason.append("V6 suppressed (cumulative SMB cap ${Round.roundTo(recentSmbVolume60Min, 0.01)}U/${Round.roundTo(cumulativeSmbCap60Min, 0.01)}U reached); ")
                aapsLogger.info(LTag.APS, "V6-ACTIVE cumulative SMB cap reached (${recentSmbVolume60Min}/${cumulativeSmbCap60Min}U) — SMB suspended")
            } else if (v5Asleep && v5decision != null) {
                it.reason.append("V6 suppressed (SLEEPING) — base SMB ${Round.roundTo(it.units ?: 0.0, 0.001)}U; ")
            } else if (v5decision != null && !activityResult.boostActive) {
                it.reason.append("V6 override skipped (Boost inactive) — base SMB ${Round.roundTo(it.units ?: 0.0, 0.001)}U; ")
                aapsLogger.info(LTag.APS, "V6-ACTIVE override skipped — Boost inactive; base oref1 SMB ${it.units ?: 0.0}U retained")
            } else if (v5decision == null) {
                // V6 threw (runShadow returned null). Its fallback was V1's dose from whichever tier fired;
                // cap it at base oref so a V6 failure cannot hand the pump a Boost tier dose.
                val orefSmb = baseOrefSmb(
                    insulinReq = it.insulinReq ?: 0.0,
                    iob = iobArray.firstOrNull()?.iob ?: 0.0,
                    currentBasal = oapsProfile.current_basal,
                    maxUamSmbBasalMinutes = oapsProfile.maxUAMSMBBasalMinutes,
                    maxSmbBasalMinutes = oapsProfile.maxSMBBasalMinutes,
                    boostInsulinReqPct = oapsProfile.Boost_InsulinReq,
                    bolusIncrement = oapsProfile.bolus_increment
                )
                val capped = v6UnavailableSmb(it.units, orefSmb)
                if (capped != it.units) {
                    it.reason.append("V6 unavailable: base SMB capped from ${Round.roundTo(it.units ?: 0.0, 0.001)}U to oref ${Round.roundTo(orefSmb, 0.001)}U; ")
                    aapsLogger.warn(LTag.APS, "V6 unavailable: V1 SMB ${it.units}U capped to base oref ${orefSmb}U")
                    it.units = capped
                }
            }
            // A tranche hold survives only on cycles where V6 drove the SMB with the tranche on. Any
            // other cycle (V6 skipped for sleep, the cumulative cap, a closed gate, no SMB allowed, an
            // error, or the tranche switched off) is the engine deciding against V6's dose, so the hold
            // goes with it rather than being released later on stale evidence. (audit #1)
            if (!trancheRan && confirmTranche.heldU() > 0.0) {
                it.reason.append("trancheGate=dropped:seam-skipped,${Round.roundTo(confirmTranche.heldU(), 0.001)}; ")
                confirmTranche.reset()
            }

            // V6: surface the anticipatory pre-meal target decision computed earlier this cycle.
            v6PreMealReason?.let { r -> it.reason.append(r) }
            // V6 meal-time learner: record a FRESH CONFIRMED commit (the event V5 treats as a meal)
            // so the pre-meal window learns this user's habitual meal times. Persist only on change.
            // Since only sessions that start outside the night window with the detector AWAKE.
            if (v5decision != null && v5decision.mealSessionStarted &&
                !mealSessionRecordable(activityResult.inNightWindow, sleepStateCached.state)
            ) {
                aapsLogger.debug(LTag.APS, "V6 meal-time learner: session not recorded (night window=${activityResult.inNightWindow}, sleep=${sleepStateCached.state})")
            } else if (v5decision != null && v5decision.mealSessionStarted) {
                mealTimeHistoryCached = MealTimeLearner.record(mealTimeHistoryCached, now)
                preferences.put(StringKey.ApsBoostMealTimeHistory, mealTimeHistoryCached.serialize())
                aapsLogger.debug(LTag.APS, "V6 meal-time learner: recorded meal commit @ ${dateUtil.dateAndTimeString(now)} (${mealTimeHistoryCached.events.size} events)")
            }

            // Step-feed availability telemetry (F1) — written EVERY cycle so a dark
            // feed is visible in NS ("none" = INACTIVE + sleep-in suppressed this cycle).
            it.boostSteps_feed = activityResult.stepsFeed
            // F6: step-feed edge detection — reason-line only (steps degrade
            // gracefully via F1's guard, so no notification; the transition just needs to be
            // findable in NS next to the cycles it affected).
            val prevStepsFeed = lastStepsFeed
            if (prevStepsFeed != null && prevStepsFeed != activityResult.stepsFeed) {
                it.reason.append("stepsFeed: $prevStepsFeed→${activityResult.stepsFeed}; ")
                aapsLogger.info(LTag.APS, "Boost step feed changed: $prevStepsFeed → ${activityResult.stepsFeed}")
            }
            lastStepsFeed = activityResult.stepsFeed

            // Sleep telemetry for NS. The state itself was evaluated at the start of the cycle by
            // [evaluateSleep]; this cycle's mlMealLikely is kept for the next evaluation, where it
            // gates sleep entry only.
            lastMlMealLikely = it.mlMealLikely
            try {
                val ev = sleepEvalCached ?: throw IllegalStateException("sleep evaluation did not run this cycle")
                val sleepResult = ev.result
                val agg = ev.agg
                val sleepInputs = ev.inputs
                val hrReadingsForSleep = ev.hrReadings

                // ── NS upload: HR + sleep + learned-schedule telemetry ─────────
                it.sleepState = sleepResult.newState.state.name
                it.sleepStateEnteredAtMs = sleepResult.newState.enteredAtMs.takeIf { v -> v > 0 }
                it.sleepEntryReason = sleepResult.newState.sleepEntryReason
                it.sleepLearnedStartMin = agg.sleepStartMinAvg
                it.sleepLearnedWakeMin = agg.wakeMinAvg
                it.sleepLearnedDurationMin = agg.sleepDurationMinAvg
                it.sleepLearnedSessionCount = agg.sessionCount
                it.hrLearnedRestingBpm = agg.restingHrBpm
                it.hrLearnedDaytimeBpm = agg.daytimeHrBpm
                // Update cache for next cycle's HrActivityCalculator.classify call
                hrLearnedDaytimeBpmCached = agg.daytimeHrBpm
                val latestHr = hrReadingsForSleep.maxByOrNull { hr -> hr.timestamp }
                if (latestHr != null && latestHr.isValid) {
                    it.hrBpmLatest = Round.roundTo(latestHr.beatsPerMinute, 0.1)
                }
                val avg5 = SleepStateDetector.averageHr(hrReadingsForSleep, now, 5)
                val avg15 = SleepStateDetector.averageHr(hrReadingsForSleep, now, 15)
                if (avg5 != null) it.hrBpmAvg5m = Round.roundTo(avg5, 0.1)
                if (avg15 != null) it.hrBpmAvg15m = Round.roundTo(avg15, 0.1)
                // F5 transient visibility: 15-min averaging blunts hypo-tachycardia
                // (+1.5 vs +13.6 bpm, analysis) — emit the 5-min extremes of the 1-min
                // rows so the transient survives into NS for retrospective modelling.
                val hrRows5m = hrReadingsForSleep.filter { hr -> hr.isValid && hr.timestamp > now - 5 * 60_000L }
                if (hrRows5m.isNotEmpty()) {
                    it.hrBpmMax5m = Round.roundTo(hrRows5m.maxOf { hr -> hr.beatsPerMinute }, 0.1)
                    it.hrBpmMin5m = Round.roundTo(hrRows5m.minOf { hr -> hr.beatsPerMinute }, 0.1)
                }
                it.hrReadingsCount15m = hrReadingsForSleep.count { hr ->
                    hr.isValid && hr.timestamp > now - 15 * 60_000L
                }
                // HR source visibility: which device feeds HR + silent-death detection.
                // Consumers stay source-agnostic; this is telemetry only.
                val hrRes = HrSourceResolver.resolve(
                    hrReadingsForSleep.filter { it.isValid }.map { HrSourceResolver.Reading(it.device, it.timestamp) }, now
                )
                it.hrSource_resolved = hrRes.active
                it.hrSource_states = hrRes.note
                // F4 phone side: edge-detect the whole feed going dark (anyFresh
                // true→false) — one reason-line note naming the last device + age — and after 60
                // consecutive dark minutes during 08:00-22:00 local, one low-priority notification
                // per dark episode (the wear watchdog re-registers the sensor; this covers every
                // other silent-death mode: BT drop, Garmin app dead, watch off wrist).
                val darkEvent = hrFeedDarkTracker.onCycle(hrRes, now, java.time.LocalTime.now().hour)
                darkEvent.wentDarkNote?.let { note ->
                    aapsLogger.warn(LTag.APS, note)
                    it.reason.append("$note; ")
                }
                if (darkEvent.raiseNotification) {
                    notificationManager.post(
                        NotificationId.BOOST_MESSAGE,
                        "Boost: heart-rate feed has been dark for ${darkEvent.darkMinutes} min — check the watch/Garmin connection",
                        level = NotificationLevel.LOW
                    )
                }

                // auto-config breadcrumb, replayed EVERY cycle so it reaches Nightscout and
                // boost_decisions.reason_text. Written by the V5 onboarding path (see
                // StringKey.ApsBoostV5AutoConfigSummary); read-only here. Empty = the onboarding path has
                // not reached a decision yet on this install. Display-only, never consulted for dosing.
                // periodic re-derivation breadcrumb (autordv=), same contract: replayed EVERY
                // cycle so Nightscout and boost_decisions always show the CURRENT auto-config state, not
                // just the one cycle in seven when the re-derivation actually ran. Written by
                // OpenAPSBoostV5Plugin.maybeRedrive. install-time history-gap breadcrumb
                // (histsync=), same contract (see BoostHistorySync); empty in the common case.
                // Read unmasked: the keys are defaultedBySM, so a plain get() dropped all three in
                // Simple Mode.
                it.reason.append(AutoConfigLedger.reasonBreadcrumbs(preferences))
                it.reason.append("sleep=${sleepResult.newState.state}")
                if (agg.sleepStartMinAvg != null) {
                    it.reason.append(" learned=${formatClockMin(agg.sleepStartMinAvg!!)}→${formatClockMin(agg.wakeMinAvg ?: 0)}/${agg.sessionCount}d")
                }
                it.reason.append("; ")
                // the detector's working each cycle, so a late or missed wake can be read
                // from NS: step growth over the last hour on the watch and phone series, the 15-min
                // steps it saw, the HR-above-floor streak, minutes into a wake candidacy, lie-in.
                it.reason.append(sleepDbgTag(sleepResult, sleepInputs))
            } catch (t: Throwable) {
                aapsLogger.error(LTag.APS, "Sleep state evaluation failed", t)
            }

            // ── Activity-load SHADOW: learn the personal step baseline + compute what
            // an activity/inactivity ISF modifier WOULD do, and LOG it. Never applied to dosing. ──
            if (preferences.getBoostDosing(BooleanKey.ApsBoostActivityShadowEnabled)) {   // raw read (Simple-Mode mask bypass)
                try {
                    val offsetMs = java.time.ZoneId.systemDefault().rules.getOffset(java.time.Instant.now()).totalSeconds * 1000L
                    val todayIdx = DailyStepHistoryTracker.dayIndex(now, offsetMs)
                    val dayStartMs = todayIdx * 86_400_000L - offsetMs
                    var multi = multiStepHistoryCached

                    // ── Assemble per-source completed-day history from whatever the user has ──
                    // Health Connect: EVERY dataOrigin (Garmin, Google Fit, …) — instant backfill, and
                    // one source dying no longer starves the baseline (the failure).
                    for ((rawSrc, totals) in healthConnectStepsIngest.allSourceDailyTotals)
                        multi = DailyStepHistoryTracker.mergeSource(multi, rawSrc, totals, todayIdx)
                    // Backfill the PHONE anchor from HC's phone-sensor (device.type==PHONE) history so the
                    // phone-anchored baseline is full-window at once, not accrued one day per midnight.
                    val hcPhone = healthConnectStepsIngest.phoneDailyTotals
                    if (hcPhone.isNotEmpty()) multi = DailyStepHistoryTracker.mergeSource(multi, StepSourceResolver.PHONE, hcPhone, todayIdx)
                    // Wear: derive completed-day totals from the SC table (throttled — daily totals are
                    // stable within an hour, and a 28-day SC read is heavy at the 5-min cycle cadence).
                    if (now - lastWearDailyMs >= 60 * 60_000L) {
                        lastWearDailyMs = now
                        val windowStartMs = (todayIdx - DailyStepHistoryTracker.WINDOW_DAYS) * 86_400_000L - offsetMs
                        val wearHist = try { persistenceLayer.getStepsCountFromTimeToTime(windowStartMs, now) } catch (t: Throwable) { emptyList() }
                        val wearDaily = WearStepSource.dailyTotals(wearHist, todayIdx, offsetMs)
                        if (wearDaily.isNotEmpty()) multi = DailyStepHistoryTracker.mergeSource(multi, StepSourceResolver.WEAR, wearDaily, todayIdx)
                    }

                    // ── Today-so-far per candidate source ──
                    // Reconcile the phone counter with HC's today total (hold the HIGHER, never anchor
                    // down), only when HC's value is for the CURRENT local day (no cross-midnight bleed).
                    if (healthConnectStepsIngest.todayStepsDay == todayIdx)
                        StepService.seedTodayFromHc(healthConnectStepsIngest.todayStepsSoFar, offsetMs)
                    val todaySc = try { persistenceLayer.getStepsCountFromTimeToTime(dayStartMs, now) } catch (t: Throwable) { emptyList() }
                    val wearToday = WearStepSource.stepsToday(todaySc, dayStartMs, now)
                    val phoneToday = StepService.getStepsToday(offsetMs)

                    // ── Intraday running-max BANK — day-close never reads live counts.
                    // Despite hold-higher: the wear counter can reset at DEVICE midnight, and the day
                    // then closes at the phone count: wear's candidate was already 0 at the moment of resolution. Bank
                    // per-source maxima every cycle (wear/phone native units; HC sources only when
                    // HC's today value is for the CURRENT local day); on rollover the completed
                    // day is merged from the BANK. Persisted so an app restart keeps the peak.
                    // Replaces the old phone-only in-memory ledger (phoneDayCached/phoneMaxCached).
                    val bankCounts = LinkedHashMap<String, Int>()
                    bankCounts[StepSourceResolver.WEAR] = wearToday
                    bankCounts[StepSourceResolver.PHONE] = phoneToday
                    if (healthConnectStepsIngest.todayStepsDay == todayIdx)
                        for ((rawSrc, t) in healthConnectStepsIngest.todayStepsBySource) {
                            val c = StepSourceResolver.canonical(rawSrc)
                            if (c == StepSourceResolver.WEAR || c == StepSourceResolver.PHONE) continue
                            bankCounts[c] = maxOf(bankCounts[c] ?: 0, t)
                        }
                    val banked = DailyStepHistoryTracker.bankCycle(stepBankCached, todayIdx, bankCounts)
                    stepBankCached = banked.bank
                    val bankSerialized = banked.bank.serialize()
                    if (bankSerialized != lastStepBankSerialized) {
                        preferences.put(StringKey.ApsBoostIntradayStepBank, bankSerialized)
                        lastStepBankSerialized = bankSerialized
                    }
                    if (banked.closedDayTotals.isNotEmpty()) {
                        for (t in banked.closedDayTotals)
                            multi = DailyStepHistoryTracker.mergeSource(multi, t.source, listOf(t), todayIdx)
                        // Breadcrumb AT the rollover cycle (the 07-06 close was silent): what each
                        // source banked. The held-X-over-Y resolution line follows from
                        // phoneAnchoredWindow below on this same cycle, now with banked values.
                        it.reason.append("stepHistory: day-close banked ${banked.closedDayTotals.joinToString(", ") { t -> "${t.source} ${t.steps}" }}; ")
                        aapsLogger.info(LTag.APS, "Boost step day-close (banked): ${banked.closedDayTotals.joinToString(", ") { t -> "${t.source} ${t.steps}" }}")
                    }

                    if (multi.sources != multiStepHistoryCached.sources) {
                        multiStepHistoryCached = multi
                        preferences.put(StringKey.ApsBoostDailyStepHistory, multi.serialize())
                    }

                    // ── Auto-resolve today's active source (no UI) ──
                    // Wear "fresh" uses the RESOLUTION grace window: strict 12-min
                    // freshness flapped today's count wear↔phone every 2-3 cycles overnight (the
                    // resolver prefers the highest-trust FRESH source, so each brief wear-quiet
                    // spell handed the count to the live-but-tiny phone: 349↔32 in the 07-06/07
                    // telemetry). The wear today-count is rebuilt from the day's SC rows, so it
                    // stays valid across a short quiet spell. Phone "fresh" = the pedometer has
                    // actually reported this boot (F9) — `phoneToday > 0` conflated
                    // "no data" with "no steps yet today" (false at midnight, false after reboot
                    // until the user moves, and never true on a phone whose sensor is dead).
                    val states = mutableListOf<StepSourceResolver.SourceState>()
                    states += StepSourceResolver.SourceState(StepSourceResolver.WEAR, WearStepSource.isRecentlyFresh(todaySc, now), multi.sources[StepSourceResolver.WEAR]?.days?.size ?: 0, wearToday)
                    states += StepSourceResolver.SourceState(StepSourceResolver.PHONE, StepService.feedState() == StepService.FeedState.LIVE, multi.sources[StepSourceResolver.PHONE]?.days?.size ?: 0, phoneToday)
                    for ((rawSrc, t) in healthConnectStepsIngest.todayStepsBySource) {
                        val c = StepSourceResolver.canonical(rawSrc)
                        if (c == StepSourceResolver.WEAR || c == StepSourceResolver.PHONE) continue
                        states += StepSourceResolver.SourceState(c, false, multi.sources[c]?.days?.size ?: 0, t)   // HC is hourly/laggy → not "fresh"
                    }
                    val res = StepSourceResolver.resolve(states)

                    // ── Baseline from the PHONE-ANCHORED rolling window. The phone is the one source
                    //    that runs continuously across watch SWAPS (one watch ceases as the next
                    //    starts → they never overlap), so it is the calibration frame; worn sources
                    //    are scaled into phone units. (Replaces watch-to-watch bridging, which could
                    //    never calibrate a swap.) ──
                    val bridged = DailyStepHistoryTracker.phoneAnchoredWindow(multi, todayIdx)
                    val sf = DailyStepHistoryTracker.shadowFactors(bridged.history, todayIdx)
                    it.boostActivityLoad_baselineSteps = sf.baselineSteps
                    it.boostActivityLoad_lastDaySteps = sf.lastDaySteps
                    it.boostActivityLoad_ratio = sf.ratio?.let { r -> Round.roundTo(r, 0.01) }
                    it.boostActivityLoad_wouldDeltaIsfPct = Round.roundTo(sf.wouldDeltaIsfPct, 0.1)
                    it.boostActivityLoad_source = res.active
                    it.boostActivitySource_resolved = res.active
                    it.boostActivitySource_states = res.note
                    it.boostActivitySource_bridge = if (bridged.donorsUsed.isEmpty()) "phone"
                        else "phone<-" + bridged.donorsUsed.joinToString("+") + if (bridged.calibrated) "" else "(raw)"
                    // Hold-higher reconcile breadcrumb: make the yesterday-total
                    // resolution visible in NS — the 07-02 undercount (wear 6224 recorded as 2227)
                    // was invisible because nothing logged which source's count won the day.
                    bridged.heldNote?.let { hn -> it.reason.append("stepHistory: $hn; ") }
                    if (sf.baselineSteps != null && sf.ratio != null) {
                        val sign = if (sf.wouldDeltaIsfPct >= 0) "+" else ""
                        it.reason.append("activityLoad: ${res.active ?: "none"} base ${sf.baselineSteps} last ${sf.lastDaySteps} (${Round.roundTo(sf.ratio!!, 0.01)}x) wouldΔISF $sign${Round.roundTo(sf.wouldDeltaIsfPct, 0.1)}% [${sf.note}] bridge[${it.boostActivitySource_bridge}]; ")
                    } else {
                        it.reason.append("activityLoad: no baseline (src[${res.note}]); ")
                    }

                    // ── Intraday "running hot?" — today's count converted to PHONE units so it matches
                    //    the phone-anchored baseline (worn-source today × phone/worn calibration). ──
                    val intraHour = java.time.LocalTime.now().hour
                    val stepsTodayPhone = DailyStepHistoryTracker.toPhoneUnits(res.stepsToday, res.active, multi)
                    val intra = DailyStepHistoryTracker.intradayFactor(stepsTodayPhone, sf.baselineSteps, intraHour)
                    it.boostActivityLoad_stepsToday = res.stepsToday
                    it.boostActivityLoad_stepsSource = res.active
                    it.boostActivityLoad_intradayRatio = intra.ratio?.let { r -> Round.roundTo(r, 0.01) }
                    it.boostActivityLoad_intradayDeltaIsfPct = Round.roundTo(intra.wouldDeltaIsfPct, 0.1)
                    if (intra.ratio != null) {
                        it.reason.append("activityIntraday: today ${res.stepsToday} vs exp ${intra.expectedByNow} (${Round.roundTo(intra.ratio!!, 0.01)}x) wouldΔISF +${Round.roundTo(intra.wouldDeltaIsfPct, 0.1)}%; ")
                    }
                } catch (t: Throwable) {
                    aapsLogger.error(LTag.APS, "Activity-load shadow failed", t)
                }
            }

            // ── Autosens / TDD-DynISF coordination telemetry. Always logs which
            // mechanism drives basal + the would-be alternative, so ApsBoostAutosensWhenNoTdd can be
            // validated on real data before being enabled. SHADOW only when the toggle is OFF — the
            // applied ratio (boostAutosens_appliedRatio) is whatever determine_basal actually used. ──
            it.boostVersion = BoostBuildInfo.BOOST_VERSION
            it.boostAutosens_mode = if (useTdd) "tdd" else if (autosensWhenNoTdd) "autosens" else "curve"
            it.boostAutosens_orefRatio = Round.roundTo(orefAutosensRatio, 0.001)
            it.boostAutosens_curveRatio = Round.roundTo(isfResult.ratio, 0.001)
            it.boostAutosens_appliedRatio = Round.roundTo(autosensResult.ratio, 0.001)
            if (!useTdd) {
                it.reason.append("autosensCoord[${it.boostAutosens_mode}]: oref=${Round.roundTo(orefAutosensRatio, 0.01)} curve=${Round.roundTo(isfResult.ratio, 0.01)} applied=${Round.roundTo(autosensResult.ratio, 0.01)}; ")
            }

            val determineBasalResult = apsResultProvider().with(it)
            determineBasalResult.inputConstraints = inputConstraints
            determineBasalResult.autosensResult = autosensResult
            determineBasalResult.iobData = iobArray
            determineBasalResult.glucoseStatus = glucoseStatus
            determineBasalResult.currentTemp = currentTemp
            determineBasalResult.oapsProfileBoost = oapsProfile
            determineBasalResult.mealData = mealData
            lastAPSResult = determineBasalResult
            lastAPSRun = now
            aapsLogger.debug(LTag.APS, "Result: $it")
            rxBus.send(EventAPSCalculationFinished())
        }

        rxBus.send(EventOpenAPSUpdateGui())
    }

    // ---- Glucose status ----

    override fun getGlucoseStatusData(allowOldData: Boolean) = glucoseStatusCalculatorSMB.getGlucoseStatusData(allowOldData)

    // ---- Constraints ----

    override fun isSuperBolusEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        value.set(false)
        return value
    }

    override suspend fun applyMaxIOBConstraints(maxIob: Constraint<Double>): Constraint<Double> {
        if (engineActive()) {
            val maxIobPref = preferences.get(DoubleKey.ApsSmbMaxIob)
            maxIob.setIfSmaller(maxIobPref, rh.gs(ApsStrings.limiting_iob, maxIobPref, rh.gs(ApsStrings.maxvalueinpreferences)), this)
            maxIob.setIfSmaller(hardLimits.maxIobSMB(), rh.gs(ApsStrings.limiting_iob, hardLimits.maxIobSMB(), rh.gs(ApsStrings.hardlimit)), this)
        }
        return maxIob
    }

    override fun applyBasalConstraints(absoluteRate: Constraint<Double>, profile: Profile): Constraint<Double> {
        if (engineActive()) {
            var maxBasal = preferences.get(DoubleKey.ApsMaxBasal)
            if (maxBasal < profile.getMaxDailyBasal()) {
                maxBasal = profile.getMaxDailyBasal()
                absoluteRate.addReason(rh.gs(ApsStrings.increasing_max_basal), this)
            }
            absoluteRate.setIfSmaller(maxBasal, rh.gs(CoreUiStrings.limitingbasalratio, maxBasal, rh.gs(ApsStrings.maxvalueinpreferences)), this)

            val maxBasalMultiplier = preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier)
            val maxFromBasalMultiplier = floor(maxBasalMultiplier * profile.getBasal() * 100) / 100
            absoluteRate.setIfSmaller(
                maxFromBasalMultiplier,
                rh.gs(CoreUiStrings.limitingbasalratio, maxFromBasalMultiplier, rh.gs(ApsStrings.max_basal_multiplier)),
                this
            )
            val maxBasalFromDaily = preferences.get(DoubleKey.ApsMaxDailyMultiplier)
            val maxFromDaily = floor(profile.getMaxDailyBasal() * maxBasalFromDaily * 100) / 100
            absoluteRate.setIfSmaller(maxFromDaily, rh.gs(CoreUiStrings.limitingbasalratio, maxFromDaily, rh.gs(ApsStrings.max_daily_basal_multiplier)), this)
        }
        return absoluteRate
    }

    override suspend fun isSMBModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = preferences.get(BooleanKey.ApsUseSmb)
        if (!enabled) value.set(false, rh.gs(ApsStrings.smb_disabled_in_preferences), this)
        else if (isNightModeActive()) value.set(false, rh.gs(R.string.boost_night_mode_smb_disabled), this)
        return value
    }

    // ---- Night Mode ----

    // @Volatile: read from the constraint path (isSMBModeEnabled) on non-loop threads;
    // without a happens-before edge a racing reader could pair a fresh timestamp with a stale result
    // and return "not night" on a night cycle (SMB let through). Benign worst case now: recompute.
    @Volatile private var lastNightModeRun: Long = 0
    @Volatile private var lastNightModeResult: Boolean = false

    // ---- Sleep state ----
    // Updated at end of invoke() once HR + steps + mlMealLikely are known. Read by
    // isNightModeActiveImpl() when ApsBoostNightModeAutoBySleep is enabled.
    @Volatile private var sleepStateCached: SleepStateDetector.State =
        SleepStateDetector.State.deserialize(preferences.getBoostDosing(StringKey.ApsBoostSleepState))
    @Volatile private var sleepHistoryCached: SleepHistoryTracker.History =
        SleepHistoryTracker.History.deserialize(preferences.getBoostDosing(StringKey.ApsBoostSleepHistory))
    /** One cycle's sleep evaluation, kept for the end-of-cycle telemetry. */
    private class SleepEval(
        val result: SleepStateDetector.Result,
        val agg: SleepHistoryTracker.Aggregate,
        val inputs: SleepStateDetector.Inputs,
        val hrReadings: List<HR>
    )

    @Volatile private var sleepEvalCached: SleepEval? = null
    // When the detector last confirmed a wake on steps or HR + steps. In memory only: after a restart
    // the lie-in failsafe applies again until steps clear it, the conservative direction.
    @Volatile private var lastGenuineWakeMs: Long = 0L
    private val GENUINE_STEP_WAKES = setOf("steps", "hr_steps")
    // mlMealLikely from the previous cycle: the sleep evaluation now runs before this cycle's is
    // computed. It gates sleep ENTRY only (< 0.30), so a five-minute-old value is enough.
    @Volatile private var lastMlMealLikely: Double? = null

    /**
     * Evaluate the sleep state for this cycle and update [sleepStateCached] and the sleep history.
     * Runs at the start of [runEngine] so the SMB constraint and the Boost gate act on this cycle's
     * state. Null if the evaluation failed; the previous state then stands.
     */
    private suspend fun evaluateSleep(now: Long): SleepEval? =
        try {
            val configuredNightStartMin = parseTimeToMinutesOfDay(preferences.getBoostDosing(StringKey.ApsBoostNightModeStart))
            val configuredNightEndMin = parseTimeToMinutesOfDay(preferences.getBoostDosing(StringKey.ApsBoostNightModeEnd))
            val nowLocal = java.time.LocalTime.now()
            val minOfDay = nowLocal.hour * 60 + nowLocal.minute

            // Auto-tune from learned 28-day history if ≥7 sessions exist; otherwise fall back
            // to configured nightStart / nightEnd.
            val localOffsetMs = java.time.ZoneId.systemDefault().rules.getOffset(java.time.Instant.now()).totalSeconds * 1000L
            val agg = SleepHistoryTracker.aggregate(sleepHistoryCached) { t -> java.time.ZoneId.systemDefault().rules.getOffset(java.time.Instant.ofEpochMilli(t)).totalSeconds * 1000L }
            // The learned window may only nudge the configured night window by ±BAND, and the
            // wake side trains on genuine wakes only (see SleepHistoryTracker). Together these
            // anchor the hard sleep/wake bounds to the user's configured times and cap how far
            // learning can move them — preventing the self-reinforcing earlier-every-night
            // collapse seen when the hard-exit fed its own learned wake. No/insufficient data
            // → effective == configured.
            val effectiveNightStartMin = clampToConfiguredBand(agg.sleepStartMinAvg, configuredNightStartMin, LEARNED_WINDOW_BAND_MIN)
            // wake side: one-sided — learning may only push the night-end LATER than configured, never
            // earlier (never lift protection before the user's set wake). See clampToConfiguredBand.
            val effectiveNightEndMin = clampToConfiguredBand(agg.wakeMinAvg, configuredNightEndMin, LEARNED_WINDOW_BAND_MIN, allowEarlier = false)
            // Learned resting HR (sleep p10 median, ≥7 sessions) overrides the configured static value
            // for sleep state evaluation; fallback is the existing user-set hrRestingBpm.
            val effectiveHrResting = agg.restingHrBpm ?: hrRestingBpm

            // 30-min window for HR — sufficient for both 5-min and 15-min avgs and sleep eval.
            val hrReadingsForSleep = persistenceLayer.getHeartRatesFromTime(now - 30 * 60_000L)
            // Lump-tolerant wake evidence: the detector needs today's CUMULATIVE
            // steps — wear-bridge steps arrive in batches the phone's 15-min bucket never sees (the
            // phone is not carried overnight), so wakes were missed and boundary-forced.
            val sleepTodayIdx = DailyStepHistoryTracker.dayIndex(now, localOffsetMs)
            val sleepDayStartMs = sleepTodayIdx * 86_400_000L - localOffsetMs
            val scTodayForSleep = try { persistenceLayer.getStepsCountFromTimeToTime(sleepDayStartMs, now) } catch (t: Throwable) { emptyList() }
            // Watch and phone day totals kept apart; the detector measures growth on each.
            val watchStepsToday = WearStepSource.stepsToday(scTodayForSleep, sleepDayStartMs, now)
            // Without the Health Connect top-up, which lands as one jump.
            val phoneStepsToday = StepService.getOwnStepsToday(localOffsetMs)
            val sleepInputs = SleepStateDetector.Inputs(
                nowMs = now,
                minuteOfDay = minOfDay,
                hrReadings = hrReadingsForSleep,
                hrWindowMinutes = 5,
                hrResting = effectiveHrResting,
                // Phone and freshest watch row combined, as the gate sees them. Phone-only missed every
                // watch step with the phone set down.
                stepsLast15Min = blendedSteps15Min(now),
                mlMealLikely = lastMlMealLikely,
                nightStartMin = effectiveNightStartMin,
                nightEndMin = effectiveNightEndMin,
                preSleepLeadMin = preferences.getBoostDosing(IntKey.ApsBoostPreSleepLeadMin),
                minSleepHysteresisMin = preferences.getBoostDosing(IntKey.ApsBoostSleepHysteresisMin),
                wakeHrHysteresisMin = preferences.getBoostDosing(IntKey.ApsBoostWakeHrHysteresisMin),
                stepsToday = watchStepsToday,
                // sleep-in merge (v7-shadow): fold the lie-in backstop into the state machine.
                sleepInStepsThreshold = sleepInSteps,
                sleepInWindowMin = (sleepInHours * 60.0).toInt(),
                cycleSpacingMinutes = iobCobCalculator.ads.detectedCadenceMinutes,
                stepsTodayPhone = phoneStepsToday
            )
            val sleepResult = SleepStateDetector.evaluate(
                prev = sleepStateCached,
                inputs = sleepInputs,
                aapsLogger = aapsLogger
            )
            val prevSleepState = sleepStateCached.state
            sleepStateCached = sleepResult.newState
            if (sleepResult.wakeReason == "boundary") lastSleepBoundaryExitMs = now
            if (sleepResult.wakeReason in GENUINE_STEP_WAKES) lastGenuineWakeMs = now
            if (sleepResult.transitioned) {
                preferences.put(StringKey.ApsBoostSleepState, sleepResult.newState.serialize())
                aapsLogger.debug(LTag.APS, "Sleep state transitioned → ${sleepResult.newState.state} (${sleepResult.debug})")

                // Update sleep history on the actual sleep/wake transitions only
                val newState = sleepResult.newState.state
                val historyChanged = when {
                    prevSleepState != SleepStateDetector.SleepState.SLEEPING &&
                        newState == SleepStateDetector.SleepState.SLEEPING -> {
                        sleepHistoryCached = SleepHistoryTracker.onSleepStart(sleepHistoryCached, now)
                        true
                    }
                    prevSleepState == SleepStateDetector.SleepState.SLEEPING &&
                        newState != SleepStateDetector.SleepState.SLEEPING -> {
                        // Fetch HR over the just-ended sleep period for restingHrP10
                        val sleepStartMs = sleepHistoryCached.openSleepStartMs ?: 0L
                        val sleepHrSamples = if (sleepStartMs > 0) {
                            persistenceLayer.getHeartRatesFromTimeToTime(sleepStartMs, now)
                                .filter { hr -> hr.isValid && hr.beatsPerMinute > 0 }
                                .map { hr -> hr.beatsPerMinute }
                        } else emptyList()
                        // Fetch HR over the awake period preceding this sleep for daytimeHrP10
                        val priorWakeMs = SleepHistoryTracker.lastWakeMs(sleepHistoryCached)
                        val daytimeHrSamples = if (priorWakeMs != null && sleepStartMs > priorWakeMs) {
                            persistenceLayer.getHeartRatesFromTimeToTime(priorWakeMs, sleepStartMs)
                                .filter { hr -> hr.isValid && hr.beatsPerMinute > 0 }
                                .map { hr -> hr.beatsPerMinute }
                        } else emptyList()
                        sleepHistoryCached = SleepHistoryTracker.onWake(
                            sleepHistoryCached, now,
                            sleepHrBpms = sleepHrSamples,
                            daytimeHrBpms = daytimeHrSamples,
                            wakeReason = sleepResult.wakeReason
                        )
                        true
                    }
                    else -> false
                }
                if (historyChanged) {
                    preferences.put(StringKey.ApsBoostSleepHistory, sleepHistoryCached.serialize())
                }
            }
            SleepEval(sleepResult, agg, sleepInputs, hrReadingsForSleep)
        } catch (t: Throwable) {
            aapsLogger.error(LTag.APS, "Sleep state evaluation failed", t)
            null
        }

    /** Phone 15-min steps with the freshest watch row's 15-min count, the larger taken, as the gate. */
    private suspend fun blendedSteps15Min(now: Long): Int {
        val phone = StepService.getRecentStepCount15Min()
        val watch = try {
            persistenceLayer.getStepsCountFromTimeToTime(now - 15 * 60_000L, now).maxByOrNull { sc -> sc.timestamp }?.steps15min ?: 0
        } catch (t: Throwable) { 0 }
        return maxOf(phone, watch)
    }

    /** `sleepDbg=lbWatch,lbPhone,steps15,hrStreak,wakeCandMin,lieIn; ` (wakeCandMin "-" when none). */
    private fun sleepDbgTag(r: SleepStateDetector.Result, inputs: SleepStateDetector.Inputs): String {
        val cand = r.newState.wakeCandidateSinceMs?.let { s -> ((inputs.nowMs - s) / 60_000L).toString() } ?: "-"
        return "sleepDbg=${r.stepsLbWatch},${r.stepsLbPhone},${inputs.stepsLast15Min},${r.newState.hrHighStreak},$cand,${if (r.inLieIn) 1 else 0}; "
    }
    // V6 meal-time learner — rolling 60-day history of V5-CONFIRMED meal commits. Loaded once at
    // construction; updated at end of invoke() when a fresh CONFIRMED fires, persisted on change.
    @Volatile private var mealTimeHistoryCached: MealTimeLearner.History =
        MealTimeLearner.History.deserialize(preferences.getBoostDosing(StringKey.ApsBoostMealTimeHistory))
    // Activity-load SHADOW — rolling 28-day PER-SOURCE daily-step history (multi-source abstraction
    // ; deserialize auto-migrates the old single-source blob). Persisted under the same key.
    @Volatile private var multiStepHistoryCached: DailyStepHistoryTracker.MultiSourceHistory =
        DailyStepHistoryTracker.MultiSourceHistory.deserialize(preferences.getBoostDosing(StringKey.ApsBoostDailyStepHistory))
    @Volatile private var lastWearDailyMs = 0L          // throttle the 28-day Wear SC read to hourly
    // Intraday step bank: per-source running max of today's counts, persisted so a
    // mid-evening app restart keeps the day's peak. Day-close resolves from THIS, never from
    // post-reset live reads (the 07-06 device-midnight wear reset undercount).
    @Volatile private var stepBankCached: DailyStepHistoryTracker.IntradayStepBank =
        DailyStepHistoryTracker.IntradayStepBank.deserialize(preferences.getBoostDosing(StringKey.ApsBoostIntradayStepBank))
    @Volatile private var lastStepBankSerialized: String? = null   // write-avoidance cache
    // Cached learned daytime baseline (used by HrActivityCalculator in current cycle from prior
    // cycle's aggregate computation — 5-min lag is acceptable, baseline changes slowly).
    @Volatile private var hrLearnedDaytimeBpmCached: Int? = null

    // Step-based sleep-in (lie-in) state from the most recent calculateBoostActivity().
    // Read by isNightModeActiveImpl() so a lie-in applies the user's night-mode SMB rules. The SMB
    // constraint is evaluated earlier in invoke() than calculateBoostActivity() runs, so this holds
    // the PREVIOUS cycle's value — a one-cycle (~5 min) lag that is safe: it errs toward keeping night
    // mode on one extra cycle when waking, and the boostActive override gate already blocks the
    // amplified V5 dose on the entering cycle regardless.
    @Volatile private var sleepInActiveCached: Boolean = false

    /**
     * Pre-BG-gate "user is in their night/sleep period" — the night time window OR HR/step sleep
     * detection, gated by the night-mode master toggle. This is the sleep-aware signal that gates the
     * Boost window (boostActive = !this). It deliberately EXCLUDES the BG / COB / low-TT gates that
     * [isNightModeActiveImpl] layers on for the SMB-suppression decision: those must NOT influence the
     * Boost window, or a nocturnal high would flip Boost back on and re-fire a V6 dose while asleep.
     *
     */
    private fun isInNightSleepPeriod(): Boolean {
        if (!preferences.getBoostDosing(BooleanKey.ApsBoostNightModeEnabled)) return false
        val now = System.currentTimeMillis()
        val midnight = now - MidnightUtils.milliSecFromMidnight(now)
        val start = midnight + parseTimeToMillisOrDefault(preferences.getBoostDosing(StringKey.ApsBoostNightModeStart), "22:00")
        val end = midnight + parseTimeToMillisOrDefault(preferences.getBoostDosing(StringKey.ApsBoostNightModeEnd), "07:00")
        // Equal-times guard: with start==end the wrap-branch union covers the whole
        // day → always-night → Boost/V6 silently never doses. Treat as an EMPTY time window
        // (mathematically a zero-length interval); HR/step sleep detection + the steps lie-in
        // backstop still protect the night.
        if (start == end) {
            aapsLogger.error(LTag.APS, "Night-mode start == end — treating time window as empty (sleep detection still active)")
            return preferences.getBoostDosing(BooleanKey.ApsBoostNightModeAutoBySleep) &&
                sleepStateCached.state != SleepStateDetector.SleepState.AWAKE
        }
        val active = NightWindow.contains(now, start, end)
        val autoBySleep = preferences.getBoostDosing(BooleanKey.ApsBoostNightModeAutoBySleep)
        val sleepActive = autoBySleep && sleepStateCached.state != SleepStateDetector.SleepState.AWAKE
        return active || sleepActive
    }

    private suspend fun isNightModeActive(): Boolean {
        val currentTimeMillis = System.currentTimeMillis()
        val timeAligned = currentTimeMillis - (currentTimeMillis % 1000)
        if (lastNightModeRun >= timeAligned) return lastNightModeResult
        lastNightModeResult = isNightModeActiveImpl()
        lastNightModeRun = timeAligned
        return lastNightModeResult
    }

    private suspend fun isNightModeActiveImpl(): Boolean {
        if (!preferences.getBoostDosing(BooleanKey.ApsBoostNightModeEnabled)) return false

        val bgCurrent = glucoseStatusCalculatorSMB.getGlucoseStatusData(true)?.glucose ?: return false

        // Night by time window OR HR/step sleep detection (isInNightSleepPeriod, which also honours
        // ApsBoostNightModeAutoBySleep — extending past nightEnd while still SLEEPING and enabling early
        // on PRE_SLEEP), OR the step-based lie-in surfaced from calculateBoostActivity so night-mode SMB
        // rules also apply during a morning lie-in. (sleepInActiveCached lags one cycle; see its
        // declaration.)
        if (!isInNightSleepPeriod() && !sleepInActiveCached) return false

        // Disable night mode when COB > 0
        if (preferences.getBoostDosing(BooleanKey.ApsBoostNightModeDisableWithCob)) {
            val mealData = iobCobCalculator.getMealDataWithWaitingForCalculationFinish()
            if (mealData.mealCOB > 0) return false
        }

        // Disable night mode with low temp target
        if (preferences.getBoostDosing(BooleanKey.ApsBoostNightModeDisableWithLowTt)) {
            val profile = profileFunction.getProfile() ?: return false
            val profileTarget = profile.getTargetMgdl()
            persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())?.let { tempTarget ->
                val targetBg = hardLimits.verifyHardLimits(
                    tempTarget.target(), CoreUiStrings.temp_target_value,
                    BOOST_LIMIT_TEMP_TARGET_BG
                )
                if (targetBg < profileTarget) return false
            }
        }

        // Check BG vs profile target + offset
        val profile = profileFunction.getProfile() ?: return false
        val profileTarget = profile.getTargetMgdl()
        val bgOffset = profileUtil.convertToMgdl(preferences.getBoostDosing(UnitDoubleKey.ApsBoostNightModeBgOffset, profileUtil), profileUtil.units)
        return bgCurrent < profileTarget + bgOffset
    }

    override fun isUAMEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = preferences.get(BooleanKey.ApsUseUam)
        if (!enabled) value.set(false, rh.gs(ApsStrings.uam_disabled_in_preferences), this)
        return value
    }

    override fun isAutosensModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = preferences.get(BooleanKey.ApsUseAutosens)
        if (!enabled) value.set(false, rh.gs(ApsStrings.autosens_disabled_in_preferences), this)
        return value
    }

    // 3.4's configuration() carried boostVersion into apsConfiguration on every devicestatus upload.
    // AAPS 4.0 removed per-plugin configuration() and the apsConfiguration sub-document, so the
    // version now rides on RT.boostVersion, which reaches Nightscout inside openaps.suggested.

    // ---- Preferences screen ----

    override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
        key = "openapsboost_settings",
        title = ApsStrings.openaps_boost,
        items = listOf<PreferenceItem>(
            // V1-only SMB sizing — these size V1's own SMB tiers, which "Boost V5" replaces, so they
            // are NOT shown on the V5 screen.
            PreferenceSubScreenDef(
                key = "boost_v1_smb_sizing",
                title = ApsStrings.boost_v1_smb_sizing_title,
                items = listOf(
                    DoubleKey.ApsBoostInsulinReqPct,
                    DoubleKey.ApsBoostBolus,
                    DoubleKey.ApsBoostScale
                    // Boost start/end time retired — the Boost window now tracks the night-mode
                    // period (see calculateBoostActivity / isInNightSleepPeriod). Keys kept for back-compat.
                )
            )
        ) + boostEngineCategories(),
        icon = pluginDescription.icon
    )

    /**
     * Shared Boost ENGINE preference categories — everything both "Boost" (V1) and the selectable
     * "Boost V5" need: basal/IOB, Boost controls, DynISF, activity/HR, post-exercise, night mode,
     * SMB safety, advanced. V5's [OpenAPSBoostV5Plugin] includes these too, then adds its own V5/V6
     * knobs — so engine settings live in ONE place and stay reachable under whichever Boost plugin
     * is the active APS. Contains NO V5/V6 controls (those belong to the V5 plugin).
     */
    fun boostEngineCategories(includeEngineEssentials: Boolean = true): List<PreferenceItem> = buildList {
        // ── 1. Default AAPS Settings ────────────────────────────────
        add(
            PreferenceSubScreenDef(
                key = "boost_default_aaps_settings",
                title = ApsStrings.boost_settings_default_aaps,
                items = buildList {
                    if (includeEngineEssentials) add(DoubleKey.ApsMaxBasal)
                    add(DoubleKey.ApsSmbMaxIob)
                    // Sizes the oref tiers, which also bound V6 outside a meal.
                    add(DoubleKey.ApsBoostInsulinReqPct)
                    add(DoubleKey.ApsBoostScale)
                    add(BooleanKey.ApsUseAutosens)
                    add(BooleanKey.ApsAutoIsfHighTtRaisesSens)
                    add(BooleanKey.ApsAutoIsfLowTtLowersSens)
                }
            )
        )

        // ── 2. Shared Boost engine controls (live under V5 too) ─────
        // maxIOB clamps V5's dose; circadian ISF + allow-with-high-TT shape sens/insulinReq
        // which V5 inherits as baseInsulinReq. The V1 SMB-SIZING controls (bolus cap, scale,
        // percent-scale, boost time window) are NOT here — V5 replaces V1's SMB, so
        // they're inert under V5; they live in V1's screen only. EXCEPTION: the cumulative-60min
        // SMB cap ALSO bounds V5/V6 (re-checked at the V6 override), so it lives in the shared
        // Safety category below — see §6.
        if (includeEngineEssentials) add(DoubleKey.ApsBoostMaxIob)
        add(BooleanKey.ApsBoostEnableCircadianIsf)
        add(BooleanKey.ApsBoostAllowWithHighTt)

        // ── 3. Dynamic ISF Controls ──────────────────────────────────
        add(
            PreferenceSubScreenDef(
                key = "boost_dynisf_settings",
                title = ApsStrings.boost_dynisf_title,
                items = listOf(
                    BooleanKey.ApsBoostUseTdd,
                    BooleanKey.ApsBoostAdjustSensitivity,
                    BooleanKey.ApsBoostAutosensWhenNoTdd,
                    UnitDoubleKey.ApsBoostDynIsfNormalTarget,
                    DoubleKey.ApsBoostDynIsfVelocity,
                    UnitDoubleKey.ApsBoostDynIsfBgCap,
                    IntKey.ApsBoostDynIsfAdjustmentFactor
                )
            )
        )

        // ── 4. Exercise Settings (parent with nested sub-screens) ────
        add(
            PreferenceSubScreenDef(
                key = "boost_exercise_settings",
                title = ApsStrings.boost_settings_exercise,
                items = listOf(
                    // 4a. Step Count Settings
                    PreferenceSubScreenDef(
                        key = "boost_stepcount_settings",
                        title = ApsStrings.boost_stepcount_settings_title,
                        summary = ApsStrings.boost_stepcount_settings_summary,
                        items = listOf(
                            IntKey.ApsBoostInactivitySteps,
                            DoubleKey.ApsBoostInactivityPct,
                            DoubleKey.ApsBoostSleepInHours,
                            IntKey.ApsBoostSleepInSteps,
                            IntKey.ApsBoostActivitySteps5,
                            IntKey.ApsBoostActivitySteps15,
                            IntKey.ApsBoostActivitySteps30,
                            IntKey.ApsBoostActivitySteps60,
                            DoubleKey.ApsBoostActivityPct
                        )
                    ),
                    // 4b. Heart Rate Integration
                    PreferenceSubScreenDef(
                        key = "boost_hr_integration_settings",
                        title = ApsStrings.boost_hr_integration_title,
                        summary = ApsStrings.boost_hr_integration_summary,
                        items = listOf(
                            BooleanKey.ApsBoostHrIntegrationEnabled,
                            IntKey.ApsBoostHrMaxBpm,
                            IntKey.ApsBoostHrRestingBpm,
                            IntKey.ApsBoostHrWindowMinutes,
                            BooleanKey.ApsBoostHrStressDetection
                        )
                    ),
                    // 4c. Post-Exercise Recovery
                    PreferenceSubScreenDef(
                        key = "boost_post_exercise_recovery_settings",
                        title = ApsStrings.boost_post_exercise_recovery_title,
                        items = listOf(
                            BooleanKey.ApsBoostPostExerciseRecoveryEnabled,
                            DoubleKey.ApsBoostPostExerciseRecoveryHours,
                            UnitDoubleKey.ApsBoostPostExerciseRecoveryTarget,
                            DoubleKey.ApsBoostPostExerciseRecoveryScale,
                            IntKey.ApsBoostPostExerciseMinDuration
                        )
                    )
                )
            )
        )

        // ── 5. Night Mode ────────────────────────────────────────────
        add(
            PreferenceSubScreenDef(
                key = "boost_night_mode_settings",
                title = ApsStrings.boost_night_mode_title,
                items = buildList {
                    if (includeEngineEssentials) add(BooleanKey.ApsBoostNightModeEnabled)
                    add(StringKey.ApsBoostNightModeStart)
                    add(StringKey.ApsBoostNightModeEnd)
                    if (includeEngineEssentials) add(UnitDoubleKey.ApsBoostNightModeBgOffset)
                    add(BooleanKey.ApsBoostNightModeDisableWithCob)
                    add(BooleanKey.ApsBoostNightModeDisableWithLowTt)
                    // HR-based sleep detection (opt-in)
                    add(BooleanKey.ApsBoostNightModeAutoBySleep)
                    add(IntKey.ApsBoostPreSleepLeadMin)
                    add(IntKey.ApsBoostSleepHysteresisMin)
                    add(IntKey.ApsBoostWakeHrHysteresisMin)
                    // Health Connect HR ingest
                    add(BooleanKey.ApsBoostHealthConnectHrEnabled)
                    add(IntKey.ApsBoostHealthConnectPollMin)
                    // Activity-load SHADOW (HC steps → step baseline; logs would-do ISF, never doses)
                    add(BooleanKey.ApsBoostActivityShadowEnabled)
                    add(ApsIntentKey.GrantHealthConnect.withClick {
                        context.startActivity(
                            Intent(context, HealthConnectPermissionActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    })
                }
            )
        )

        // ── 6. Safety Settings ───────────────────────────────────────
        add(
            PreferenceSubScreenDef(
                key = "boost_safety_settings",
                title = ApsStrings.boost_settings_safety,
                items = buildList {
                    if (includeEngineEssentials) add(BooleanKey.ApsUseSmb)
                    add(BooleanKey.ApsUseSmbWithHighTt)
                    add(BooleanKey.ApsUseSmbAlways)
                    add(BooleanKey.ApsUseSmbWithCob)
                    add(BooleanKey.ApsUseSmbWithLowTt)
                    add(BooleanKey.ApsUseSmbAfterCarbs)
                    if (includeEngineEssentials) add(BooleanKey.ApsUseUam)
                    add(IntKey.ApsMaxSmbFrequency)
                    add(BooleanKey.ApsLoopAtNativeCadence)
                    add(IntKey.ApsMaxMinutesOfBasalToLimitSmb)
                    add(IntKey.ApsUamMaxMinutesOfBasalToLimitSmb)
                    add(IntKey.ApsCarbsRequestThreshold)
                    // Cumulative 60-min SMB volume cap — a hard anti-stacking gate that ALSO bounds V5/V6
                    // (re-checked at the V6 override in OpenAPSBoostPlugin), so it lives in the shared
                    // engine Safety category — NOT just V1's SMB-sizing screen. 0 disables; auto-config
                    // sets it per user (up to ~confirmedCap, so the key's max must cover the cohort).
                    add(DoubleKey.ApsBoostCumulativeSmbCap60Min)
                    // allow-all-BG-sources + bypass-version-check toggles removed —
                    // both are now forced always-on in code (no longer user-facing levers).
                    // V5/V6 controls intentionally NOT here — they live in the selectable "Boost V5"
                    // plugin's screen. V1 is V5-free.
                }
            )
        )

        // ── 7. Advanced Settings ─────────────────────────────────────
        add(
            PreferenceSubScreenDef(
                key = "absorption_smb_advanced",
                title = CoreUiStrings.advanced_settings_title,
                items = listOf(
                    ApsIntentKey.LinkToDocs,
                    BooleanKey.ApsAlwaysUseShortDeltas,
                    BooleanKey.ApsBoostPostRescueTightRampTrial,
                    DoubleKey.ApsMaxDailyMultiplier,
                    DoubleKey.ApsMaxCurrentBasalMultiplier
                )
            )
        )
    }
}

// ── V5/V6 exercise-input mapping (F2) ───────────────────────────────────────────────
// Pure top-level helpers so the live profile build's mapping is unit-testable. They MUST stay
// bit-identical to the retired V3MLG3 block (OpenAPSBoostV3MLG3Plugin.kt, `v5_exerciseActive =
// activityResult.activityState in setOf(...)` / `v5_inPostExerciseWindow =
// postExerciseRecoveryEnabled && now < recoveryWindowEnd`) — V5's consumers were calibrated
// against that mapping while V5 shadowed V4.4.1.

/**
 * Activity states that count as "exercising" for V5/V6 ([OapsProfileBoost.v5_exerciseActive]).
 * Consumed by MealSignalScore's notExercisingTerm and MealHypothesis' fastConfirm !exercising gate.
 * NOTE: includes "STRESS" (as V3MLG3 did) — a high-HR no-steps state should damp meal confirmation
 * just like exercise. Distinct from the plugin's post-exercise `exerciseStateSet`, which
 * deliberately EXCLUDES "STRESS" (stress shouldn't start a recovery window).
 */
internal val V5_EXERCISE_STATES = setOf(
    "ACTIVE", "VIGOROUS_AEROBIC", "MODERATE_AEROBIC", "LIGHT_AEROBIC", "RESISTANCE", "STRESS"
)

/** [OapsProfileBoost.v5_exerciseActive] from V1's activity classification. */
internal fun v5ExerciseActive(activityState: String): Boolean = activityState in V5_EXERCISE_STATES

/**
 * [OapsProfileBoost.v5_inPostExerciseWindow] — true while the post-exercise recovery window is
 * open. Feeds V5's AggressionBudget post-exercise damper (postExerciseRecoveryModifier).
 */
internal fun v5InPostExerciseWindow(postExerciseRecoveryEnabled: Boolean, nowMs: Long, recoveryWindowEndMs: Long): Boolean =
    postExerciseRecoveryEnabled && nowMs < recoveryWindowEndMs

// ── Activity classification ─────────────────────────────────────────────────────────
// Pure so the gate's reach can be tested: before this date the whole block ran only while the Boost
// gate was open, so closing the gate at night also removed the exercise profile reduction and the
// raised activity target (audit #11, a regression from the overnight gate fix).

/** Everything [classifyBoostActivity] reads. Step and HR inputs are this cycle's. */
internal data class BoostActivityInputs(
    /** The Boost gate. Gates only the INACTIVE raise, the one outcome here that adds insulin. */
    val raiseGateOpen: Boolean,
    /** Steps above any of the configured activity thresholds. */
    val isActive: Boolean,
    /** HR classification, or null when HR integration is off or no usable HR was found (none, or stuck). */
    val hr: HrActivityCalculator.HrClassificationResult?,
    val hrIntegrationEnabled: Boolean,
    val hrStressDetection: Boolean,
    /** [StepFeed.inactivityEligible]: live feed, few steps, profile at 100%, and not asleep or overnight. */
    val inactivityEligible: Boolean,
    val stepsAvailable: Boolean,
    val tempTargetSet: Boolean,
    val profilePercent: Int,
    val activityPct: Double,
    val inactivityPct: Double,
    val minBg: Double,
    val maxBg: Double,
    val targetBg: Double,
    val recentSteps60Min: Int,
    val inactivitySteps: Int,
)

/** Result of [classifyBoostActivity]: the activity state, the profile percentage and the targets. */
internal data class BoostActivityClassification(
    val state: String,
    val profileSwitch: Int,
    val minBg: Double,
    val maxBg: Double,
    val targetBg: Double,
    val debug: String,
)

private const val ACTIVITY_BG_TARGET = 150.0
private const val RAISED_BG_TARGET = 160.0

/**
 * Classifies the cycle's activity from steps and, when integrated, heart rate. Exercise, resistance,
 * stress and elevated-HR handling lower the profile or raise the target and run whatever the gate
 * says. The INACTIVE raise (profile to inactivityPct) adds insulin, so it needs all of: the gate open,
 * [BoostActivityInputs.inactivityEligible], and, with HR integration on, positive HR evidence of rest
 * (see [HrActivityCalculator.inactivityRaiseBlockedByHr]).
 */
internal fun classifyBoostActivity(i: BoostActivityInputs): BoostActivityClassification {
    val debug = StringBuilder()
    var minBg = i.minBg
    var maxBg = i.maxBg
    var targetBg = i.targetBg
    var profile = i.profilePercent
    val state: String
    val hr = i.hr

    fun raiseTarget(t: Double) {
        if (!i.tempTargetSet) {
            minBg = t
            maxBg = t
            targetBg = t
        }
    }

    if (hr != null) debug.append("\nHR: ${hr.debugInfo}")

    if (i.isActive) {
        when (hr?.exerciseState) {
            HrActivityCalculator.ExerciseState.VIGOROUS_AEROBIC -> {
                // High intensity aerobic: reduce profile more (activityPct - 10, min 50%), raise target
                state = "VIGOROUS_AEROBIC"
                if (profile == 100) profile = (i.activityPct - 10.0).coerceAtLeast(50.0).toInt()
                raiseTarget(ACTIVITY_BG_TARGET)
                debug.append("\nVigorous aerobic (HR ${String.format("%.0f", hr.averageHrBpm)} bpm, ${hr.hrZone.label}) → profile ${profile}%, target $targetBg")
            }
            HrActivityCalculator.ExerciseState.RESISTANCE -> {
                // Resistance exercise: raise target BG but do NOT reduce profile (acute BG rise, delayed hypo risk)
                state = "RESISTANCE"
                raiseTarget(RAISED_BG_TARGET)
                debug.append("\nResistance exercise (HR ${String.format("%.0f", hr.averageHrBpm)} bpm, ${hr.hrZone.label}) → profile unchanged at ${profile}%, target $targetBg")
            }
            null, HrActivityCalculator.ExerciseState.MODERATE_AEROBIC,
            HrActivityCalculator.ExerciseState.LIGHT_AEROBIC -> {
                state = "ACTIVE"
                if (profile == 100) profile = i.activityPct.toInt()
                raiseTarget(ACTIVITY_BG_TARGET)
                debug.append("\nActivity detected → profile ${profile}%, target $targetBg")
            }
            else -> {
                // HR signal contradicts steps (LOW confidence): fall back to step-only ACTIVE
                state = "ACTIVE"
                if (profile == 100) profile = i.activityPct.toInt()
                raiseTarget(ACTIVITY_BG_TARGET)
                debug.append("\nActivity detected (HR inconclusive: ${hr.exerciseState}) → profile ${profile}%, target $targetBg")
            }
        }
    } else if (i.inactivityEligible) {
        if (i.hrIntegrationEnabled && HrActivityCalculator.inactivitySuppressedByElevatedHr(hr)) {
            // zone >= 3 with low steps is probable non-step exercise (cycling, rowing,
            // resistance). Suppress the raise and raise the target instead.
            state = "RESISTANCE"
            raiseTarget(RAISED_BG_TARGET)
            debug.append("\nInactivity SUPPRESSED (HR ${hr?.hrZone?.label} elevated, 60m steps ${i.recentSteps60Min}) → target $targetBg, profile unchanged")
        } else if (i.hrStressDetection && hr?.exerciseState == HrActivityCalculator.ExerciseState.STRESS &&
            hr.confidence != HrActivityCalculator.Confidence.LOW
        ) {
            state = "STRESS"
            raiseTarget(RAISED_BG_TARGET)
            debug.append("\nStress/illness (HR ${String.format("%.0f", hr.averageHrBpm)} bpm, ${hr.hrZone.label}, no movement) → target $targetBg, profile unchanged")
        } else if (i.hrIntegrationEnabled && HrActivityCalculator.inactivityRaiseBlockedByHr(hr)) {
            // Zone 2 (easy cycling, rowing) is not sedentary, and with HR integration on, no usable HR
            // is not evidence of rest either. Withhold the raise; leave target and profile as they are.
            state = if (hr == null) "HR_UNAVAILABLE" else "HR_ELEVATED"
            debug.append(
                if (hr == null) "\nInactivity raise WITHHELD (HR integration on, no usable HR, 60m steps ${i.recentSteps60Min}) → profile unchanged"
                else "\nInactivity raise WITHHELD (HR ${hr.hrZone.label}, 60m steps ${i.recentSteps60Min}) → profile unchanged"
            )
        } else if (!i.raiseGateOpen) {
            state = "normal"
            debug.append("\nInactivity raise WITHHELD (Boost gate closed) → profile unchanged")
        } else {
            state = "INACTIVE"
            profile = i.inactivityPct.toInt()
            debug.append("\nInactivity detected (60m steps ${i.recentSteps60Min} < ${i.inactivitySteps}) → profile ${profile}%")
        }
    } else if (i.hrIntegrationEnabled && hr?.exerciseState == HrActivityCalculator.ExerciseState.RESISTANCE &&
        hr.confidence != HrActivityCalculator.Confidence.LOW
    ) {
        // HR-only resistance detection (steps don't detect this)
        state = "RESISTANCE"
        raiseTarget(RAISED_BG_TARGET)
        debug.append("\nResistance (HR-only, ${hr.hrZone.label}) → target $targetBg, profile unchanged")
    } else if (i.hrStressDetection && hr?.exerciseState == HrActivityCalculator.ExerciseState.STRESS &&
        hr.confidence != HrActivityCalculator.Confidence.LOW
    ) {
        state = "STRESS"
        raiseTarget(RAISED_BG_TARGET)
        debug.append("\nStress (HR-only, ${hr.hrZone.label}) → target $targetBg, profile unchanged")
    } else if (!i.stepsAvailable) {
        // F1: feed dark and no HR-only classification fired. Profile stays 100%.
        state = "steps-unknown"
        debug.append("\nActivity: steps-unknown (feed unavailable — no INACTIVE, profile unchanged)")
    } else {
        state = "normal"
        debug.append("\nActivity: normal (no adjustment)")
    }
    return BoostActivityClassification(state, profile, minBg, maxBg, targetBg, debug.toString())
}
