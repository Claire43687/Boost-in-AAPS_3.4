package app.aaps.core.data.model

import app.aaps.core.data.iob.Iob
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Insulin configuration holds info about insulin
 */
data class ICfg(
    /**
     * Insulin name
     */
    var insulinLabel: String,
    /**
     * Aka DIA before in milliseconds
     */
    var insulinEndTime: Long,
    /**
     * Peak time from start in milliseconds
     */
    var insulinPeakTime: Long,
    /**
     * Insulin concentration (0.2 for U20, 2.0 for U200 insulin)
     */
    var concentration: Double = 1.0,
    /**
     * Shape of the action curve. [InsulinModel.OREF] for everything written before the field existed.
     */
    var model: InsulinModel = InsulinModel.OREF
) {

    constructor(insulinLabel: String, peak: Int, dia: Double, concentration: Double, model: InsulinModel = InsulinModel.OREF)
        : this(insulinLabel = insulinLabel, insulinEndTime = (dia * 3600 * 1000).toLong(), insulinPeakTime = (peak * 60000).toLong(), concentration = concentration, model = model)

    /**
     * Used in InsulinPlugin (insulin editor)
     */
    fun isEqual(iCfg: ICfg?): Boolean {
        iCfg?.let { iCfg ->
            if (insulinLabel != iCfg.insulinLabel)
                return false
            if (insulinEndTime != iCfg.insulinEndTime)
                return false
            if (insulinPeakTime != iCfg.insulinPeakTime)
                return false
            if (concentration != iCfg.concentration)
                return false
            if (model != iCfg.model)
                return false
            return true
        }
        return false
    }

    /**
     * Same insulin action (DIA, peak, concentration, curve model), whatever the name. Use this to ask "is this insulin
     * in the list", because a name can change while the insulin stays the same: the list rebuilds every
     * label at start, and a profile switch keeps the label it was made with.
     */
    fun hasSameCurve(iCfg: ICfg?): Boolean =
        iCfg != null &&
            insulinEndTime == iCfg.insulinEndTime &&
            insulinPeakTime == iCfg.insulinPeakTime &&
            concentration == iCfg.concentration &&
            model == iCfg.model

    /**
     * DIA (insulinEndTime) in hours rounded to 1 decimal place
     */
    val dia: Double
        get() = (insulinEndTime / 3600.0 / 100.0).roundToInt() / 10.0

    /**
     * Peak time in minutes
     */
    val peak: Int
        get() = (insulinPeakTime / 60000).toInt()

    /**
     * False for the `insulinEndTime = -1` sentinel the DB v33 migration writes into rows that predate ICfg
     * (and for anything else non-positive). Such a record is not an insulin: [dia] rounds to `0.0`, which is
     * outside the hard limits, so an APS run that accepts it aborts every cycle instead of dosing.
     *
     * Treat it as "no insulin" — the same as absent — so callers fall through to their normal
     * ask-or-refuse path rather than propagating a degenerate curve.
     */
    val isUsable: Boolean
        get() = insulinEndTime > 0 && insulinPeakTime > 0

    /**
     * Set insulinEndTime aka DIA
     * @param hours duration in hours
     */
    fun setDia(hours: Double) {
        insulinEndTime = (hours * 3600 * 1000).toLong()
    }

    /**
     * Set insulinPeakTime aka peak
     * @param minutes peak tme in minutes
     */
    fun setPeak(minutes: Int) {
        insulinPeakTime = (minutes * 60000).toLong()
    }

    /**
     * insulinNickname is only used in insulin editor
     */
    var insulinNickname: String = ""

    /**
     * deepClone is only used in insulin editor
     */
    fun deepClone(): ICfg = ICfg(insulinLabel, insulinEndTime, insulinPeakTime, concentration, model).also { it.insulinNickname = insulinNickname }

    /**
     * Insulin on board and activity of [bolus] at [time], from the curve that [model] selects.
     *
     * [BS.amount] is in IU (U100-equivalent units) here at every concentration: PumpSync stores a pump's
     * delivery as `PumpInsulin.iU(concentration)`, i.e. pump units times concentration. Both curves are linear in
     * the amount, so IOB comes out in IU too; only the Lyumjev peak time depends on the amount, see
     * [lyumjevEffectiveDose].
     */
    fun iobCalcForTreatment(bolus: BS, time: Long): Iob =
        when (model) {
            InsulinModel.OREF       -> orefIobCalcForTreatment(bolus, time)
            InsulinModel.LYUMJEV_PD -> lyumjevPdIobCalcForTreatment(bolus, time)
        }

    /**
     * Lyumjev pharmacodynamic curve, transcribed from Boost 6.1.2 `InsulinLyumjevPDBasePlugin.iobCalcForTreatment`.
     * The arithmetic and the edge handling are kept as they were there (no clamp for a time before the bolus,
     * a hard end at 8 h). The effective dose follows Boost 7.4.5 rather than 6.1.2, whose two plugins had it
     * swapped (audit #23); see [lyumjevEffectiveDose].
     *
     * Peak time (minutes):  tp = (A0 + A1 X) / (1 + B1 X), X the effective dose
     * Activity:             (2 dose / tpModel) t exp(-t² / tpModel), tpModel = 2 tp²
     * IOB:                  dose (exp(-t² / tpModel) - exp(-end² / tpModel)), end = 480 min
     */
    private fun lyumjevPdIobCalcForTreatment(bolus: BS, time: Long): Iob {
        val result = Iob()
        if (bolus.amount != 0.0) {
            val t = (time - bolus.timestamp) / 1000.0 / 60.0  // minutes since bolus

            if (t < LYUMJEV_PD_END_MINUTES) {
                // Dose-dependent peak time
                val effDose = lyumjevEffectiveDose(bolus.amount)
                val tp = (LYUMJEV_A0 + LYUMJEV_A1 * effDose) / (1 + LYUMJEV_B1 * effDose)
                val tpModel = tp.pow(2.0) * 2  // transform for model formula

                // Gaussian activity curve
                result.activityContrib = (2 * bolus.amount / tpModel) * t * exp(-t.pow(2.0) / tpModel)

                // Integrated IOB (closed-form of activity integral)
                val lowerLimit = t
                val upperLimit = LYUMJEV_PD_END_MINUTES
                result.iobContrib = bolus.amount * (exp(-lowerLimit.pow(2.0) / tpModel) - exp(-upperLimit.pow(2.0) / tpModel))
            }
        }
        return result
    }

    private fun orefIobCalcForTreatment(bolus: BS, time: Long): Iob {
        devAssert(insulinEndTime != 0L)
        devAssert(insulinPeakTime != 0L)
        val result = Iob()
        if (bolus.amount != 0.0) {
            val bolusTime = bolus.timestamp
            val t = (time - bolusTime) / 1000.0 / 60.0
            // Defensive clamp: the bilinear IOB model is only well-defined for 0 < tp < td/2. Corrupt
            // iCfg (the v33 migration sentinel -1 before its repair, or a malformed NS-imported insulin
            // config) would otherwise divide by zero, produce a negative tau, or — when td <= 0 — make
            // the `t < td` gate never fire and silently contribute ZERO IOB, all of which mislead the
            // loop into overdelivery. These bounds are MATH-validity floors only, NOT the medical limits
            // (which are enforced upstream): legitimate peaks below HardLimits.LIMIT_PEAK (e.g. 30 min) and
            // any dia >= MIN_DIA are preserved unchanged; only degenerate values are sanitized.
            val td = (dia * 60).coerceAtLeast(MIN_DIA_MINUTES)
            val tp = peak.toDouble().coerceIn(MIN_PEAK_MINUTES, td / 2.0 - 1.0)
            // force the IOB to 0 if over DIA hours have passed
            if (t < td) {
                val tau = tp * (1 - tp / td) / (1 - 2 * tp / td)
                val a = 2 * tau / td
                val s = 1 / (1 - a + (1 + a) * exp(-td / tau))
                result.activityContrib = bolus.amount * (s / tau.pow(2.0)) * t * (1 - t / td) * exp(-t / tau)
                result.iobContrib = bolus.amount * (1 - s * (1 - a) * ((t.pow(2.0) / (tau * td * (1 - a)) - t / tau - 1) * exp(-t / tau) + 1))
            }
        }
        return result
    }

    companion object {

        // Math-validity floors for iobCalcForTreatment. They only engage for corrupt/degenerate iCfg
        // and are no-ops for real configs; they are NOT the medical HardLimits.
        private const val MIN_DIA_MINUTES = 300.0 // 5 h (mirrors HardLimits.MIN_DIA); floors corrupt/sentinel DIA <= 0
        private const val MIN_PEAK_MINUTES = 1.0  // just keeps tp > 0; real peaks (incl. sub-MIN_PEAK like 30 min) pass through

        // Lyumjev PD model constants, unchanged from Boost 6.1.2 InsulinLyumjevPDBasePlugin.
        private const val LYUMJEV_A0 = 61.33    // minutes
        private const val LYUMJEV_A1 = 12.27
        private const val LYUMJEV_B1 = 0.05185

        /** The Lyumjev PD curve ends here whatever the dose. The editor fixes DIA to this value for that model. */
        const val LYUMJEV_PD_END_HOURS = 8.0
        private const val LYUMJEV_PD_END_MINUTES = LYUMJEV_PD_END_HOURS * 60.0

        /** Nominal peak stored with a Lyumjev PD insulin, as 6.1.2's plugin reported it. The curve does not use it. */
        const val LYUMJEV_PD_NOMINAL_PEAK_MINUTES = 45

        /**
         * The dose X that sets the Lyumjev peak time: the insulin delivered, in IU.
         *
         * 4 pump units of U100 are 4 IU and 4 pump units of U200 are 8 IU, so X is 4 and 8. Here [amount] is
         * already IU at every concentration (PumpSync stores `PumpInsulin.iU(concentration)`, pump units x
         * concentration), so X is the amount itself and the same insulin gives the same curve on either.
         *
         * Boost 6.1.2 had the two plugins swapped: U100 doubled its amount and U200 used its pump units, giving
         * U100 four times U200's X for the same insulin. The swap dates from the model's first commit
         * (44a0263099, May 2022), which doubled the dose for insulin ID 6 under the comment "ID = 6 for Lyumjev
         * U200"; ID 6 was U100. Fixed in Boost 7.4.5 as dose-path audit #23.
         */
        fun lyumjevEffectiveDose(amount: Double): Double = amount

        /** Lyumjev is sold as U100 and U200, the two concentrations the model has a plugin for in Boost. */
        fun lyumjevPdSupportsConcentration(concentration: Double): Boolean = concentration == 1.0 || concentration == 2.0
    }
}

/**
 * Index of the list entry for [iCfg], or -1. An entry with the same label and curve wins. If there is none,
 * the first entry with the same curve is taken, so an insulin still matches after its label has changed
 * (see [ICfg.hasSameCurve]).
 */
fun List<ICfg>.indexOfInsulin(iCfg: ICfg?): Int =
    indexOfFirst { it.isEqual(iCfg) }.takeIf { it >= 0 } ?: indexOfFirst { it.hasSameCurve(iCfg) }

/**
 * Label of the list entry for [iCfg] (see [indexOfInsulin]), or the label of [iCfg] itself when no entry has
 * the same curve. Use it to find or mark the running insulin in the list. The running insulin keeps the label
 * it was made with, which the list may no longer have (an older name, or an insulin renamed since).
 */
fun List<ICfg>.labelOfInsulin(iCfg: ICfg?): String? =
    getOrNull(indexOfInsulin(iCfg))?.insulinLabel ?: iCfg?.insulinLabel