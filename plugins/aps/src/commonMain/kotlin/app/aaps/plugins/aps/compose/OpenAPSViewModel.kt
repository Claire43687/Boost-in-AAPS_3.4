package app.aaps.plugins.aps.compose

import androidx.compose.runtime.Immutable
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.aps.OapsProfileAutoIsf
import app.aaps.core.interfaces.aps.OapsProfileBoost
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.math.roundToInt

@Immutable
data class OpenAPSUiState(
    val lastRun: String = "",
    val sections: List<OpenAPSSection> = emptyList(),
    val statusMessage: String = "",
    val isRefreshing: Boolean = false
)

@Immutable
data class OpenAPSSection(
    val title: TextRef,
    val rows: List<KeyValueRow> = emptyList(),
    val collapsedByDefault: Boolean = false,
    val isGroupHeader: Boolean = false
)

@Immutable
data class KeyValueRow(
    val key: String,
    val value: String
)

class OpenAPSViewModel(
    private val apsPlugin: APS,
    private val rxBus: RxBus,
    private val rh: TextResolver,
    private val dateUtil: DateUtil,
    private val scope: CoroutineScope
) {

    private val _uiState = MutableStateFlow(OpenAPSUiState())
    val uiState: StateFlow<OpenAPSUiState> = _uiState.asStateFlow()

    init {
        rxBus.toFlow(EventOpenAPSUpdateGui::class)
            .onEach { updateState() }
            .launchIn(scope)

        rxBus.toFlow(EventResetOpenAPSGui::class)
            .onEach { event -> resetState(event.text) }
            .launchIn(scope)

        // Initial state from current data
        scope.launch { updateState() }
    }

    fun onRefresh() {
        _uiState.value = _uiState.value.copy(isRefreshing = true)
        scope.launch {
            apsPlugin.invoke("OpenAPS pull-to-refresh", false)
        }
    }

    private suspend fun updateState() {
        val lastAPSResult = apsPlugin.lastAPSResult
        if (lastAPSResult == null) {
            _uiState.value = OpenAPSUiState(
                statusMessage = rh.gs(CoreUiStrings.not_available_full)
            )
            return
        }

        val sections = buildList {
            // --- Input parameters group ---
            add(OpenAPSSection(title = ApsStrings.openapsma_input_parameters_label, isGroupHeader = true))

            // Constraints
            lastAPSResult.inputConstraints?.let { constraints ->
                val reasons = constraints.getReasons()
                if (reasons.isNotEmpty()) {
                    add(
                        OpenAPSSection(
                            title = ApsStrings.constraints,
                            rows = reasons.split("\n").filter { it.isNotBlank() }.map { KeyValueRow("", it) }
                        )
                    )
                }
            }

            // Glucose Status
            lastAPSResult.glucoseStatus?.let { gs ->
                add(
                    OpenAPSSection(
                        title = ApsStrings.openapsma_glucose_status_label,
                        rows = gs.toRows()
                    )
                )
            }

            // Heart rate (Boost): before, only one line of it was shown, inside Script debug
            lastAPSResult.oapsProfileBoost?.let { bp ->
                add(OpenAPSSection(title = ApsStrings.hr_section_label, rows = bp.heartRateRows()))
            }

            // Current Temp
            lastAPSResult.currentTemp?.let { ct ->
                add(
                    OpenAPSSection(
                        title = ApsStrings.openapsma_current_temp_label,
                        rows = ct.toRows()
                    )
                )
            }

            // IOB Data
            lastAPSResult.iob?.let { iob ->
                add(
                    OpenAPSSection(
                        title = ApsStrings.openapsma_iob_data_label,
                        rows = iob.toRows(lastAPSResult.iobData?.size ?: 0)
                    )
                )
            }

            // Profile (OapsProfile, OapsProfileAutoIsf or OapsProfileBoost)
            val profileRows = lastAPSResult.oapsProfileAutoIsf?.toRows()
                ?: lastAPSResult.oapsProfile?.toRows()
                ?: lastAPSResult.oapsProfileBoost?.toRows()
            if (profileRows != null) {
                add(
                    OpenAPSSection(
                        title = ApsStrings.openapsma_profile_label,
                        rows = profileRows,
                        collapsedByDefault = true
                    )
                )
            }

            // Meal Data
            lastAPSResult.mealData?.let { md ->
                add(
                    OpenAPSSection(
                        title = ApsStrings.openapsma_meal_data_label,
                        rows = md.toRows()
                    )
                )
            }

            // Autosens Data
            lastAPSResult.autosensResult?.let { asr ->
                add(
                    OpenAPSSection(
                        title = ApsStrings.openapsma_autosensdata_label,
                        rows = asr.toRows()
                    )
                )
            }

            // --- Result group ---
            add(OpenAPSSection(title = CoreUiStrings.result, isGroupHeader = true))

            // Script Debug
            lastAPSResult.scriptDebug?.let { debug ->
                if (debug.isNotEmpty()) {
                    add(
                        OpenAPSSection(
                            title = ApsStrings.openapsma_script_debug_data_label,
                            rows = debug.map { KeyValueRow("", it) },
                            collapsedByDefault = true
                        )
                    )
                }
            }

            // Raw Result (RT)
            val rawData = lastAPSResult.rawData()

            // Auto config: Boost appends its autocfg=, autordv= and histsync= breadcrumbs to every reason
            if (rawData is RT) {
                val autoConfigRows = autoConfigRows(rawData.reason.toString())
                if (autoConfigRows.isNotEmpty()) {
                    add(
                        OpenAPSSection(
                            title = ApsStrings.openapsma_autoconfig_label,
                            rows = autoConfigRows,
                            collapsedByDefault = true
                        )
                    )
                }
            }

            if (rawData is RT) {
                add(
                    OpenAPSSection(
                        title = CoreUiStrings.result,
                        rows = rawData.toRows(),
                        collapsedByDefault = true
                    )
                )
            }

            // Request (algorithm decision) — last section
            val requestText = lastAPSResult.resultAsString()
            if (requestText.isNotEmpty()) {
                add(
                    OpenAPSSection(
                        title = ApsStrings.openapsma_request_label,
                        rows = requestText.lines().filter { it.isNotBlank() }.map { KeyValueRow("", it) }
                    )
                )
            }
        }

        _uiState.value = OpenAPSUiState(
            lastRun = dateUtil.dateAndTimeString(apsPlugin.lastAPSRun),
            sections = sections,
            isRefreshing = false
        )
    }

    private fun resetState(text: String) {
        _uiState.value = OpenAPSUiState(
            statusMessage = text,
            isRefreshing = false
        )
    }

    // --- Mapping functions ---

    /**
     * One full-width line naming each breadcrumb, then a row per comma-separated item, named in words.
     * A value such as tr=KEPT_USER_TUNED/d=0.35/op=0.65 becomes a row with the outcome and one indented
     * row per part. A code with no description is shown as written, so a new breadcrumb field still appears.
     */
    private fun autoConfigRows(reason: String): List<KeyValueRow> = buildList {
        for (crumb in AUTO_CONFIG_BREADCRUMB.findAll(reason)) {
            val name = crumb.groupValues[1]
            add(KeyValueRow("", AUTO_CONFIG_CRUMB_LABELS[name]?.let { rh.gs(it) } ?: name))
            for (item in crumb.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                if (item.startsWith('@')) {
                    add(KeyValueRow(rh.gs(ApsStrings.autoconfig_at), isoToLocal(item.substring(1))))
                    continue
                }
                val cut = item.indexOf('=').takeIf { it > 0 } ?: item.indexOf(':').takeIf { it > 0 }
                if (cut == null) {
                    add(KeyValueRow(itemLabel(item), ""))
                    continue
                }
                val code = item.substring(0, cut)
                var value = item.substring(cut + 1)
                // keptUser=1@<ISO time>: the time of the set-up run rides on the last count
                val at = value.substringAfter('@', "").takeIf { it.isNotEmpty() }
                if (at != null) value = value.substringBefore('@')
                val parts = value.split('/')
                add(KeyValueRow(itemLabel(code), words(parts.first()).replace("|", ", ")))
                for (part in parts.drop(1)) {
                    val eq = part.indexOf('=')
                    if (eq > 0) {
                        val letter = part.substring(0, eq)
                        val label = AUTO_CONFIG_PART_LABELS[code]?.get(letter)?.let { rh.gs(it) } ?: letter
                        add(KeyValueRow("   $label", words(part.substring(eq + 1))))
                    } else {
                        // kept:user-set or held:<why>, the reason the knob was left where it is
                        add(KeyValueRow("   " + rh.gs(ApsStrings.autoconfig_reason), part.replace(':', ' ').replace('-', ' ')))
                    }
                }
                if (at != null) add(KeyValueRow(rh.gs(ApsStrings.autoconfig_at), isoToLocal(at)))
            }
        }
    }

    /** held-CommittedCapU, kept-PrimerCapU and retired-X carry the setting's name after the prefix. */
    private fun itemLabel(code: String): String {
        AUTO_CONFIG_ITEM_LABELS[code]?.let { return rh.gs(it) }
        for ((prefix, ref) in AUTO_CONFIG_PREFIX_LABELS)
            if (code.startsWith(prefix)) return rh.gs(ref, code.removePrefix(prefix))
        return code
    }

    private fun isoToLocal(iso: String): String =
        runCatching { dateUtil.dateAndTimeString(dateUtil.fromISODateString(iso)) }.getOrNull() ?: iso

    /** KEPT_USER_TUNED reads as "kept user tuned"; numbers and true/false pass through. */
    private fun words(code: String): String =
        if (OUTCOME_CODE.matches(code)) code.lowercase().replace('_', ' ') else code

    private fun GlucoseStatus.toRows(): List<KeyValueRow> = listOf(
        KeyValueRow("glucose", glucose.toString()),
        KeyValueRow("delta", delta.toString()),
        KeyValueRow("shortAvgDelta", shortAvgDelta.toString()),
        KeyValueRow("longAvgDelta", longAvgDelta.toString())
    )

    private fun CurrentTemp.toRows(): List<KeyValueRow> = buildList {
        add(KeyValueRow("duration", duration.toString()))
        add(KeyValueRow("rate", rate.toString()))
        minutesrunning?.let { add(KeyValueRow("minutesrunning", it.toString())) }
    }

    private fun IobTotal.toRows(arraySize: Int): List<KeyValueRow> = buildList {
        add(KeyValueRow("arraySize", rh.gs(ApsStrings.array_of_elements, arraySize)))
        add(KeyValueRow("iob", iob.toString()))
        add(KeyValueRow("activity", activity.toString()))
        add(KeyValueRow("bolussnooze", bolussnooze.toString()))
        add(KeyValueRow("basaliob", basaliob.toString()))
        add(KeyValueRow("netbasalinsulin", netbasalinsulin.toString()))
        add(KeyValueRow("hightempinsulin", hightempinsulin.toString()))
        add(KeyValueRow("lastBolusTime", if (lastBolusTime > 0) dateUtil.dateAndTimeString(lastBolusTime) else "0"))
        add(KeyValueRow("netInsulin", netInsulin.toString()))
        add(KeyValueRow("extendedBolusInsulin", extendedBolusInsulin.toString()))
    }

    private fun OapsProfile.toRows(): List<KeyValueRow> = buildList {
        add(KeyValueRow("dia", dia.toString()))
        add(KeyValueRow("min_5m_carbimpact", min_5m_carbimpact.toString()))
        add(KeyValueRow("max_iob", max_iob.toString()))
        add(KeyValueRow("max_daily_basal", max_daily_basal.toString()))
        add(KeyValueRow("max_basal", max_basal.toString()))
        add(KeyValueRow("min_bg", min_bg.toString()))
        add(KeyValueRow("max_bg", max_bg.toString()))
        add(KeyValueRow("target_bg", target_bg.toString()))
        add(KeyValueRow("carb_ratio", carb_ratio.toString()))
        add(KeyValueRow("sens", sens.toString()))
        add(KeyValueRow("autosens_adjust_targets", autosens_adjust_targets.toString()))
        add(KeyValueRow("max_daily_safety_multiplier", max_daily_safety_multiplier.toString()))
        add(KeyValueRow("current_basal_safety_multiplier", current_basal_safety_multiplier.toString()))
        add(KeyValueRow("high_temptarget_raises_sensitivity", high_temptarget_raises_sensitivity.toString()))
        add(KeyValueRow("low_temptarget_lowers_sensitivity", low_temptarget_lowers_sensitivity.toString()))
        add(KeyValueRow("sensitivity_raises_target", sensitivity_raises_target.toString()))
        add(KeyValueRow("resistance_lowers_target", resistance_lowers_target.toString()))
        add(KeyValueRow("adv_target_adjustments", adv_target_adjustments.toString()))
        add(KeyValueRow("exercise_mode", exercise_mode.toString()))
        add(KeyValueRow("half_basal_exercise_target", half_basal_exercise_target.toString()))
        add(KeyValueRow("maxCOB", maxCOB.toString()))
        add(KeyValueRow("skip_neutral_temps", skip_neutral_temps.toString()))
        add(KeyValueRow("remainingCarbsCap", remainingCarbsCap.toString()))
        add(KeyValueRow("enableUAM", enableUAM.toString()))
        add(KeyValueRow("A52_risk_enable", A52_risk_enable.toString()))
        add(KeyValueRow("SMBInterval", SMBInterval.toString()))
        add(KeyValueRow("enableSMB_with_COB", enableSMB_with_COB.toString()))
        add(KeyValueRow("enableSMB_with_temptarget", enableSMB_with_temptarget.toString()))
        add(KeyValueRow("allowSMB_with_high_temptarget", allowSMB_with_high_temptarget.toString()))
        add(KeyValueRow("enableSMB_always", enableSMB_always.toString()))
        add(KeyValueRow("enableSMB_after_carbs", enableSMB_after_carbs.toString()))
        add(KeyValueRow("maxSMBBasalMinutes", maxSMBBasalMinutes.toString()))
        add(KeyValueRow("maxUAMSMBBasalMinutes", maxUAMSMBBasalMinutes.toString()))
        add(KeyValueRow("bolus_increment", bolus_increment.toString()))
        add(KeyValueRow("carbsReqThreshold", carbsReqThreshold.toString()))
        add(KeyValueRow("current_basal", current_basal.toString()))
        add(KeyValueRow("temptargetSet", temptargetSet.toString()))
        add(KeyValueRow("autosens_max", autosens_max.toString()))
        add(KeyValueRow("out_units", out_units))
        lgsThreshold?.let { add(KeyValueRow("lgsThreshold", it.toString())) }
        add(KeyValueRow("variable_sens", variable_sens.toString()))
        add(KeyValueRow("insulinDivisor", insulinDivisor.toString()))
        add(KeyValueRow("TDD", TDD.toString()))
    }

    // OapsProfileBoost has some 75 fields and gains more with each Boost release, so its rows come
    // from its serializer, in declaration order, rather than a hand-kept list that would fall behind.
    private fun OapsProfileBoost.heartRateRows(): List<KeyValueRow> = buildList {
        if (!hr_integration) {
            add(KeyValueRow("", rh.gs(ApsStrings.hr_integration_off)))
        } else {
            val avg = hr_avg_bpm
            if (avg == null) {
                add(KeyValueRow("", rh.gs(ApsStrings.hr_no_reading, hr_window_min ?: 0)))
            } else {
                add(KeyValueRow(rh.gs(ApsStrings.hr_average, hr_window_min ?: 0), rh.gs(ApsStrings.hr_bpm, avg.roundToInt())))
                hr_reserve_pct?.let { add(KeyValueRow(rh.gs(ApsStrings.hr_reserve), rh.gs(CoreUiStrings.units_format_percent, it.roundToInt()))) }
                add(KeyValueRow(rh.gs(ApsStrings.hr_zone), hr_zone))
                if (hr_state.isNotEmpty())
                    add(KeyValueRow(rh.gs(ApsStrings.hr_classified), rh.gs(ApsStrings.hr_state_confidence, words(hr_state), words(hr_confidence))))
            }
            hr_resting_bpm?.let {
                add(KeyValueRow(rh.gs(if (hr_resting_learned) ApsStrings.hr_resting_learned else ApsStrings.hr_resting_setting), rh.gs(ApsStrings.hr_bpm, it)))
            }
            hr_max_bpm?.let { add(KeyValueRow(rh.gs(ApsStrings.hr_max), rh.gs(ApsStrings.hr_bpm, it))) }
        }
        if (activity_state.isNotEmpty()) add(KeyValueRow(rh.gs(ApsStrings.hr_activity_state), activity_state))
        if (hr_summary.isNotEmpty()) add(KeyValueRow(rh.gs(ApsStrings.hr_details), hr_summary))
    }

    private fun OapsProfileBoost.toRows(): List<KeyValueRow> =
        rowsJson.encodeToJsonElement(OapsProfileBoost.serializer(), this).jsonObject
            .filterKeys { !it.startsWith("hr_") } // shown in the Heart rate section
            .filterValues { it !is JsonNull }
            .map { (key, value) -> KeyValueRow(key, (value as? JsonPrimitive)?.content ?: value.toString()) }

    private fun OapsProfileAutoIsf.toRows(): List<KeyValueRow> = buildList {
        add(KeyValueRow("dia", dia.toString()))
        add(KeyValueRow("min_5m_carbimpact", min_5m_carbimpact.toString()))
        add(KeyValueRow("max_iob", max_iob.toString()))
        add(KeyValueRow("max_daily_basal", max_daily_basal.toString()))
        add(KeyValueRow("max_basal", max_basal.toString()))
        add(KeyValueRow("min_bg", min_bg.toString()))
        add(KeyValueRow("max_bg", max_bg.toString()))
        add(KeyValueRow("target_bg", target_bg.toString()))
        add(KeyValueRow("carb_ratio", carb_ratio.toString()))
        add(KeyValueRow("sens", sens.toString()))
        add(KeyValueRow("autosens_adjust_targets", autosens_adjust_targets.toString()))
        add(KeyValueRow("max_daily_safety_multiplier", max_daily_safety_multiplier.toString()))
        add(KeyValueRow("current_basal_safety_multiplier", current_basal_safety_multiplier.toString()))
        add(KeyValueRow("high_temptarget_raises_sensitivity", high_temptarget_raises_sensitivity.toString()))
        add(KeyValueRow("low_temptarget_lowers_sensitivity", low_temptarget_lowers_sensitivity.toString()))
        add(KeyValueRow("sensitivity_raises_target", sensitivity_raises_target.toString()))
        add(KeyValueRow("resistance_lowers_target", resistance_lowers_target.toString()))
        add(KeyValueRow("adv_target_adjustments", adv_target_adjustments.toString()))
        add(KeyValueRow("exercise_mode", exercise_mode.toString()))
        add(KeyValueRow("half_basal_exercise_target", half_basal_exercise_target.toString()))
        add(KeyValueRow("maxCOB", maxCOB.toString()))
        add(KeyValueRow("skip_neutral_temps", skip_neutral_temps.toString()))
        add(KeyValueRow("remainingCarbsCap", remainingCarbsCap.toString()))
        add(KeyValueRow("enableUAM", enableUAM.toString()))
        add(KeyValueRow("A52_risk_enable", A52_risk_enable.toString()))
        add(KeyValueRow("SMBInterval", SMBInterval.toString()))
        add(KeyValueRow("enableSMB_with_COB", enableSMB_with_COB.toString()))
        add(KeyValueRow("enableSMB_with_temptarget", enableSMB_with_temptarget.toString()))
        add(KeyValueRow("allowSMB_with_high_temptarget", allowSMB_with_high_temptarget.toString()))
        add(KeyValueRow("enableSMB_always", enableSMB_always.toString()))
        add(KeyValueRow("enableSMB_after_carbs", enableSMB_after_carbs.toString()))
        add(KeyValueRow("maxSMBBasalMinutes", maxSMBBasalMinutes.toString()))
        add(KeyValueRow("maxUAMSMBBasalMinutes", maxUAMSMBBasalMinutes.toString()))
        add(KeyValueRow("bolus_increment", bolus_increment.toString()))
        add(KeyValueRow("carbsReqThreshold", carbsReqThreshold.toString()))
        add(KeyValueRow("current_basal", current_basal.toString()))
        add(KeyValueRow("temptargetSet", temptargetSet.toString()))
        add(KeyValueRow("autosens_max", autosens_max.toString()))
        add(KeyValueRow("out_units", out_units))
        lgsThreshold?.let { add(KeyValueRow("lgsThreshold", it.toString())) }
        // AutoISF-specific
        add(KeyValueRow("variable_sens", variable_sens.toString()))
        add(KeyValueRow("autoISF_version", autoISF_version))
        add(KeyValueRow("enable_autoISF", enable_autoISF.toString()))
        add(KeyValueRow("autoISF_max", autoISF_max.toString()))
        add(KeyValueRow("autoISF_min", autoISF_min.toString()))
        add(KeyValueRow("bgAccel_ISF_weight", bgAccel_ISF_weight.toString()))
        add(KeyValueRow("bgBrake_ISF_weight", bgBrake_ISF_weight.toString()))
        add(KeyValueRow("pp_ISF_weight", pp_ISF_weight.toString()))
        add(KeyValueRow("lower_ISFrange_weight", lower_ISFrange_weight.toString()))
        add(KeyValueRow("higher_ISFrange_weight", higher_ISFrange_weight.toString()))
        add(KeyValueRow("dura_ISF_weight", dura_ISF_weight.toString()))
        add(KeyValueRow("smb_delivery_ratio", smb_delivery_ratio.toString()))
        add(KeyValueRow("smb_delivery_ratio_min", smb_delivery_ratio_min.toString()))
        add(KeyValueRow("smb_delivery_ratio_max", smb_delivery_ratio_max.toString()))
        add(KeyValueRow("smb_delivery_ratio_bg_range", smb_delivery_ratio_bg_range.toString()))
        add(KeyValueRow("smb_max_range_extension", smb_max_range_extension.toString()))
        add(KeyValueRow("enableSMB_EvenOn_OddOff_always", enableSMB_EvenOn_OddOff_always.toString()))
        add(KeyValueRow("iob_threshold_percent", iob_threshold_percent.toString()))
        add(KeyValueRow("profile_percentage", profile_percentage.toString()))
    }

    private fun MealData.toRows(): List<KeyValueRow> = buildList {
        add(KeyValueRow("carbs", carbs.toString()))
        add(KeyValueRow("mealCOB", mealCOB.toString()))
        add(KeyValueRow("slopeFromMaxDeviation", slopeFromMaxDeviation.toString()))
        add(KeyValueRow("slopeFromMinDeviation", slopeFromMinDeviation.toString()))
        add(KeyValueRow("lastBolusTime", if (lastBolusTime > 0) dateUtil.dateAndTimeString(lastBolusTime) else "0"))
        add(KeyValueRow("lastCarbTime", if (lastCarbTime > 0) dateUtil.dateAndTimeString(lastCarbTime) else "0"))
        add(KeyValueRow("usedMinCarbsImpact", usedMinCarbsImpact.toString()))
    }

    private fun AutosensResult.toRows(): List<KeyValueRow> = buildList {
        add(KeyValueRow("ratio", ratio.toString()))
        add(KeyValueRow("carbsAbsorbed", carbsAbsorbed.toString()))
        add(KeyValueRow("sensResult", sensResult))
        add(KeyValueRow("pastSensitivity", pastSensitivity))
        add(KeyValueRow("ratioLimit", ratioLimit))
        add(KeyValueRow("ratioFromTdd", ratioFromTdd.toString()))
        add(KeyValueRow("ratioFromCarbs", ratioFromCarbs.toString()))
    }

    private fun RT.toRows(): List<KeyValueRow> = buildList {
        add(KeyValueRow("algorithm", algorithm.name))
        add(KeyValueRow("runningDynamicIsf", runningDynamicIsf.toString()))
        timestamp?.let { add(KeyValueRow("timestamp", dateUtil.dateAndTimeString(it))) }
        add(KeyValueRow("temp", temp))
        bg?.let { add(KeyValueRow("bg", it.toString())) }
        tick?.let { add(KeyValueRow("tick", it)) }
        eventualBG?.let { add(KeyValueRow("eventualBG", it.toString())) }
        targetBG?.let { add(KeyValueRow("targetBG", it.toString())) }
        snoozeBG?.let { add(KeyValueRow("snoozeBG", it.toString())) }
        insulinReq?.let { add(KeyValueRow("insulinReq", it.toString())) }
        carbsReq?.let { add(KeyValueRow("carbsReq", it.toString())) }
        carbsReqWithin?.let { add(KeyValueRow("carbsReqWithin", it.toString())) }
        units?.let { add(KeyValueRow("units", it.toString())) }
        deliverAt?.let { add(KeyValueRow("deliverAt", dateUtil.dateAndTimeString(it))) }
        sensitivityRatio?.let { add(KeyValueRow("sensitivityRatio", it.toString())) }
        val reasonStr = reason.toString()
        if (reasonStr.isNotEmpty()) add(KeyValueRow("reason", reasonStr))
        duration?.let { add(KeyValueRow("duration", it.toString())) }
        rate?.let { add(KeyValueRow("rate", it.toString())) }
        COB?.let { add(KeyValueRow("COB", it.toString())) }
        IOB?.let { add(KeyValueRow("IOB", it.toString())) }
        variable_sens?.let { add(KeyValueRow("variable_sens", it.toString())) }
        isfMgdlForCarbs?.let { add(KeyValueRow("isfMgdlForCarbs", it.toString())) }
    }
}

// encodeDefaults so the fields with defaults (dynIsfMode, the debug reasons, the V5 inputs) are listed too.
private val rowsJson = Json { encodeDefaults = true }

private val AUTO_CONFIG_BREADCRUMB = Regex("""\b(autocfg|autordv|histsync)=([^;]*)""")

private val OUTCOME_CODE = Regex("[A-Z][A-Z_]+")

private val AUTO_CONFIG_CRUMB_LABELS = mapOf(
    "autocfg" to ApsStrings.autoconfig_crumb_setup,
    "autordv" to ApsStrings.autoconfig_crumb_redrive,
    "histsync" to ApsStrings.autoconfig_crumb_histsync
)

private val AUTO_CONFIG_ITEM_LABELS = mapOf(
    "applied" to ApsStrings.autoconfig_applied,
    "heldTBR" to ApsStrings.autoconfig_held_tbr,
    "keptUser" to ApsStrings.autoconfig_kept_user,
    "held" to ApsStrings.autoconfig_held,
    "tr" to ApsStrings.autoconfig_tr,
    "ndw" to ApsStrings.autoconfig_ndw,
    "win" to ApsStrings.autoconfig_win,
    "ev" to ApsStrings.autoconfig_ev,
    "ch" to ApsStrings.autoconfig_ch,
    "skip" to ApsStrings.autoconfig_skip,
    "pr" to ApsStrings.autoconfig_pr,
    "aec" to ApsStrings.autoconfig_aec,
    "fcc" to ApsStrings.autoconfig_fcc
)

private val AUTO_CONFIG_PREFIX_LABELS = listOf(
    "held-" to ApsStrings.autoconfig_prefix_held,
    "kept-" to ApsStrings.autoconfig_prefix_kept,
    "retired-" to ApsStrings.autoconfig_prefix_retired
)

private val SWITCH_PARTS = mapOf("v" to ApsStrings.autoconfig_part_switch_value, "ok" to ApsStrings.autoconfig_part_switch_ok)

/** The letters inside a slash-separated value, by the item they belong to. */
private val AUTO_CONFIG_PART_LABELS = mapOf(
    "tr" to mapOf(
        "d" to ApsStrings.autoconfig_part_tr_d,
        "op" to ApsStrings.autoconfig_part_tr_op,
        "s" to ApsStrings.autoconfig_part_tr_s,
        "l" to ApsStrings.autoconfig_part_tr_l,
        "J" to ApsStrings.autoconfig_part_tr_j
    ),
    "ndw" to mapOf(
        "z" to ApsStrings.autoconfig_part_ndw_z,
        "p" to ApsStrings.autoconfig_part_ndw_p,
        "n" to ApsStrings.autoconfig_part_ndw_n
    ),
    "pr" to mapOf("tb" to ApsStrings.autoconfig_part_pr_tb, "wc" to ApsStrings.autoconfig_part_pr_wc),
    "aec" to SWITCH_PARTS,
    "fcc" to SWITCH_PARTS
)
