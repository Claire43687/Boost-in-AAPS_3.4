package app.aaps.ui.compose.overview.boost

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.overview.BoostV5Brake
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.icons.IcAutomation
import app.aaps.core.ui.compose.icons.IcCannulaChange
import app.aaps.core.ui.compose.icons.IcCgmInsert
import app.aaps.core.ui.compose.icons.IcPumpBattery
import app.aaps.core.ui.compose.navigation.icon
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.overview.graphs.GraphViewModel
import java.util.Locale

/**
 * Which treatment and action buttons the Boost screen shows under the chart.
 *
 * The six card buttons follow the same `OverviewShow*Button` preferences as the main overview, so
 * they are switched in the same settings screen (Boost 6.1.2, commit 3306b3e387). The row under them
 * carries what the 3.4 fragment showed in its included standard button layout: the accept-temp
 * button, the QuickWizard entries and the automation user actions.
 */
@Immutable
data class BoostActionsState(
    val showInsulin: Boolean = true,
    val showCalculator: Boolean = true,
    val showCarbs: Boolean = false,
    val showTreatment: Boolean = false,
    val showCalibration: Boolean = false,
    val showCgm: Boolean = false,
    /** The CGM button opens the Dexcom app instead of xDrip+ when the BG source is Dexcom (BYODA). */
    val isDexcomSource: Boolean = false,
    val acceptTempAvailable: Boolean = false,
    val quickWizardItems: List<BoostQuickWizardButton> = emptyList(),
    val userActions: List<BoostUserActionButton> = emptyList()
)

/** One enabled QuickWizard entry. */
@Immutable
data class BoostQuickWizardButton(val guid: String, val text: String)

/** One runnable automation user action. */
@Immutable
data class BoostUserActionButton(val eventId: String, val title: String, val icon: ImageVector?)

/**
 * Boost V2 overview, a stateless Compose port of `boost_overview_v2_fragment.xml` and
 * `BoostOverviewV2Fragment`. Renders the bespoke dark card layout: top nav bar, three-column hero
 * (pump / BG bobble / reading), four stat pills, the V5 decision strip, a chart slot ([chartContent]),
 * a time-range selector, the action buttons, and the pump-status and calc-progress footers.
 *
 * Info dialogs (IOB / DynISF / TDD / V5 / Exercise) are handled in-screen via [BoostV2Panel].
 * Bolus and navigation actions are hoisted lambdas, wired by the host.
 */
@Composable
fun BoostV2Screen(
    uiState: BoostUiState,
    pumpStatus: String,
    actions: BoostActionsState,
    modifier: Modifier = Modifier,
    chartContent: @Composable () -> Unit = {},
    panelTabs: @Composable () -> Unit = {},
    searchActive: Boolean = false,
    searchBar: @Composable (Modifier) -> Unit = {},
    searchResults: @Composable () -> Unit = {},
    onMenu: () -> Unit = {},
    onBoostDetails: () -> Unit = {},
    onElement: (ElementType) -> Unit = {},
    onAcceptTemp: () -> Unit = {},
    onQuickWizard: (String) -> Unit = {},
    onUserAction: (String) -> Unit = {}
) {
    // Which detail dialog (if any) is open — pure in-screen state.
    var activePanel: BoostV2Panel? by remember { mutableStateOf(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BoostV2Colors.background)
    ) {
        TopNavBar(
            uiState,
            searchActive = searchActive,
            searchBar = searchBar,
            onMenu = onMenu,
            onBoostDetails = onBoostDetails,
            onSettings = { onElement(ElementType.SETTINGS) },
            onLoop = { onElement(ElementType.RUNNING_MODE) }
        )
        // The results cover everything under the header, as on the main overview, and the chart stays composed.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxSize()) {
                panelTabs()

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp)
                ) {
                    HeroSection(
                        uiState = uiState,
                        onTempTarget = { onElement(ElementType.TEMP_TARGET_MANAGEMENT) },
                        onExercise = { activePanel = BoostV2Panel.EXERCISE }
                    )

                    StatPillsRow(
                        uiState = uiState,
                        onIob = { activePanel = BoostV2Panel.IOB },
                        onDynIsf = { activePanel = BoostV2Panel.DYNISF },
                        onTdd = { activePanel = BoostV2Panel.TDD },
                        onProfile = { onElement(ElementType.PROFILE_MANAGEMENT) }
                    )

                    if (uiState.isV5Active) {
                        Spacer(Modifier.height(6.dp))
                        V5Strip(uiState, onClick = { activePanel = BoostV2Panel.V5 })
                    }

                    Spacer(Modifier.height(6.dp))
                    // Chart slot, called unconditionally so the layout is stable.
                    chartContent()

                    Spacer(Modifier.height(12.dp))
                    OverviewActions(
                        actions = actions,
                        onElement = onElement,
                        onAcceptTemp = onAcceptTemp,
                        onQuickWizard = onQuickWizard,
                        onUserAction = onUserAction
                    )
                    Spacer(Modifier.height(12.dp))
                }

                // Footers pinned below the scroll (mirrors the source layout). A running bolus is shown by
                // the shared bolus progress overlay above every screen, so the status line is not clickable.
                if (pumpStatus.isNotBlank()) {
                    Text(
                        text = pumpStatus,
                        color = BoostV2Colors.textPrimary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BoostV2Colors.cardBackground)
                            .padding(vertical = 6.dp)
                    )
                }
                if (uiState.calcProgressPct != 100) {
                    LinearProgressIndicator(
                        progress = { uiState.calcProgressPct / 100f },
                        color = BoostV2Colors.accent,
                        trackColor = BoostV2Colors.stroke,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp)
                            .height(4.dp)
                    )
                }
            }
            searchResults()
        }
    }

    BoostV2DetailDialog(panel = activePanel, uiState = uiState, onDismiss = { activePanel = null })
}

// ── Top nav bar ───────────────────────────────────────────────────────────────

@Composable
private fun TopNavBar(uiState: BoostUiState, searchActive: Boolean, searchBar: @Composable (Modifier) -> Unit, onMenu: () -> Unit, onBoostDetails: () -> Unit, onSettings: () -> Unit, onLoop: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        IconButton(onClick = onMenu) {
            Icon(Icons.Default.Menu, contentDescription = stringResource(CoreUiStrings.open_navigation), tint = BoostV2Colors.textPrimary)
        }
        // The space beside the badge and AID status is too narrow to type in, so they give way while searching.
        if (!searchActive) {
            // BOOST badge → the active APS plugin's page, the V6 tab on 6.1.2
            Text(
                text = "${stringResource(UiStrings.boost_v2_badge)} ›",
                color = BoostV2Colors.accent,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                modifier = Modifier
                    .background(BoostV2Colors.accentFill)
                    .clickable(onClickLabel = stringResource(UiStrings.boost_v2_details)) { onBoostDetails() }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        searchBar(Modifier.weight(1f).padding(horizontal = 8.dp))
        if (!searchActive) {
            // AID status + dot → loop dialog
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onLoop() }
            ) {
                Text(
                    text = uiState.aidStatusText,
                    color = BoostV2Colors.textPrimary,
                    fontSize = 11.sp
                )
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier
                        .size(6.dp)
                        .background(aidStatusColorV2(uiState.aidStatus))
                )
            }
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Default.Settings, contentDescription = stringResource(CoreUiStrings.settings), tint = BoostV2Colors.textPrimary)
        }
    }
}

// ── Hero section ────────────────────────────────────────────────────────────────

@Composable
private fun HeroSection(uiState: BoostUiState, onTempTarget: () -> Unit, onExercise: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 8.dp)
    ) {
        // Left: pump column
        PumpColumn(uiState, modifier = Modifier.weight(1f))

        // Centre: BG bobble + delta
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BoostBgBobble(uiState = uiState, size = 140.dp)
            if (uiState.deltaDisplay.isNotBlank()) {
                Text(
                    text = uiState.deltaDisplay,
                    color = BoostV2Colors.textSecondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // Right: reading / target / exercise
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f)
        ) {
            CaptionLabel(stringResource(UiStrings.boost_v2_reading))
            Text(uiState.timeAgoDisplay, color = BoostV2Colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)

            Spacer(Modifier.height(8.dp))
            CaptionLabel(stringResource(UiStrings.boost_v2_target))
            Text(
                text = uiState.targetDisplay,
                color = if (uiState.targetIsTempTarget) BoostV2Colors.amber else BoostV2Colors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.clickable { onTempTarget() }
            )
            Text(
                text = uiState.activityDetail,
                color = activityModeColorV2(uiState.activityMode),
                fontSize = 11.sp,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable { onExercise() }
            )
        }
    }
}

@Composable
private fun PumpColumn(uiState: BoostUiState, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        CaptionLabel(stringResource(UiStrings.boost_v2_pump))
        // Reservoir — cartridge icon + large value.
        val reservoirColor = statusLevelColorV2(uiState.reservoirLevel)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            Text(
                text = uiState.reservoirDisplay,
                color = reservoirColor,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp
            )
        }
        PumpStatLine(IcPumpBattery, uiState.batteryDisplay, statusLevelColorV2(uiState.batteryLevel))
        PumpStatLine(IcCannulaChange, uiState.cannulaAgeDisplay, statusLevelColorV2(uiState.cannulaAgeLevel))
        PumpStatLine(IcCgmInsert, uiState.sensorAgeDisplay, statusLevelColorV2(uiState.sensorAgeLevel))
    }
}

@Composable
private fun PumpStatLine(icon: ImageVector, value: String, valueColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = BoostV2Colors.labelGrey,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(value, color = valueColor, fontSize = 12.sp)
    }
}

@Composable
private fun CaptionLabel(text: String) {
    Text(text = text, color = BoostV2Colors.labelGrey, fontSize = 10.sp, letterSpacing = 1.sp)
}

// ── Stat pills ──────────────────────────────────────────────────────────────────

@Composable
private fun StatPillsRow(
    uiState: BoostUiState,
    onIob: () -> Unit,
    onDynIsf: () -> Unit,
    onTdd: () -> Unit,
    onProfile: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp)
    ) {
        StatPill(stringResource(UiStrings.boost_v2_iob), uiState.iobTotal, BoostV2Colors.pillIob, onClick = onIob, modifier = Modifier.weight(1f))
        StatPill(stringResource(UiStrings.boost_v2_dynisf), uiState.dynIsfDisplay, BoostV2Colors.pillDynIsf, onClick = onDynIsf, modifier = Modifier.weight(1f))
        StatPill(stringResource(UiStrings.boost_v2_tdd), uiState.tddDisplay, BoostV2Colors.pillTdd, onClick = onTdd, modifier = Modifier.weight(1f))
        StatPill(
            label = stringResource(UiStrings.boost_v2_profile),
            value = uiState.profileName,
            valueColor = BoostV2Colors.pillProfile,
            valueSize = 11.sp,
            subtitle = "${uiState.profilePct}%",
            subtitleColor = if (uiState.profileModified) BoostV2Colors.amber else BoostV2Colors.accent,
            onClick = onProfile,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatPill(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
    valueSize: androidx.compose.ui.unit.TextUnit = 15.sp,
    subtitle: String? = null,
    subtitleColor: Color = BoostV2Colors.textSecondary,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(BoostV2Colors.cardBackground)
            .border(1.dp, BoostV2Colors.stroke, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(8.dp)
    ) {
        CaptionLabel(label)
        Text(value, color = valueColor, fontWeight = FontWeight.Bold, fontSize = valueSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) {
            Text(subtitle, color = subtitleColor, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
        }
    }
}

// ── V5 strip ────────────────────────────────────────────────────────────────────

@Composable
private fun V5Strip(uiState: BoostUiState, onClick: () -> Unit) {
    val stateColor = Color(uiState.v5State.colorHex)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BoostV2Colors.cardBackground)
            .border(1.dp, BoostV2Colors.stroke, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(10.dp)
    ) {
        // Row 1: state · mult · age  +  dose / budget
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${uiState.v5State.label.uppercase(Locale.getDefault())}  ×${uiState.v5ActionMultDisplay} · ${uiState.v5AgeDisplay}",
                color = stateColor,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                letterSpacing = 0.5.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "dose ${uiState.v5DoseDisplay}U · budget ${uiState.v5BudgetDisplay}U",
                color = BoostV2Colors.textSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.End
            )
        }

        // Row 2: meal-score gauge (0..1), tinted by state colour
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
        ) {
            CaptionLabel(stringResource(UiStrings.boost_v2_score))
            LinearProgressIndicator(
                progress = { uiState.v5Score.toFloat().coerceIn(0f, 1f) },
                color = stateColor,
                trackColor = BoostV2Colors.stroke,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .height(6.dp)
            )
            Text(uiState.v5ScoreDisplay, color = BoostV2Colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }

        // Row 3: active brakes (hidden when none)
        if (uiState.v5Brakes.isNotEmpty()) {
            val hasHard = uiState.v5Brakes.any { it.isHard }
            Text(
                text = uiState.v5Brakes.joinToString("   ·   ") { brakeStripText(it) },
                color = if (hasHard) BoostV2Colors.red else BoostV2Colors.orange,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

private fun brakeStripText(b: BoostV5Brake): String =
    if (b.isHard) "⛔ ${b.label}"
    else "${b.label} ${String.format(Locale.getDefault(), "%.2f", b.factor)}"

// ── Range selector ──────────────────────────────────────────────────────────────

@Composable
internal fun RangeSelector(rangeHours: Int, onSetRange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        listOf(3, 6, 12, 24).forEach { h ->
            val selected = h == rangeHours
            Text(
                text = "${h}h",
                color = if (selected) BoostV2Colors.accent else BoostV2Colors.textSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (selected) BoostV2Colors.rangeActiveFill else BoostV2Colors.rangeInactiveFill)
                    .clickable { onSetRange(h) }
                    .width(38.dp)
                    .padding(vertical = 4.dp)
            )
        }
    }
}

// ── Action buttons ──────────────────────────────────────────────────────────────

private data class OverviewButton(val label: String, val icon: ImageVector?, val onClick: () -> Unit)

/**
 * All overview buttons in one grid, in the 3.4 order: card buttons, then accept-temp, QuickWizard and
 * automation user actions. A new row starts only when the row above holds three, and every row
 * fills the width, so a short row has wider buttons.
 */
@Composable
private fun OverviewActions(
    actions: BoostActionsState,
    onElement: (ElementType) -> Unit,
    onAcceptTemp: () -> Unit,
    onQuickWizard: (String) -> Unit,
    onUserAction: (String) -> Unit
) {
    @Composable
    fun cardButton(label: String, element: ElementType) = OverviewButton(label, element.icon()) { onElement(element) }

    val buttons = buildList {
        if (actions.showInsulin) add(cardButton(stringResource(UiStrings.boost_v2_insulin), ElementType.INSULIN))
        if (actions.showCalculator) add(cardButton(stringResource(UiStrings.boost_v2_calculator), ElementType.BOLUS_WIZARD))
        if (actions.showCarbs) add(cardButton(stringResource(UiStrings.boost_v2_carbs), ElementType.CARBS))
        if (actions.showTreatment) add(cardButton(stringResource(UiStrings.boost_v2_treatment), ElementType.TREATMENT))
        if (actions.showCalibration) add(cardButton(stringResource(UiStrings.boost_v2_calibration), ElementType.CALIBRATION))
        if (actions.showCgm)
            add(cardButton(stringResource(UiStrings.boost_v2_cgm), if (actions.isDexcomSource) ElementType.CGM_DEX else ElementType.CGM_XDRIP))
        if (actions.acceptTempAvailable) add(OverviewButton(stringResource(UiStrings.boost_v2_accept_temp), null, onAcceptTemp))
        actions.quickWizardItems.forEach { item ->
            add(OverviewButton(item.text, ElementType.QUICK_WIZARD.icon()) { onQuickWizard(item.guid) })
        }
        actions.userActions.forEach { item ->
            add(OverviewButton(item.title, item.icon ?: IcAutomation) { onUserAction(item.eventId) })
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        buttons.chunked(BUTTONS_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { button ->
                    ActionButton(label = button.label, icon = button.icon, onClick = button.onClick, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(BoostV2Colors.accentFill)
            .border(1.dp, BoostV2Colors.accentStroke, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 14.dp)
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = BoostV2Colors.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = BoostV2Colors.accent, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private const val BUTTONS_PER_ROW = 3

// ── Route (stateful entry point) ─────────────────────────────────────────────────

/**
 * Thin stateful wrapper: collects [BoostV2ViewModel.uiState] and [BoostV2ViewModel.rangeToDisplayHours]
 * and forwards the hoisted action lambdas to [BoostV2Screen].
 *
 * The [BoostV2ViewModel.chartFaithful] preference picks the [BoostChartStyle], set to V2 on open, and the
 * header toggle flips it live via [BoostV2ViewModel.setChartFaithful]. [graphViewModel] is the one
 * the main overview uses, created by the host. Opening Treatments from a tap on the V2 chart goes
 * through [onElement] like every other navigation, so it is protection-checked the same way.
 */
@Composable
fun BoostV2Route(
    viewModel: BoostV2ViewModel,
    graphViewModel: GraphViewModel,
    pumpStatus: String,
    actions: BoostActionsState,
    modifier: Modifier = Modifier,
    panelTabs: @Composable () -> Unit = {},
    searchActive: Boolean = false,
    searchBar: @Composable (Modifier) -> Unit = {},
    searchResults: @Composable () -> Unit = {},
    onMenu: () -> Unit = {},
    onBoostDetails: () -> Unit = {},
    onElement: (ElementType) -> Unit = {},
    onAcceptTemp: () -> Unit = {},
    onQuickWizard: (String) -> Unit = {},
    onUserAction: (String) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val rangeHours by viewModel.rangeToDisplayHours.collectAsStateWithLifecycle()
    val chartFaithful by viewModel.chartFaithful.collectAsStateWithLifecycle()
    val chartStyle = if (chartFaithful) BoostChartStyle.V2 else BoostChartStyle.STANDARD

    // The overview always opens on the V2 chart. The toggle still switches style until the next open.
    LaunchedEffect(Unit) { viewModel.setChartFaithful(true) }

    BoostV2Screen(
        uiState = uiState,
        pumpStatus = pumpStatus,
        actions = actions,
        modifier = modifier,
        chartContent = {
            BoostChart(
                style = chartStyle,
                graphViewModel = graphViewModel,
                rangeHours = rangeHours,
                onSetRange = viewModel::setRange,
                onTreatments = { onElement(ElementType.TREATMENTS) },
                onSelectStyle = { viewModel.setChartFaithful(it == BoostChartStyle.V2) }
            )
        },
        panelTabs = panelTabs,
        searchActive = searchActive,
        searchBar = searchBar,
        searchResults = searchResults,
        onMenu = onMenu,
        onBoostDetails = onBoostDetails,
        onElement = onElement,
        onAcceptTemp = onAcceptTemp,
        onQuickWizard = onQuickWizard,
        onUserAction = onUserAction
    )
}
