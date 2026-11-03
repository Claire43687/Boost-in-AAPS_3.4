package app.aaps.ui.compose.overview.boost

import app.aaps.core.interfaces.overview.ActivityMode
import app.aaps.core.interfaces.overview.BoostTier
import app.aaps.core.interfaces.overview.BoostV5Brake
import app.aaps.core.interfaces.overview.BoostV5State

/**
 * Raw (un-formatted) Boost algorithm status extracted from the last APS run.
 * Produced by [BoostOverviewDataProvider]; contains only domain values (no colours / no
 * pre-formatted strings). Field set mirrors the live BoostOverviewHelper.BoostStatus source.
 */
data class BoostStatus(
    val tier: BoostTier = BoostTier.INACTIVE,
    val tierLabel: String = "Inactive",
    val tierReason: String = "",
    val dynIsfValue: Double = 0.0,
    val tdd7d: Double = 0.0,
    val tdd24h: Double = 0.0,
    val tddWeighted: Double = 0.0,
    val tddFromDebug: Double = 0.0,
    val insulinDivisor: Int = 0,
    val lastBg: Double = 0.0,
    val activityMode: ActivityMode = ActivityMode.NORMAL,
    val activityDetail: String = "Normal",
    val targetBgMgdl: Double = 0.0,
    val autosensRatio: Double = 1.0,
    val profilePercentage: Int = 100,
    val deltaAccl: Double = 0.0,
    val variableSens: Double = 0.0,
    val scriptDebugText: String = "",
    val cannulaAgeDays: Double = -1.0,
    val sensorAgeDays: Double = -1.0,
    val fastCarbProtection: Boolean = false,
    // Deviation-based sensitivity adjustment
    val deviationSensRatio: Double? = null,
    val deviationSensSource: String = "none",
    val deviationSensClean: Int = 0,
    val deviationSensTotal: Int = 0,
    val sensNormalTarget: Double = 0.0,
    // Boost V5 state-machine telemetry (populated only when V5 is the active APS)
    val isV5Active: Boolean = false,
    val v5State: BoostV5State = BoostV5State.IDLE,
    val v5Age: Int = 0,
    val v5Score: Double = 0.0,
    val v5ActionMult: Double = 1.0,
    val v5Budget: Double = 0.0,
    val v5FinalDose: Double = 0.0,
    val v5Brakes: List<BoostV5Brake> = emptyList(),
    val v5GateRaw: String = "",
)
