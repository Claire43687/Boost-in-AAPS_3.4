package app.aaps.wear.complications

import android.app.PendingIntent
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.WeightedElementsComplicationData
import app.aaps.core.interfaces.logging.LTag
import app.aaps.wear.data.ComplicationData as ComplicationStore

/**
 * TIR (time in range) Complication, WEIGHTED_ELEMENTS (Boost).
 *
 * Renders the last 24 h time-in-range split as a ring of five coloured segments in proportion.
 * The five percentages arrive from the phone in
 * [app.aaps.core.interfaces.rx.weardata.EventData.Status.tirWeights] as "vlow,low,inrange,high,vhigh";
 * each becomes a weighted element with its fixed band colour.
 * Display only; never affects dosing.
 */
class TirWeightedComplication : ModernBaseComplicationProviderService() {

    override fun buildComplicationData(
        type: ComplicationType,
        data: ComplicationStore,
        complicationPendingIntent: PendingIntent
    ): ComplicationData? {
        if (type != ComplicationType.WEIGHTED_ELEMENTS) {
            aapsLogger.warn(LTag.WEAR, "TirWeightedComplication unexpected type: $type")
            return null
        }
        // WeightedElementsComplicationData needs API 33 and wear's minSdk is 30. The system only asks for
        // this type on API 33 and later, so the guard changes nothing on a watch; it states the constraint.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null

        val weights = parseWeights(data.statusData.tirWeights)
        val elements = if (weights != null && weights.any { it > 0f }) {
            BAND_COLORS.indices.map { i ->
                WeightedElementsComplicationData.Element(weight = weights.getOrElse(i) { 0f }, color = BAND_COLORS[i])
            }
        } else {
            // No data yet: a single neutral grey ring so the slot is visibly populated
            listOf(WeightedElementsComplicationData.Element(weight = 1f, color = NO_DATA_COLOR))
        }

        val inRangePct = inRangeText(weights)
        return WeightedElementsComplicationData.Builder(
            elements = elements,
            contentDescription = PlainComplicationText.Builder(text = "Time in range $inRangePct").build()
        )
            .setText(PlainComplicationText.Builder(text = inRangePct).build())
            .setTitle(PlainComplicationText.Builder(text = "TIR").build())
            .setTapAction(complicationPendingIntent)
            .build()
    }

    override fun getProviderCanonicalName(): String = TirWeightedComplication::class.java.canonicalName!!

    companion object {

        // vLow, low, inRange, high, vHigh: must match the TIR colour scheme on the faces.
        private val BAND_COLORS = intArrayOf(
            0xFFC62828.toInt(), // very low  (dark red)
            0xFFFF9F45.toInt(), // low       (orange)
            0xFF41C97B.toInt(), // in range  (green)
            0xFFFFC233.toInt(), // high      (amber)
            0xFFFF5252.toInt()  // very high (red)
        )
        private val NO_DATA_COLOR = 0xFF455A64.toInt()

        /** Five band weights from the phone's "vlow,low,inrange,high,vhigh" string, or null when absent or malformed. */
        @VisibleForTesting
        internal fun parseWeights(raw: String): List<Float>? {
            if (raw.isBlank()) return null
            val parts = raw.split(",").mapNotNull { it.trim().toFloatOrNull() }
            return if (parts.size == BAND_COLORS.size) parts else null
        }

        /** Centre text: the in-range percentage, or "--" without data. */
        @VisibleForTesting
        internal fun inRangeText(weights: List<Float>?): String = weights?.getOrNull(2)?.let { "${it.toInt()}%" } ?: "--"
    }
}
