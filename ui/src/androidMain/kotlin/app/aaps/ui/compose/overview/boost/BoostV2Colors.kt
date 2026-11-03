package app.aaps.ui.compose.overview.boost

import androidx.compose.ui.graphics.Color
import app.aaps.core.interfaces.overview.ActivityMode
import app.aaps.core.ui.compose.StatusLevel

/**
 * V2-specific colour palette for the Boost overview screen.
 *
 * The V2 overview is a bespoke, always-dark card design (background `#0d0f14`, cyan accent).
 * `AapsTheme` / Material light-or-dark colours would clash with the hard-coded dark card
 * backgrounds, so the V2 look is preserved via this single palette object rather than scattering
 * `Color(0xFF…)` literals across the composables. Enum / [StatusLevel] / [ActivityMode] → colour
 * mapping is centralised in the helpers at the bottom of this file.
 *
 * Values copied verbatim from the live `boost_overview_v2_fragment.xml` layout and
 * `BoostOverviewV2Fragment` colour code so the port matches the original pixel-for-pixel.
 */
object BoostV2Colors {

    // Surfaces
    val background = Color(0xFF0D0F14)
    val cardBackground = Color(0xFF10121A)
    val stroke = Color(0xFF1A1D28)

    // Accent
    val accent = Color(0xFF00D4FF)          // cyan
    val accentFill = Color(0x1A00D4FF)      // 10% cyan — badge / action-button background
    val accentStroke = Color(0x2600D4FF)    // 15% cyan — action-button border

    // Text
    val textPrimary = Color(0xFFE8EAF0)
    val textSecondary = Color(0xFFAAAAAA)
    val labelGrey = Color(0xFF9AA0AD)

    // Semantic
    val green = Color(0xFF4CAF50)
    val amber = Color(0xFFFFB300)
    val red = Color(0xFFFF5252)
    val blue = Color(0xFF60A5FA)
    val purple = Color(0xFFC084FC)
    val orange = Color(0xFFFB923C)

    // Pill value colours (static per layout)
    val pillIob = blue
    val pillDynIsf = purple
    val pillTdd = orange
    val pillProfile = accent

    // BG-bobble 5-zone thresholds (mg/dL, TIR consensus)
    // Glucose colours are the 6.1.2 night theme (alarm, high, inRange, low), which the V2 fragment read from the theme.
    val bgUrgent = Color(0xFFFF4444)
    val bgHigh = Color(0xFFFFFF00)
    val bgInRange = Color(0xFF00FF00)
    val bgLow = Color(0xFFFF0000)

    /** Range-selector active/inactive backgrounds (see `updateRangeButtons`). */
    val rangeActiveFill = accentFill
    val rangeInactiveFill = Color.Transparent

    // ── V2 chart palette (rebuilt in Vico, mirrors BoostV2GraphData) ──────────────
    /** Chart canvas background (#0a0c10) — slightly darker than the card background. */
    val chartBackground = Color(0xFF0A0C10)

    /** In-range band fill — very subtle cyan tint, argb(12, 0, 212, 255). */
    val chartInRangeFill = Color(0x0C00D4FF)

    /** In-range upper border — cyan at 20 % opacity. */
    val chartInRangeUpperBorder = Color(0x3300D4FF)

    /** In-range lower border — red at 30 % opacity. */
    val chartInRangeLowerBorder = Color(0x4DFF5252)

    /** Target line — white at 15 %, argb(38, 255, 255, 255). */
    val chartTargetLine = Color(0x26FFFFFF)

    /** Now line — white at 10 %. */
    val chartNowLine = Color(0x1AFFFFFF)

    /** IOB line — blue #60a5fa. */
    val chartIobLine = blue

    /** IOB area fill — blue #60a5fa at 40 %. */
    val chartIobFill = Color(0x6660A5FA)

    /** Bolus and SMB markers on the BG graph (6.1.2 night theme `bolus`). */
    val chartBolus = Color(0xFF1EA3E5)

    // Prediction colours (6.1.2 night theme iob, cob, uam, zt). Absorbed COB is the COB colour, dimmed.
    val chartPredIob = Color(0xFF1E88E5)
    val chartPredCob = Color(0xFFFB8C00)
    val chartPredACob = Color(0x99FB8C00)
    val chartPredUam = Color(0xFFC9BD60)
    val chartPredZt = Color(0xFF00D2D2)

    /** Insulin activity on the BG graph (6.1.2 night theme `activity`). */
    val chartInsulinActivity = Color(0xFFD3F166)

    /** Carbs markers on the BG graph. */
    val chartCarbs = amber

    /** Basal line — cyan, the 6.1.2 night theme `basal`; V2 overrode only the fill. */
    val chartBasalLine = Color(0xFF00FFFF)

    /** Basal fill — orange #fb923c at 35 %. */
    val chartBasalFill = Color(0x59FB923C)

    /** Activity sub-graph steps line — lime green (matches the 4.0 activity series). */
    val chartActivityLine = Color(0xFFD3F166)

    /** Activity sub-graph steps area fill — lime green at 30 %. */
    val chartActivityFill = Color(0x4DD3F166)

    /** Activity sub-graph heart-rate line — pink/red, distinct from the lime steps line. */
    val chartHeartRateLine = Color(0xFFFF6B81)

    /** Regular (non-bucketed) BG reading outline. */
    val chartBgReading = Color(0xFFE8EAF0)

    /** Grid lines / axis guidelines (#1a1d28). */
    val chartGrid = stroke

    /** Axis label colour (#aaaaaa). */
    val chartAxisLabel = textSecondary
}

/** 5-zone BG ring colour by mg/dL. */
internal fun bgZoneColor(bgMgdl: Double): Color = when {
    bgMgdl > 250 -> BoostV2Colors.bgUrgent   // very high — urgent red
    bgMgdl > 180 -> BoostV2Colors.bgHigh      // high — orange
    bgMgdl >= 70 -> BoostV2Colors.bgInRange   // in range — green
    bgMgdl >= 54 -> BoostV2Colors.bgLow       // low — deep orange
    bgMgdl > 0   -> BoostV2Colors.bgUrgent    // very low — urgent red
    else         -> BoostV2Colors.bgInRange   // no reading — default green
}

/** Pump reservoir / battery / age warning level → V2 colour. */
internal fun statusLevelColorV2(level: StatusLevel): Color = when (level) {
    StatusLevel.NORMAL      -> BoostV2Colors.textPrimary
    StatusLevel.WARNING     -> BoostV2Colors.amber
    StatusLevel.CRITICAL    -> BoostV2Colors.red
    StatusLevel.UNSPECIFIED -> BoostV2Colors.textSecondary
}

/** AID (loop) status → indicator dot / text colour. */
internal fun aidStatusColorV2(status: AidStatus): Color = when (status) {
    AidStatus.ACTIVE    -> BoostV2Colors.green
    AidStatus.ATTENTION -> BoostV2Colors.amber
    AidStatus.ALERT     -> BoostV2Colors.red
    AidStatus.DISABLED  -> BoostV2Colors.labelGrey
}

/** Exercise / activity mode → label colour (matches `updateHeroRight`). */
internal fun activityModeColorV2(mode: ActivityMode): Color = when (mode) {
    ActivityMode.ACTIVE    -> BoostV2Colors.amber
    ActivityMode.INACTIVE  -> BoostV2Colors.blue
    ActivityMode.SLEEP_IN  -> BoostV2Colors.purple
    ActivityMode.BOOST_OFF -> BoostV2Colors.labelGrey
    ActivityMode.NORMAL    -> BoostV2Colors.textSecondary
}
