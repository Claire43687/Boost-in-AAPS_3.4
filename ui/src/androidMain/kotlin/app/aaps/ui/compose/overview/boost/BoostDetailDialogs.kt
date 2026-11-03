package app.aaps.ui.compose.overview.boost

import androidx.compose.runtime.Composable
import app.aaps.core.ui.compose.stringResource
import app.aaps.core.interfaces.overview.BoostV5Brake
import app.aaps.core.ui.compose.dialogs.OkDialog
import app.aaps.ui.UiStrings
import java.util.Locale

/**
 * Identifies which tappable element opened an in-screen detail dialog.
 * PROFILE / TARGET / LOOP / INSULIN / CALCULATOR are hoisted navigation/bolus actions handled by
 * the host (Stage E) and are NOT represented here.
 */
enum class BoostV2Panel { IOB, DYNISF, TDD, V5, EXERCISE }

/**
 * Renders the detail dialog for the tapped [panel] (null → nothing shown).
 * Pure Compose — all content is built from [BoostUiState]; each dialog dismisses via [onDismiss].
 * Reproduces the content of the source `BoostOverviewV2Fragment` onClick handlers and `showV5Detail`.
 */
@Composable
fun BoostV2DetailDialog(
    panel: BoostV2Panel?,
    uiState: BoostUiState,
    onDismiss: () -> Unit
) {
    when (panel) {
        BoostV2Panel.IOB      -> IobDialog(uiState, onDismiss)
        BoostV2Panel.DYNISF   -> DynIsfDialog(uiState, onDismiss)
        BoostV2Panel.TDD      -> TddDialog(uiState, onDismiss)
        BoostV2Panel.V5       -> V5Dialog(uiState, onDismiss)
        BoostV2Panel.EXERCISE -> ExerciseDialog(uiState, onDismiss)
        null                  -> Unit
    }
}

@Composable
private fun IobDialog(s: BoostUiState, onDismiss: () -> Unit) {
    OkDialog(
        title = stringResource(UiStrings.boost_v2_dialog_iob_title),
        message = buildString {
            appendLine("Total: ${s.iobTotal}")
            appendLine("Bolus: ${s.iobBolus}U")
            append("Basal: ${s.iobBasal}U")
        },
        onDismiss = onDismiss
    )
}

@Composable
private fun DynIsfDialog(s: BoostUiState, onDismiss: () -> Unit) {
    OkDialog(
        title = stringResource(UiStrings.boost_v2_dialog_dynisf_title),
        message = buildString {
            appendLine("Dynamic ISF: ${withUnits(s.variableSensDisplay, s.glucoseUnits + "/U")}")   // glucose units per unit of insulin
            appendLine()
            appendLine("Algorithm inputs:")
            appendLine("  BG: ${withUnits(s.lastBgDisplay, s.glucoseUnits)}")
            appendLine("  TDD (weighted): ${s.tddWeighted}")
            if (s.insulinDivisor != "---") appendLine("  Insulin divisor: ${s.insulinDivisor}")
            appendLine()
            appendLine("TDD 7d avg: ${s.tdd7d}")
            append("TDD 24h: ${s.tdd24h}")
        },
        onDismiss = onDismiss
    )
}

@Composable
private fun TddDialog(s: BoostUiState, onDismiss: () -> Unit) {
    OkDialog(
        title = stringResource(UiStrings.boost_v2_dialog_tdd_title),
        message = buildString {
            appendLine("oapsProfile.TDD: ${s.tddWeighted}")
            appendLine("TDD from debug: ${s.tddFromDebug}")
            appendLine("TDD 7d avg: ${s.tdd7d}")
            append("TDD 24h: ${s.tdd24h}")
            appendScriptDebug(s.scriptDebugText)
        },
        onDismiss = onDismiss
    )
}

@Composable
private fun V5Dialog(s: BoostUiState, onDismiss: () -> Unit) {
    OkDialog(
        title = stringResource(UiStrings.boost_v2_dialog_v5_title),
        message = buildString {
            appendLine("State: ${s.v5State.label} (${s.v5State.verb}) · age ${s.v5AgeDisplay}")
            appendLine()
            appendLine("Meal score: ${s.v5ScoreDisplay}   (enter 0.44 · confirm 0.55)")
            appendLine("Action multiplier: ×${s.v5ActionMultDisplay}")
            appendLine("Aggression budget: ${s.v5BudgetDisplay}U")
            appendLine("Dose this cycle: ${s.v5DoseDisplay}U")
            appendLine()
            append("Brakes:")
            if (s.v5Brakes.isEmpty()) append("\n  none")
            else s.v5Brakes.forEach { append("\n  ${brakeLine(it)}") }
            appendLine()
            appendLine()
            append("Delta accel: ${s.deltaAccl}%")
            appendScriptDebug(s.scriptDebugText)
        },
        onDismiss = onDismiss
    )
}

@Composable
private fun ExerciseDialog(s: BoostUiState, onDismiss: () -> Unit) {
    OkDialog(
        title = stringResource(UiStrings.boost_v2_dialog_exercise_title),
        message = buildString {
            appendLine("Current: ${s.activityDetail}")
            appendLine()
            appendLine("Exercise target: ${withUnits(s.targetBgDisplay, s.glucoseUnits)}")
            appendLine()
            append("Profile: ${s.profilePercentage}%")
            appendScriptDebug(s.scriptDebugText)
        },
        onDismiss = onDismiss
    )
}

/** Appends the glucose-unit suffix to a formatted value, unless it is the "---" placeholder. */
private fun withUnits(value: String, units: String): String =
    if (value == "---") value else "$value $units"

private fun brakeLine(b: BoostV5Brake): String =
    if (b.isHard) "⛔ ${b.label} (hard disable)"
    else "${b.label}: ×${String.format(Locale.getDefault(), "%.2f", b.factor)}"

/** Appends the "--- Script Debug ---" trailer used by the TDD / V5 / Exercise dialogs. */
private fun StringBuilder.appendScriptDebug(debug: String) {
    appendLine()
    appendLine()
    appendLine("--- Script Debug ---")
    append(debug.ifBlank { "(no debug output)" }.take(2000))
}
