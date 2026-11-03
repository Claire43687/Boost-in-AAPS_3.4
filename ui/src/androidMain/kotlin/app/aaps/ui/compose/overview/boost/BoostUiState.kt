package app.aaps.ui.compose.overview.boost

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.overview.ActivityMode
import app.aaps.core.interfaces.overview.BoostTier
import app.aaps.core.interfaces.overview.BoostV5Brake
import app.aaps.core.interfaces.overview.BoostV5State
import app.aaps.core.ui.compose.StatusLevel

/**
 * AID (loop) status classification for the top-right indicator dot.
 * The Compose layer maps each value to a theme colour (green / amber / red / grey).
 */
enum class AidStatus {
    ACTIVE,     // AID ON — closed loop running (green)
    ATTENTION,  // OPEN / S.BOLUS — needs user action (amber)
    ALERT,      // PAUSED / DISC / NO AID — not delivering (red)
    DISABLED    // OFF — loop disabled (grey)
}

/**
 * Display-ready state for the Boost V2 overview screen.
 *
 * All numeric values are pre-formatted [String]s; classification lives in the domain enums
 * ([BoostTier] / [BoostV5State] / [ActivityMode]) so the Compose layer can map them to theme
 * colours. Two convenience [Color] slots (profile / target) are kept from the preview version;
 * new code should prefer classifying via the enums and theming in the UI layer.
 *
 * Extends the AAPS-v4-preview BoostUiState to cover the full V2 data set:
 *  - pump column (reservoir / battery / cannula & sensor age)
 *  - hero row (bg / delta / time-ago / target / exercise)
 *  - four pills (iob / dynisf / tdd / profile)
 *  - V5 strip (state / score / mult / age / dose / budget / brakes)
 *  - dialog-detail fields.
 */
@Immutable
data class BoostUiState(
    // ── Tier ──
    val tier: BoostTier = BoostTier.INACTIVE,
    val tierLabel: String = "Inactive",
    val tierReason: String = "",
    val fastCarbProtection: Boolean = false,

    // ── Pump column ──
    val reservoirDisplay: String = "---",
    val batteryDisplay: String = "---",
    val cannulaAgeDisplay: String = "---",
    val sensorAgeDisplay: String = "---",
    // Warn levels — Compose maps each to a theme colour (UNSPECIFIED = no tint)
    val reservoirLevel: StatusLevel = StatusLevel.UNSPECIFIED,
    val batteryLevel: StatusLevel = StatusLevel.UNSPECIFIED,
    val cannulaAgeLevel: StatusLevel = StatusLevel.UNSPECIFIED,
    val sensorAgeLevel: StatusLevel = StatusLevel.UNSPECIFIED,

    // ── Hero row ──
    val bgDisplay: String = "---",
    /** Raw BG in mg/dL — drives the bobble's 5-zone ring colour (0 = no reading). */
    val bgValueMgdl: Double = 0.0,
    val deltaDisplay: String = "---",
    val timeAgoDisplay: String = "---",
    val exerciseDisplay: String = "Normal",
    val isActualBg: Boolean = true,
    val trendArrow: TrendArrow = TrendArrow.NONE,

    // ── AID (loop) status indicator ──
    val aidStatusText: String = "AID ON",
    val aidStatus: AidStatus = AidStatus.ACTIVE,

    // ── Calc progress ──
    val calcProgressPct: Int = 100,

    // ── IOB pill ──
    val iobTotal: String = "---",
    val iobBolus: String = "0.00",
    val iobBasal: String = "0.00",

    // ── DynISF pill ──
    val dynIsfDisplay: String = "---",
    val variableSensDisplay: String = "---",

    // ── TDD pill ──
    val tddDisplay: String = "---",
    val tddWeighted: String = "---",
    val tddFromDebug: String = "---",
    val tdd7d: String = "---",
    val tdd24h: String = "---",

    // ── Profile pill ──
    val profileName: String = "---",
    val profilePct: Int = 100,
    val profileModified: Boolean = false,
    val profileColor: Color = Color(0xFF4CAF50),

    // ── Target ──
    val targetDisplay: String = "---",
    val targetIsTempTarget: Boolean = false,
    val targetColor: Color = Color(0xFF4CAF50),

    // ── Activity / Exercise ──
    val activityMode: ActivityMode = ActivityMode.NORMAL,
    val activityDetail: String = "Normal",

    // ── Boost V5 strip ──
    val isV5Active: Boolean = false,
    val v5State: BoostV5State = BoostV5State.IDLE,
    /** Raw meal score 0..1 — drives the score progress bar fill. */
    val v5Score: Double = 0.0,
    val v5ScoreDisplay: String = "---",
    val v5ActionMultDisplay: String = "---",
    val v5AgeDisplay: String = "---",
    val v5DoseDisplay: String = "---",
    val v5BudgetDisplay: String = "---",
    val v5Brakes: List<BoostV5Brake> = emptyList(),
    val v5GateRaw: String = "",

    /** Profile glucose units label ("mg/dL" / "mmol/L") for dialog value suffixes. */
    val glucoseUnits: String = "mg/dL",

    // ── Algorithm details for dialogs ──
    val deltaAccl: String = "0.0",
    val insulinDivisor: String = "---",
    val lastBgDisplay: String = "---",
    val autosensRatio: String = "1.0",
    val profilePercentage: Int = 100,
    val targetBgDisplay: String = "---",
    val scriptDebugText: String = "",

    // Deviation-based sensitivity (dialog detail)
    val deviationSensRatio: String = "---",
    val deviationSensSource: String = "none",
    val deviationSensClean: Int = 0,
    val deviationSensTotal: Int = 0,
    val sensNormalTarget: String = "---",
)
