package app.aaps.plugins.aps.compose

import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.OapsProfileBoost
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import app.aaps.shared.tests.generatedTextResolver
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

internal class OpenAPSViewModelTest {

    @Mock private lateinit var apsPlugin: APS
    @Mock private lateinit var rxBus: RxBus
    @Mock private lateinit var dateUtil: DateUtil

    private val updateGuiFlow = MutableSharedFlow<EventOpenAPSUpdateGui>()
    private val resetGuiFlow = MutableSharedFlow<EventResetOpenAPSGui>()

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        whenever(rxBus.toFlow(EventOpenAPSUpdateGui::class)).thenReturn(updateGuiFlow)
        whenever(rxBus.toFlow(EventResetOpenAPSGui::class)).thenReturn(resetGuiFlow)
    }

    // Dispatchers.Unconfined runs the VM's launched coroutines (init updateState + onRefresh's
    // apsPlugin.invoke) eagerly and synchronously, so state and interactions settle in-line. The
    // never-completing toFlow subscriptions just park; a standalone scope has no leaked-coroutine check.
    // The resolver is the real one, not a mock. init's updateState() takes the no-result path and builds
    // OpenAPSUiState(statusMessage = rh.gs(...)); a mock answered that with null, which became a
    // non-null-param NPE inside the Unconfined init coroutine. The standalone scope has no leaked-coroutine
    // check, so it escaped this test and tripped "UncaughtExceptionsBeforeTest" in whatever runTest ran next
    // in the same fork. Real text cannot be null, so that whole hazard is gone.
    private fun viewModel() =
        OpenAPSViewModel(apsPlugin, rxBus, generatedTextResolver(), dateUtil, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun uiState_showsNotAvailable_whenNoApsResult() {
        whenever(apsPlugin.lastAPSResult).thenReturn(null)

        val sut = viewModel()

        assertThat(sut.uiState.value.statusMessage).isEqualTo("Not available")
        assertThat(sut.uiState.value.sections).isEmpty()
    }

    @Test
    fun onRefresh_setsRefreshingState() {
        whenever(apsPlugin.lastAPSResult).thenReturn(null)

        val sut = viewModel()
        sut.onRefresh()

        // isRefreshing is flipped synchronously; the subsequent apsPlugin.invoke(...) is a
        // fire-and-forget launch, not asserted here to keep the test deterministic.
        assertThat(sut.uiState.value.isRefreshing).isTrue()
    }
    @Test
    fun uiState_listsBoostProfile_whenResultCarriesOapsProfileBoost() {
        // A Boost result carries only oapsProfileBoost; before this the Profile section was left out.
        val result = mock<APSResult>()
        whenever(result.oapsProfileBoost).thenReturn(boostProfile())
        whenever { result.resultAsString() }.thenReturn("")
        whenever(apsPlugin.lastAPSResult).thenReturn(result)
        whenever(dateUtil.dateAndTimeString(any())).thenReturn("")

        val rows = viewModel().uiState.value.sections
            .single { it.title == ApsStrings.openapsma_profile_label }.rows.associate { it.key to it.value }

        assertThat(rows["max_iob"]).isEqualTo("8.0")
        assertThat(rows["boost_bolus"]).isEqualTo("2.1")
        assertThat(rows["insulinPeak"]).isEqualTo("38")
        // Fields with defaults are listed too, and a null field is left out.
        assertThat(rows["dynIsfMode"]).isEqualTo("true")
        assertThat(rows).doesNotContainKey("lgsThreshold")
    }

    private fun boostProfile() = OapsProfileBoost(
        dia = 10.0, min_5m_carbimpact = 8.0, max_iob = 8.0, max_daily_basal = 1.0, max_basal = 8.0,
        min_bg = 86.0, max_bg = 86.0, target_bg = 86.0, carb_ratio = 10.0, sens = 68.5,
        autosens_adjust_targets = false, max_daily_safety_multiplier = 3.0, current_basal_safety_multiplier = 4.0,
        high_temptarget_raises_sensitivity = false, low_temptarget_lowers_sensitivity = false,
        sensitivity_raises_target = false, resistance_lowers_target = false, adv_target_adjustments = false,
        exercise_mode = false, half_basal_exercise_target = 160, maxCOB = 120, skip_neutral_temps = false,
        remainingCarbsCap = 90, enableUAM = true, A52_risk_enable = false, SMBInterval = 5,
        enableSMB_with_COB = true, enableSMB_with_temptarget = false, allowSMB_with_high_temptarget = false,
        enableSMB_always = true, enableSMB_after_carbs = false, maxSMBBasalMinutes = 30, maxUAMSMBBasalMinutes = 30,
        bolus_increment = 0.05, carbsReqThreshold = 1, current_basal = 0.7, temptargetSet = false, autosens_max = 1.2,
        out_units = "mmol/L", lgsThreshold = null,
        variable_sens = 62.8, insulinDivisor = 75, TDD = 16.0,
        dynISFBgCap = 210.0, dynISFBgCapped = 159.0, sensNormalTarget = 68.5, normalTarget = 99.0, dynISFvelocity = 1.0,
        insulinPeak = 38,
        boostActive = true, profileSwitch = 100, boost_bolus = 2.1, boost_maxIOB = 8.0,
        Boost_InsulinReq = 90.0, boost_scale = 1.2,
        enableCircadianISF = false, allowBoost_with_high_temptarget = false,
        recentSteps5Minutes = 0, recentSteps15Minutes = 0, recentSteps30Minutes = 0, recentSteps60Minutes = 0,
        recentLowBG = 999.0, recentBrakingProduct = 0.0, dynIsfMode = true,
    )
}
