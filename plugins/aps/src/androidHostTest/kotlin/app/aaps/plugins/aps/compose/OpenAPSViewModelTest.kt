package app.aaps.plugins.aps.compose

import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.OapsProfileBoost
import app.aaps.core.interfaces.aps.RT
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

    @Test
    fun uiState_listsAutoConfigBreadcrumbs_asRows() {
        // Shaped like a 4.0 Boost reason, shortened. zz is a code with no description, shown as written.
        val reason = "COB: 0; autocfg=applied=0,keptUser=1@2026-01-01T08:00:00.000Z,tr=KEPT_USER_TUNED/d=0.35/kept:user-set,ndw=PROFILE_CHANGED; " +
            "autordv=@2026-01-08T08:00:00.000Z,win=28d,held-CommittedCapU:2.35,aec=KEPT_USER_TUNED/zz=1; sleep=AWAKE; "
        val result = mock<APSResult>()
        whenever(result.rawData()).thenReturn(RT(runningDynamicIsf = false, reason = StringBuilder(reason)))
        whenever { result.resultAsString() }.thenReturn("")
        whenever(apsPlugin.lastAPSResult).thenReturn(result)
        whenever(dateUtil.fromISODateString("2026-01-08T08:00:00.000Z")).thenReturn(1_000L)
        whenever(dateUtil.dateAndTimeString(any())).thenReturn("8 Jan 08:00")

        val section = viewModel().uiState.value.sections.single { it.title == ApsStrings.openapsma_autoconfig_label }

        assertThat(section.collapsedByDefault).isTrue()
        assertThat(section.rows).containsExactly(
            KeyValueRow("", "First set-up"),
            KeyValueRow("Settings applied", "0"),
            KeyValueRow("Settings kept as you set them", "1"),
            KeyValueRow("Run at", "8 Jan 08:00"),
            KeyValueRow("Confirm-tranche threshold", "kept user tuned"),
            KeyValueRow("   Derived from your episodes", "0.35"),
            KeyValueRow("   Reason", "kept user set"),
            KeyValueRow("Insulin-need watch", "profile changed"),
            KeyValueRow("", "Latest re-derivation"),
            KeyValueRow("Run at", "8 Jan 08:00"),
            KeyValueRow("History window", "28d"),
            KeyValueRow("Suggestion held back: CommittedCapU", "2.35"),
            KeyValueRow("Aggressive early confirm", "kept user tuned"),
            KeyValueRow("   zz", "1")
        ).inOrder()
    }

    @Test
    fun uiState_hasNoAutoConfigSection_whenReasonCarriesNoBreadcrumb() {
        val result = mock<APSResult>()
        whenever(result.rawData()).thenReturn(RT(runningDynamicIsf = false, reason = StringBuilder("COB: 0; sleep=AWAKE; ")))
        whenever { result.resultAsString() }.thenReturn("")
        whenever(apsPlugin.lastAPSResult).thenReturn(result)
        whenever(dateUtil.dateAndTimeString(any())).thenReturn("")

        assertThat(viewModel().uiState.value.sections.map { it.title }).doesNotContain(ApsStrings.openapsma_autoconfig_label)
    }

    private fun heartRateRows(profile: OapsProfileBoost): List<KeyValueRow> {
        val result = mock<APSResult>()
        whenever(result.oapsProfileBoost).thenReturn(profile)
        whenever { result.resultAsString() }.thenReturn("")
        whenever(apsPlugin.lastAPSResult).thenReturn(result)
        whenever(dateUtil.dateAndTimeString(any())).thenReturn("")
        return viewModel().uiState.value.sections.single { it.title == ApsStrings.hr_section_label }.rows
    }

    @Test
    fun uiState_heartRateSection_showsTheReadingInWords() {
        val rows = heartRateRows(boostProfile().apply {
            hr_integration = true; hr_avg_bpm = 91.6; hr_reserve_pct = 26.4; hr_zone = "zone1"
            hr_state = "MODERATE_AEROBIC"; hr_confidence = "MEDIUM"; hr_resting_bpm = 58; hr_resting_learned = true
            hr_max_bpm = 180; hr_window_min = 15; activity_state = "activity"; hr_summary = "HR: avg=91.6 bpm"
        })

        assertThat(rows).containsExactly(
            KeyValueRow("Average over 15 min", "92 bpm"),
            KeyValueRow("Share of heart-rate reserve", "26%"),
            KeyValueRow("Zone", "zone1"),
            KeyValueRow("Heart rate and steps read as", "moderate aerobic, medium confidence"),
            KeyValueRow("Resting rate (learned)", "58 bpm"),
            KeyValueRow("Maximum rate", "180 bpm"),
            KeyValueRow("Activity state this cycle", "activity"),
            KeyValueRow("Details", "HR: avg=91.6 bpm")
        ).inOrder()
        // The hr_ fields are not repeated in the Profile section.
        val profileKeys = viewModel().uiState.value.sections.single { it.title == ApsStrings.openapsma_profile_label }.rows.map { it.key }
        assertThat(profileKeys.filter { it.startsWith("hr_") }).isEmpty()
    }

    @Test
    fun uiState_heartRateSection_saysWhenThereIsNoUsableReading() {
        val rows = heartRateRows(boostProfile().apply {
            hr_integration = true; hr_zone = "none"; hr_resting_bpm = 60; hr_max_bpm = 180; hr_window_min = 15
        })

        assertThat(rows.first()).isEqualTo(KeyValueRow("", "No usable heart rate in the last 15 min. Activity is judged from steps only."))
        assertThat(rows).contains(KeyValueRow("Resting rate (setting)", "60 bpm"))
        assertThat(rows.map { it.key }).doesNotContain("Zone")
    }

    @Test
    fun uiState_heartRateSection_saysWhenIntegrationIsOff() {
        val rows = heartRateRows(boostProfile())

        assertThat(rows).containsExactly(KeyValueRow("", "Heart-rate integration is off. Activity is judged from steps only."))
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
        Boost_InsulinReq = 90.0, boost_scale = 1.2, boost_percent_scale = 200.0,
        enableBoostPercentScale = true, enableCircadianISF = false, allowBoost_with_high_temptarget = false,
        recentSteps5Minutes = 0, recentSteps15Minutes = 0, recentSteps30Minutes = 0, recentSteps60Minutes = 0,
        recentLowBG = 999.0, recentBrakingProduct = 0.0, dynIsfMode = true,
    )
}
