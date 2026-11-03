package app.aaps.ui.compose.overview.boost

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.notifications.AapsNotification
import app.aaps.core.interfaces.notifications.NotificationHandle
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.dialogs.OkCancelDialog
import app.aaps.core.ui.compose.dialogs.ThreeButtonDialog
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.compose.pump.PumpCommunicationStatus
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.compose.loopSheet.LoopActionBottomSheet
import app.aaps.ui.compose.loopSheet.LoopActionViewModel
import app.aaps.ui.compose.main.MainDrawer
import app.aaps.ui.compose.main.MainViewModel
import app.aaps.ui.compose.manageSheet.ManageSheetHost
import app.aaps.ui.compose.manageSheet.ManageViewModel
import app.aaps.ui.compose.notificationsSheet.NotificationBottomSheet
import app.aaps.ui.compose.notificationsSheet.NotificationFab
import app.aaps.ui.compose.overview.graphs.GraphViewModel
import app.aaps.ui.compose.scenesSheet.ScenesViewModel
import app.aaps.ui.compose.treatmentsSheet.TreatmentViewModel
import app.aaps.ui.search.M3SearchBar
import app.aaps.ui.search.SearchIndexEntry
import app.aaps.ui.search.SearchResults
import app.aaps.ui.search.SearchViewModel
import kotlinx.coroutines.launch

/**
 * The Boost V2 overview as a navigation destination: the screen, the main overview's drawer, its
 * notification button and sheet, and the dialogs its action buttons open.
 *
 * Every action reuses what the main overview already has. Navigation goes through [onNavigate], so
 * each treatment dialog keeps its own protection level. The button visibility comes from
 * [TreatmentViewModel], which reads the same `OverviewShow*Button` preferences as the main overview's
 * treatment sheet. Accept-temp opens the main overview's loop sheet and enacts through
 * [MainViewModel.performLoopAccept]. Automation user actions and QuickWizard entries go through
 * the same confirmation and execution path as the main overview's scenes and treatment sheets.
 */
@Composable
fun BoostOverviewHost(
    boostViewModel: BoostV2ViewModel,
    graphViewModel: GraphViewModel,
    mainViewModel: MainViewModel,
    treatmentViewModel: TreatmentViewModel,
    loopActionViewModel: LoopActionViewModel,
    scenesViewModel: ScenesViewModel,
    manageViewModel: ManageViewModel,
    searchViewModel: SearchViewModel,
    panelPlugins: List<PluginBase>,
    notificationManager: NotificationManager,
    pumpCommunicationStatus: PumpCommunicationStatus,
    onNavigate: (NavigationRequest) -> Unit,
    onBoostDetails: () -> Unit,
    onSearchResultClick: (SearchIndexEntry) -> Unit,
    onActionsError: (String, String) -> Unit,
    onNotificationActionClick: (AapsNotification) -> Unit
) {
    val treatmentState by treatmentViewModel.uiState.collectAsStateWithLifecycle()
    val loopState by loopActionViewModel.uiState.collectAsStateWithLifecycle()
    val scenesState by scenesViewModel.uiState.collectAsStateWithLifecycle()
    val pumpStatusBanner by pumpCommunicationStatus.statusBannerFlow.collectAsStateWithLifecycle()
    val notifications by notificationManager.notifications.collectAsStateWithLifecycle()
    val mainState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val searchState by searchViewModel.uiState.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var showNotificationSheet by rememberSaveable { mutableStateOf(false) }
    var showLoopActionSheet by rememberSaveable { mutableStateOf(false) }
    val manageSheetState = ManageSheetHost(
        manageViewModel = manageViewModel,
        isSimpleMode = mainState.isSimpleMode,
        onNavigate = onNavigate,
        onActionsError = onActionsError
    )

    // 3.4 listed a user action only while the loop was not suspended and the pump and profile were
    // ready; the scenes view model expresses the same conditions as a non-null activation reason.
    val actions = remember(treatmentState, loopState, scenesState) {
        BoostActionsState(
            showInsulin = treatmentState.showInsulin,
            showCalculator = treatmentState.showCalculator,
            showCarbs = treatmentState.showCarbs,
            showTreatment = treatmentState.showTreatment,
            showCalibration = treatmentState.showCalibration,
            showCgm = treatmentState.showCgm,
            isDexcomSource = treatmentState.isDexcomSource,
            acceptTempAvailable = loopState.actionAvailable,
            quickWizardItems = treatmentState.quickWizardItems
                .filter { it.isEnabled }
                .map { BoostQuickWizardButton(guid = it.guid, text = it.buttonText) },
            userActions = scenesState.items
                .filter { it.activationReason == null }
                .map { BoostUserActionButton(eventId = it.eventId, title = it.title, icon = it.firstActionIcon?.icon) }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MainDrawer(
                drawerState = drawerState,
                versionName = mainViewModel.versionName,
                // Closed before navigating, as in MainScreen: opened mid-slide, Back from that screen left the app.
                onNavigate = { request ->
                    scope.launch {
                        drawerState.close()
                        onNavigate(request)
                    }
                },
                isTreatmentsEnabled = mainState.isProfileLoaded,
                showExit = mainViewModel.showExit
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            BoostV2Route(
                viewModel = boostViewModel,
                graphViewModel = graphViewModel,
                pumpStatus = pumpStatusBanner?.text ?: "",
                actions = actions,
                modifier = Modifier.systemBarsPadding(),
                panelTabs = {
                    BoostPanelTabs(
                        plugins = panelPlugins,
                        isTreatmentsEnabled = mainState.isProfileLoaded,
                        actionsLabel = stringResource(CoreUiStrings.manage),
                        onActions = { manageSheetState.show() },
                        onElement = { element -> onNavigate(NavigationRequest.Element(element)) },
                        onPlugin = { plugin -> onNavigate(NavigationRequest.Plugin(plugin::class.simpleName.orEmpty())) }
                    )
                },
                searchActive = searchState.isSearchActive,
                searchBar = { searchModifier ->
                    M3SearchBar(
                        query = searchState.query,
                        isActive = searchState.isSearchActive,
                        onQueryChange = { searchViewModel.onQueryChanged(it) },
                        onClearClick = { searchViewModel.clearQuery() },
                        onActiveChange = { active ->
                            if (active) searchViewModel.onSearchModeActivated()
                            else searchViewModel.onSearchModeDeactivated()
                        },
                        modifier = searchModifier
                    )
                },
                searchResults = {
                    if (searchState.isSearchActive) {
                        SearchResults(
                            results = searchState.results,
                            wikiResults = searchState.wikiResults,
                            isSearching = searchState.isSearching,
                            isSearchingWiki = searchState.isSearchingWiki,
                            wikiOffline = searchState.wikiOffline,
                            revision = searchState.revision,
                            onResultClick = onSearchResultClick,
                            onPluginToggle = { plugin -> searchViewModel.togglePlugin(plugin) }
                        )
                    }
                },
                onMenu = { scope.launch { drawerState.open() } },
                onBoostDetails = onBoostDetails,
                onElement = { element -> onNavigate(NavigationRequest.Element(element)) },
                onAcceptTemp = { showLoopActionSheet = true },
                onQuickWizard = { guid -> onNavigate(NavigationRequest.QuickWizard(guid)) },
                onUserAction = { eventId -> mainViewModel.requestAutomationConfirmation(eventId) }
            )

            NotificationFab(
                notificationCount = notifications.size,
                highestLevel = notifications.minByOrNull { it.level.ordinal }?.level,
                onClick = { showNotificationSheet = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .systemBarsPadding()
                    .padding(end = 16.dp, bottom = 16.dp)
            )
        }
    }

    // Plugin enable/disable confirmations raised from search results, as on the main overview.
    searchState.pluginSwitchConfirmation?.let { confirmation ->
        OkCancelDialog(
            title = stringResource(CoreUiStrings.configbuilder_switch_confirmation_title),
            message = stringResource(CoreUiStrings.configbuilder_switch_confirmation, confirmation.fromName, confirmation.toName),
            onConfirm = { searchViewModel.confirmPluginSwitch() },
            onDismiss = { searchViewModel.dismissPluginSwitch() }
        )
    }
    searchState.hardwarePumpConfirmation?.let { confirmation ->
        OkCancelDialog(
            title = stringResource(CoreUiStrings.confirmation),
            message = confirmation.message,
            onConfirm = { searchViewModel.confirmHardwarePump() },
            onDismiss = { searchViewModel.dismissHardwarePump() }
        )
    }

    if (showNotificationSheet && notifications.isNotEmpty()) {
        NotificationBottomSheet(
            notifications = notifications,
            onDismissSheet = { showNotificationSheet = false },
            onDismissNotification = { notification -> notificationManager.dismiss(NotificationHandle(notification.instanceKey)) },
            onNotificationActionClick = onNotificationActionClick
        )
    }

    if (showLoopActionSheet) {
        LoopActionBottomSheet(
            state = loopState,
            onPerform = { mainViewModel.performLoopAccept() },
            onDismiss = { showLoopActionSheet = false }
        )
    }

    // The confirmation the main overview shows for automation actions, rendered here because the
    // main overview is not composed while this screen is open.
    val actionConfirmation by mainViewModel.actionConfirmation.collectAsStateWithLifecycle()
    actionConfirmation?.let { confirmation ->
        val secondaryAction = confirmation.secondaryAction
        val secondaryLabel = confirmation.secondaryLabel
        if (secondaryAction != null && secondaryLabel != null) {
            ThreeButtonDialog(
                title = confirmation.title,
                message = confirmation.message,
                icon = confirmation.icon,
                primaryLabel = confirmation.confirmLabel ?: stringResource(CoreUiStrings.ok),
                onPrimary = { mainViewModel.executeConfirmableAction(confirmation.onConfirmAction) },
                secondaryLabel = secondaryLabel,
                onSecondary = { mainViewModel.executeConfirmableAction(secondaryAction) },
                onDismiss = { mainViewModel.dismissActionConfirmation() }
            )
        } else {
            OkCancelDialog(
                title = confirmation.title,
                message = confirmation.message,
                icon = confirmation.icon,
                onConfirm = { mainViewModel.executeConfirmableAction(confirmation.onConfirmAction) },
                onDismiss = { mainViewModel.dismissActionConfirmation() }
            )
        }
    }
}
