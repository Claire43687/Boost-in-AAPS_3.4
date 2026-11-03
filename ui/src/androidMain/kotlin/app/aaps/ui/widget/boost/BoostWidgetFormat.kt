package app.aaps.ui.widget.boost

import app.aaps.core.interfaces.overview.ActivityMode
import app.aaps.ui.compose.overview.boost.BoostStatus
import java.util.Locale
import kotlin.math.abs

/**
 * The pure rules of the Boost home-screen widget, kept apart from the RemoteViews code so they can
 * be tested on the host. Every rule and constant here is taken from `BoostWidget` in Boost 6.1.2.
 *
 * Colours are plain ARGB integers, not `android.graphics.Color.parseColor`, because the host tests
 * run against the android.jar stub, where `parseColor` answers 0.
 */
object BoostWidgetFormat {

    const val WHITE: Int = 0xFFFFFFFF.toInt()

    /** `widget_ribbonWarning` from the 3.4 core/ui colours; the 4.0 resources no longer have it. */
    const val RIBBON_WARNING: Int = 0xFFF4D700.toInt()

    /** `widget_ribbonTextDefault` from the 3.4 core/ui colours. */
    const val RIBBON_TEXT_DEFAULT: Int = WHITE

    private const val ACTIVITY_ACTIVE: Int = 0xFF42A5F5.toInt()
    private const val ACTIVITY_INACTIVE: Int = 0xFFFF9800.toInt()
    private const val ACTIVITY_SLEEP_IN: Int = 0xFFAB47BC.toInt()
    private const val ACTIVITY_BOOST_OFF: Int = 0xFF78909C.toInt()

    /**
     * Five-zone glucose colour of the bobble ring. The widget kept its own palette in 6.1.2 (yellow
     * for high) where the Compose overview uses orange, so this is not `bgZoneColor`.
     */
    fun zoneColor(bgMgdl: Double): Int = when {
        bgMgdl > 250 -> 0xFFFF1744.toInt()
        bgMgdl > 180 -> 0xFFFFEB3B.toInt()
        bgMgdl >= 70 -> 0xFF4CAF50.toInt()
        bgMgdl >= 54 -> 0xFFFF5722.toInt()
        bgMgdl > 0   -> 0xFFD50000.toInt()
        else         -> 0xFF4CAF50.toInt()
    }

    private const val VALUE_CHARS = 7
    private const val TABLE_PADDING_DP = 8f

    data class TextSizes(val label: Float, val value: Float, val tier: Float, val timeAgo: Float)

    /**
     * Text sizes in sp for the widget's current size. `autoSizeTextType` does nothing inside
     * RemoteViews, so the sizes are set from the dimensions the launcher reports. The base sizes
     * are for 250 x 110 dp (a 3 x 2 cell widget).
     */
    fun computeTextSizes(widthDp: Int, heightDp: Int): TextSizes {
        val widthScale = widthDp / 250f
        val heightScale = heightDp / 110f
        val scale = minOf(widthScale, heightScale).coerceIn(0.8f, 2.5f)
        // The values share two columns in whatever width the bobble leaves, and the bobble is sized
        // from the height, so on a tall two-row widget it takes most of the width. Scaling by the
        // whole widget then gave "0.00 U" and "Normal" more text than their columns hold and the
        // two ran together. The cap fits seven characters at about 0.6 em into one column.
        val valueColumnDp = (widthDp - bobbleSizeDp(heightDp) - TABLE_PADDING_DP) / 2f
        val valueColumnCapSp = valueColumnDp / (VALUE_CHARS * 0.6f)
        return TextSizes(
            label = (11f * scale).coerceIn(9f, 16f),
            value = (16f * scale).coerceIn(12f, 30f).coerceAtMost(maxOf(12f, valueColumnCapSp)),
            tier = (14f * scale).coerceIn(12f, 24f),
            timeAgo = (11f * scale).coerceIn(9f, 16f)
        )
    }

    /** Bobble edge in dp: about 80 % of the widget height, between 100 and 240 dp. */
    fun bobbleSizeDp(heightDp: Int): Float = (heightDp * 0.8f).coerceIn(100f, 240f)

    /** The TDD shown: the algorithm's weighted TDD, else the one in its log, else the 7-day mean. */
    fun tddValue(status: BoostStatus): Double = when {
        status.tddWeighted > 0  -> status.tddWeighted
        status.tddFromDebug > 0 -> status.tddFromDebug
        status.tdd7d > 0        -> status.tdd7d
        else                    -> 0.0
    }

    fun tddText(status: BoostStatus, locale: Locale): String {
        val tdd = tddValue(status)
        return if (tdd > 0) String.format(locale, "%.1f U", tdd) else "--"
    }

    /** DynISF in the user's units, or "--" before the algorithm has reported one. */
    fun dynIsfText(variableSensInUnits: Double?, locale: Locale): String =
        if (variableSensInUnits != null) String.format(locale, "%.1f", variableSensInUnits) else "--"

    data class Cell(val label: String, val text: String, val color: Int)

    /**
     * Headline under the bobble. With V5/V6 as the active APS it is the state and action multiplier
     * in the state colour; otherwise the tier label in the tier colour.
     */
    fun headline(status: BoostStatus, locale: Locale): Pair<String, Int> =
        if (status.isV5Active)
            "${status.v5State.short} ×${String.format(locale, "%.1f", status.v5ActionMult)}" to status.v5State.colorHex.toInt()
        else
            status.tierLabel to status.tier.colorHex.toInt()

    /** The activity cell, which shows the V5 meal score instead while V5 is the active APS. */
    fun activityCell(status: BoostStatus, locale: Locale): Cell =
        if (status.isV5Active)
            Cell("Score", String.format(locale, "%.2f", status.v5Score), status.v5State.colorHex.toInt())
        else
            Cell("Activity", status.activityDetail, activityColor(status.activityMode))

    fun activityColor(mode: ActivityMode): Int = when (mode) {
        ActivityMode.ACTIVE    -> ACTIVITY_ACTIVE
        ActivityMode.INACTIVE  -> ACTIVITY_INACTIVE
        ActivityMode.SLEEP_IN  -> ACTIVITY_SLEEP_IN
        ActivityMode.BOOST_OFF -> ACTIVITY_BOOST_OFF
        ActivityMode.NORMAL    -> WHITE
    }

    fun profilePercentText(pct: Int): String = "$pct%"

    fun profilePercentColor(pct: Int): Int = if (pct != 100) RIBBON_WARNING else WHITE

    /** Which target the target cell shows. */
    enum class TargetSource { TEMP_TARGET, ALGORITHM_ADJUSTED, PROFILE, NONE }

    /**
     * A running temp target wins. Without one, the target the algorithm used is shown when it
     * differs from the profile target by more than 0.01 mg/dl, and the profile range otherwise.
     * [targetUsedMgdl] is the last loop run's target, or the Boost status target when the loop has
     * not run (AAPSClient), as in 6.1.2.
     */
    fun targetSource(hasTempTarget: Boolean, profileTargetMgdl: Double?, targetUsedMgdl: Double): TargetSource = when {
        hasTempTarget                                                                  -> TargetSource.TEMP_TARGET
        profileTargetMgdl == null                                                      -> TargetSource.NONE
        targetUsedMgdl != 0.0 && abs(profileTargetMgdl - targetUsedMgdl) > 0.01        -> TargetSource.ALGORITHM_ADJUSTED
        else                                                                           -> TargetSource.PROFILE
    }

    fun targetColor(source: TargetSource): Int = when (source) {
        TargetSource.TEMP_TARGET, TargetSource.ALGORITHM_ADJUSTED -> RIBBON_WARNING
        TargetSource.PROFILE                                      -> RIBBON_TEXT_DEFAULT
        TargetSource.NONE                                         -> WHITE
    }
}
