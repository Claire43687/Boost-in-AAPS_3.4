package app.aaps.wear.complications

import android.app.PendingIntent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountUpTimeReference
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.wear.R
import app.aaps.wear.interaction.utils.SmallestDoubleString
import dev.zacsweers.metro.Inject
import java.time.Instant
import java.util.concurrent.TimeUnit
import app.aaps.wear.data.ComplicationData as ComplicationStore

/**
 * SGV (Sensor Glucose Value), RANGED_VALUE variant (Boost).
 *
 * Same data as [SgvComplication], but the BG is also emitted as a numeric RANGED_VALUE (mg/dl,
 * clamped to [BG_MIN]..[BG_MAX]) beside the display text (BG + arrow) and title (delta + auto-updating
 * time). The numeric value lets a Watch Face Format face colour by time in range and draw a ring in
 * proportion to BG, which a SHORT_TEXT-only complication cannot provide.
 * Display only; never affects dosing.
 */
class SgvRangedComplication : ModernBaseComplicationProviderService() {

    @Inject lateinit var sp: SP

    override fun buildComplicationData(
        type: ComplicationType,
        data: ComplicationStore,
        complicationPendingIntent: PendingIntent
    ): ComplicationData? {
        val bgData = data.bgData
        val mainText = bgData.sgvString + bgData.slopeArrow + "︎"
        val title = buildDeltaAndTimeTitle(bgData)

        return when (type) {
            // No reading is sgv 0, which the clamp turned into a ranged value of 40: an urgent low on a face
            // that colours by value, beside the text "---".
            ComplicationType.RANGED_VALUE -> if (bgData.sgv <= 0.0) NoDataComplicationData() else {
                val value = bgData.sgv.toFloat().coerceIn(BG_MIN, BG_MAX)
                RangedValueComplicationData.Builder(
                    value = value,
                    min = BG_MIN,
                    max = BG_MAX,
                    contentDescription = PlainComplicationText.Builder(text = "Glucose $mainText").build()
                )
                    .setText(PlainComplicationText.Builder(text = mainText).build())
                    .setTitle(title)
                    .setTapAction(complicationPendingIntent)
                    .build()
            }

            ComplicationType.SHORT_TEXT   -> {
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(text = mainText).build(),
                    contentDescription = PlainComplicationText.Builder(text = "Glucose $mainText").build()
                )
                    .setTitle(title)
                    .setTapAction(complicationPendingIntent)
                    .build()
            }

            else                          -> {
                aapsLogger.warn(LTag.WEAR, "SgvRangedComplication unexpected type: $type")
                null
            }
        }
    }

    /** A stale reading gives no ranged value, so a face cannot colour an old BG as current. The text form keeps the default. */
    override fun buildOutdatedComplicationData(
        type: ComplicationType,
        data: ComplicationStore,
        complicationPendingIntent: PendingIntent
    ): ComplicationData? =
        if (type == ComplicationType.RANGED_VALUE) NoDataComplicationData()
        else super.buildOutdatedComplicationData(type, data, complicationPendingIntent)

    // Kept as in Boost 6.1.2: honours the detailed-delta and unicode preferences and counts up from the
    // reading time itself, so it does not use the base class's buildCountUpText (which rounds down).
    private fun buildDeltaAndTimeTitle(bgData: EventData.SingleBg): TimeDifferenceComplicationText {
        val rawDelta = if (sp.getBoolean(R.string.key_show_detailed_delta, false)) bgData.deltaDetailed else bgData.delta
        val useUnicode = sp.getBoolean("complication_unicode", true)
        val deltaSymbol = if (useUnicode) "Δ" else ""
        val deltaText = deltaSymbol + SmallestDoubleString(rawDelta).minimise(4)
        return TimeDifferenceComplicationText.Builder(
            style = TimeDifferenceStyle.SHORT_SINGLE_UNIT,
            countUpTimeReference = CountUpTimeReference(Instant.ofEpochMilli(bgData.timeStamp))
        )
            .setMinimumTimeUnit(TimeUnit.MINUTES)
            .setText("^1 $deltaText")
            .build()
    }

    override fun getProviderCanonicalName(): String = SgvRangedComplication::class.java.canonicalName!!

    companion object {

        // BG range the ring spans (mg/dl). Colour thresholds (TIR) are applied by the face on the value.
        const val BG_MIN = 40f
        const val BG_MAX = 350f
    }
}
