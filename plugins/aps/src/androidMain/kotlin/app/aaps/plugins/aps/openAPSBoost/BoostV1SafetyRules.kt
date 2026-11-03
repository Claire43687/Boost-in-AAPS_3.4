package app.aaps.plugins.aps.openAPSBoost

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileBoost
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

// Dose-path rules for the V1 Boost engine (DetermineBasalBoost), from the dose-path audit
// (items 5, 19 and 20).
//
// They live here, as top-level functions, because DetermineBasalBoost.determine_basal sits at the limit
// of what ART's verifier accepts: an eleven-line branch inlined into it took the method from 246 to 270
// registers and two release builds died at startup with a VerifyError. determine_basal calls
// each function in place of the expression it replaces. The functions it calls take at most five argument
// registers (a Double takes two), so that each call compiles to a plain invoke-static. A wider call needs
// an invoke-static/range and its own run of consecutive registers: measured with d8 and dexdump on
// calling the all-primitive forms from determine_basal took it from 266 to 273 registers, and
// the forms used here take it to 237. The primitive versions carry the logic and the tests.

/**
 * Final microbolus held inside oref's max_iob (audit item 5). insulinReq is already clamped to
 * max_iob − IOB, but tier divisors below 1 and the basal-derived Boost doses in Tiers 3, 4 and 6 are only
 * clamped against boost_maxIOB, so an SMB could take IOB above the user's max_iob. The dose is reduced to
 * the headroom, floored to the bolus increment; a dose already inside the headroom is returned unchanged.
 * A headroom that is zero, negative or not a number gives 0.
 */
internal fun boostSmbWithinMaxIob(microBolus: Double, maxIob: Double, iob: Double, bolusIncrement: Double): Double {
    val headroom = maxIob - iob
    if (!(headroom > 0.0)) return 0.0
    if (microBolus <= headroom) return microBolus
    if (!(bolusIncrement > 0.0)) return min(microBolus, headroom)
    val steps = 1.0 / bolusIncrement
    return floor(headroom * steps + 1e-9) / steps
}

/** [boostSmbWithinMaxIob] with the profile's max_iob and bolus increment; the form determine_basal calls. */
internal fun boostSmbWithinMaxIob(microBolus: Double, profile: OapsProfileBoost, iobData: IobTotal): Double =
    boostSmbWithinMaxIob(microBolus, profile.max_iob, iobData.iob, profile.bolus_increment)

/**
 * Conditions under which the G3 pre-UAM hold engages (audit item 19), before its release signals are
 * considered. The hold was written with "recentLowBG >= 70 (not in hypo recovery)" as a condition, which
 * scoped it to rises from near target and left recovery to the fast-carb rebound protection; in effect a
 * low lifted the hold, so a low 45 to 60 min ago was covered by neither G3 nor the 45-min post-rescue
 * window. A recent low no longer lifts it. With no low in the last hour the old condition was always
 * true, so behaviour there is unchanged.
 */
internal fun boostG3HoldConditionsMet(mealCob: Double, delta: Double, shortAvgDelta: Double): Boolean =
    mealCob < 1.0 && delta >= 5.0 && shortAvgDelta >= 3.0

/** [boostG3HoldConditionsMet] read from the meal and glucose status; the form determine_basal calls. */
internal fun boostG3HoldConditionsMet(mealData: MealData, glucoseStatus: GlucoseStatus): Boolean =
    boostG3HoldConditionsMet(mealData.mealCOB, glucoseStatus.delta, glucoseStatus.shortAvgDelta)

/**
 * Whether the Tier 8 spike override may raise the SMB cap (audit item 20). The override exists because
 * Tier 8 is the one tier capped by the basal-derived maxBolus; it was not restricted to Tier 8, and it
 * re-inflated doses that the post-rescue window or the ML tier downgrade had deliberately sent to the
 * conservative tiers. It now applies only to Tier 8, and never in the post-rescue window or under the
 * ML downgrade. The glucose, delta, insulinReq and IOB conditions stay where they were.
 */
internal fun boostSpikeOverrideAllowed(boostActive: Boolean, tier: String?, inPostRescueWindow: Boolean, mlTierDowngrade: Boolean): Boolean =
    boostActive && tier == "REGULAR_OREF1" && !inPostRescueWindow && !mlTierDowngrade

/**
 * Low-glucose threshold after Boost's lowering on an accelerating rise.
 * Boost lowers the threshold to 65 mg/dL when delta_accl is positive, which was meant for a rise that is
 * speeding up. delta_accl is also positive on a fall that is slowing (delta -1 against a short average of
 * -3 gives +67), so with an LGS setting of 80 the threshold went to 65. The lowering now needs a rising
 * delta and never goes below the user's LGS setting. On a rising,
 * accelerating cycle with no LGS setting above 65 the result is 65, as before.
 *
 * delta_accl > 0 is tested as delta > shortAvgDelta with |shortAvgDelta| > 0.001, the same sign as
 * determine_basal's rounded value except within 0.0001 mg/dL of equality.
 */
internal fun boostAccelLoweredThreshold(threshold: Double, delta: Double, shortAvgDelta: Double, lgsThreshold: Int?): Double {
    val accelerating = abs(shortAvgDelta) > 0.001 && delta > shortAvgDelta
    if (!(accelerating && delta > 0.0)) return threshold
    return kotlin.math.max(65.0, lgsThreshold?.toDouble() ?: 0.0)
}

/** [boostAccelLoweredThreshold] read from the glucose status and profile; the form determine_basal calls. */
internal fun boostAccelLoweredThreshold(threshold: Double, glucoseStatus: GlucoseStatus, profile: OapsProfileBoost): Double =
    boostAccelLoweredThreshold(threshold, glucoseStatus.delta, glucoseStatus.shortAvgDelta, profile.lgsThreshold)
