package app.aaps.plugins.sync.wear.wearintegration

import kotlin.math.roundToInt

/**
 * Boost: time-in-range split sent to the watch for the TIR ring complication.
 *
 * Fixed AGP bands in mg/dl: below 54 very low, 54 to 69 low, 70 to 180 in range, 181 to 250 high,
 * above 250 very high. Values below 39 are sensor error codes and are left out of the count.
 * Display only; this never influences dosing.
 */
internal object TirWeights {

    /**
     * Five whole percentages "vlow,low,inrange,high,vhigh" over [valuesMgdl], each rounded half up
     * on its own (so the five need not sum to exactly 100), or an empty string when no reading counts.
     */
    fun format(valuesMgdl: Iterable<Double>): String {
        var vLow = 0
        var low = 0
        var inRange = 0
        var high = 0
        var vHigh = 0
        var total = 0
        for (v in valuesMgdl) {
            when {
                v < 39  -> {}            // sensor error, ignore
                v < 54  -> { vLow++; total++ }
                v < 70  -> { low++; total++ }
                v > 250 -> { vHigh++; total++ }
                v > 180 -> { high++; total++ }
                else    -> { inRange++; total++ }
            }
        }
        if (total == 0) return ""
        // Math.round in 6.1.2 rounds half up; roundToInt rounds half away from zero, identical for the
        // non-negative values here.
        fun pct(n: Int) = (n * 100.0 / total).roundToInt()
        return "${pct(vLow)},${pct(low)},${pct(inRange)},${pct(high)},${pct(vHigh)}"
    }
}
