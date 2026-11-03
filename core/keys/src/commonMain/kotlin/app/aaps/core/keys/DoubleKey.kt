package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.ElementVisibility
import app.aaps.core.keys.interfaces.SyncChannel
import app.aaps.core.keys.interfaces.SyncDirection
import app.aaps.core.keys.interfaces.SyncSpec
import app.aaps.core.keys.interfaces.TextRef

enum class DoubleKey(
    override val key: String,
    override val defaultValue: Double,
    override val min: Double,
    override val max: Double,
    override val title: TextRef,
    override val summary: TextRef? = null,
    override val preferenceType: PreferenceType = PreferenceType.TEXT_FIELD,
    override val defaultedBySM: Boolean = false,
    override val calculatedBySM: Boolean = false,
    override val dependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val unitType: UnitType = UnitType.NONE,
    override val sync: SyncSpec? = null,
    override val visibility: ElementVisibility = ElementVisibility.ALWAYS
) : DoublePreferenceKey {

    OverviewInsulinButtonIncrement1(
        key = "insulin_button_increment_1",
        defaultValue = 0.5,
        min = -5.0,
        max = 5.0,
        title = KeysStrings.pref_title_insulin_button_increment_1,
        summary = KeysStrings.insulin_increment_button_message,
        defaultedBySM = true,
        dependency = BooleanKey.OverviewShowInsulinButton,
        unitType = UnitType.INSULIN,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    OverviewInsulinButtonIncrement2(
        key = "insulin_button_increment_2",
        defaultValue = 1.0,
        min = -5.0,
        max = 5.0,
        title = KeysStrings.pref_title_insulin_button_increment_2,
        summary = KeysStrings.insulin_increment_button_message,
        defaultedBySM = true,
        dependency = BooleanKey.OverviewShowInsulinButton,
        unitType = UnitType.INSULIN,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    OverviewInsulinButtonIncrement3(
        key = "insulin_button_increment_3",
        defaultValue = 2.0,
        min = -5.0,
        max = 5.0,
        title = KeysStrings.pref_title_insulin_button_increment_3,
        summary = KeysStrings.insulin_increment_button_message,
        defaultedBySM = true,
        dependency = BooleanKey.OverviewShowInsulinButton,
        unitType = UnitType.INSULIN,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    // Hidden where the pump cannot be primed from AAPS (patch pumps): the Fill dialog has no prime there.
    ActionsFillButton1(
        key = "fill_button1", defaultValue = 0.3, min = 0.05, max = 20.0, title = KeysStrings.pref_title_fill_button_1, defaultedBySM = true, hideParentScreenIfHidden = true,
        unitType = UnitType.INSULIN, visibility = ElementVisibility.REFILLING_CAPABLE
    ),
    ActionsFillButton2(
        key = "fill_button2", defaultValue = 0.0, min = 0.0, max = 20.0, title = KeysStrings.pref_title_fill_button_2, defaultedBySM = true,
        unitType = UnitType.INSULIN, visibility = ElementVisibility.REFILLING_CAPABLE
    ),
    ActionsFillButton3(
        key = "fill_button3", defaultValue = 0.0, min = 0.0, max = 20.0, title = KeysStrings.pref_title_fill_button_3, defaultedBySM = true,
        unitType = UnitType.INSULIN, visibility = ElementVisibility.REFILLING_CAPABLE
    ),
    SafetyMaxBolus(key = "treatmentssafety_maxbolus", defaultValue = 3.0, min = 0.1, max = 60.0, title = KeysStrings.pref_title_max_bolus, unitType = UnitType.INSULIN, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    ApsMaxBasal(
        key = "openapsma_max_basal",
        defaultValue = 1.0,
        min = 0.1,
        max = 25.0,
        title = KeysStrings.pref_title_max_basal,
        summary = KeysStrings.openapsma_max_basal_summary,
        defaultedBySM = true,
        calculatedBySM = true,
        unitType = UnitType.INSULIN_RATE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsSmbMaxIob(
        key = "openapsmb_max_iob",
        defaultValue = 3.0,
        min = 0.0,
        max = 70.0,
        title = KeysStrings.pref_title_smb_max_iob,
        summary = KeysStrings.openapssmb_max_iob_summary,
        defaultedBySM = true,
        calculatedBySM = true,
        unitType = UnitType.INSULIN,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAmaMaxIob(
        key = "openapsma_max_iob",
        defaultValue = 1.5,
        min = 0.0,
        max = 25.0,
        title = KeysStrings.pref_title_ama_max_iob,
        summary = KeysStrings.openapsma_max_iob_summary,
        defaultedBySM = true,
        calculatedBySM = true,
        unitType = UnitType.INSULIN,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsMaxDailyMultiplier(
        key = "openapsama_max_daily_safety_multiplier",
        defaultValue = 3.0,
        min = 1.0,
        max = 10.0,
        title = KeysStrings.pref_title_max_daily_multiplier,
        summary = KeysStrings.openapsama_max_daily_safety_multiplier_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsMaxCurrentBasalMultiplier(
        key = "openapsama_current_basal_safety_multiplier",
        defaultValue = 4.0,
        min = 1.0,
        max = 10.0,
        title = KeysStrings.pref_title_current_basal_multiplier,
        summary = KeysStrings.openapsama_current_basal_safety_multiplier_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAmaBolusSnoozeDivisor(
        key = "bolussnooze_dia_divisor",
        defaultValue = 2.0,
        min = 1.0,
        max = 10.0,
        title = KeysStrings.pref_title_bolus_snooze_divisor,
        summary = KeysStrings.openapsama_bolus_snooze_dia_divisor_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAmaMin5MinCarbsImpact(
        key = "openapsama_min_5m_carbimpact",
        defaultValue = 3.0,
        min = 1.0,
        max = 12.0,
        title = KeysStrings.pref_title_ama_min_5m_carbs_impact,
        summary = KeysStrings.openapsama_min_5m_carb_impact_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsSmbMin5MinCarbsImpact(
        key = "openaps_smb_min_5m_carbimpact",
        defaultValue = 8.0,
        min = 1.0,
        max = 12.0,
        title = KeysStrings.pref_title_smb_min_5m_carbs_impact,
        summary = KeysStrings.openapsama_min_5m_carb_impact_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    AbsorptionCutOff(
        key = "absorption_cutoff",
        defaultValue = 6.0,
        min = 4.0,
        max = 10.0,
        title = KeysStrings.pref_title_absorption_cutoff,
        summary = KeysStrings.absorption_cutoff_summary,
        unitType = UnitType.HOURS_DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    AbsorptionMaxTime(
        key = "absorption_maxtime",
        defaultValue = 6.0,
        min = 4.0,
        max = 10.0,
        title = KeysStrings.pref_title_absorption_maxtime,
        summary = KeysStrings.absorption_max_time_summary,
        unitType = UnitType.HOURS_DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    AutosensMin(
        key = "autosens_min",
        defaultValue = 0.7,
        min = 0.1,
        max = 1.0,
        title = KeysStrings.pref_title_autosens_min,
        summary = KeysStrings.openapsama_autosens_min_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    AutosensMax(
        key = "autosens_max",
        defaultValue = 1.2,
        min = 0.5,
        max = 3.0,
        title = KeysStrings.pref_title_autosens_max,
        summary = KeysStrings.openapsama_autosens_max_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfMin(
        key = "autoISF_min",
        defaultValue = 1.0,
        min = 0.3,
        max = 1.0,
        title = KeysStrings.pref_title_autoisf_min,
        summary = KeysStrings.openapsama_autoISF_min_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfMax(
        key = "autoISF_max",
        defaultValue = 1.0,
        min = 1.0,
        max = 3.0,
        title = KeysStrings.pref_title_autoisf_max,
        summary = KeysStrings.openapsama_autoISF_max_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfBgAccelWeight(
        key = "bgAccel_ISF_weight",
        defaultValue = 0.0,
        min = 0.0,
        max = 1.0,
        title = KeysStrings.pref_title_bg_accel_weight,
        summary = KeysStrings.openapsama_bgAccel_ISF_weight_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfBgBrakeWeight(
        key = "bgBrake_ISF_weight",
        defaultValue = 0.0,
        min = 0.0,
        max = 1.0,
        title = KeysStrings.pref_title_bg_brake_weight,
        summary = KeysStrings.openapsama_bgBrake_ISF_weight_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfLowBgWeight(
        key = "lower_ISFrange_weight",
        defaultValue = 0.0,
        min = 0.0,
        max = 2.0,
        title = KeysStrings.pref_title_low_bg_weight,
        summary = KeysStrings.openapsama_lower_ISFrange_weight_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfHighBgWeight(
        key = "higher_ISFrange_weight",
        defaultValue = 0.0,
        min = 0.0,
        max = 2.0,
        title = KeysStrings.pref_title_high_bg_weight,
        summary = KeysStrings.openapsama_higher_ISFrange_weight_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfSmbDeliveryRatioBgRange(
        key = "openapsama_smb_delivery_ratio_bg_range",
        defaultValue = 0.0,
        min = 0.0,
        max = 100.0,
        title = KeysStrings.pref_title_smb_delivery_ratio_bg_range,
        summary = KeysStrings.openapsama_smb_delivery_ratio_bg_range_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfPpWeight(
        key = "pp_ISF_weight",
        defaultValue = 0.0,
        min = 0.0,
        max = 0.15,
        title = KeysStrings.pref_title_pp_weight,
        summary = KeysStrings.openapsama_pp_ISF_weight_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_3,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfDuraWeight(
        key = "dura_ISF_weight",
        defaultValue = 0.0,
        min = 0.0,
        max = 3.0,
        title = KeysStrings.pref_title_dura_weight,
        summary = KeysStrings.openapsama_dura_ISF_weight_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfSmbDeliveryRatio(
        key = "openapsama_smb_delivery_ratio",
        defaultValue = 0.5,
        min = 0.1,
        max = 1.0,
        title = KeysStrings.pref_title_smb_delivery_ratio,
        summary = KeysStrings.openapsama_smb_delivery_ratio_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfSmbDeliveryRatioMin(
        key = "openapsama_smb_delivery_ratio_min",
        defaultValue = 0.5,
        min = 0.1,
        max = 1.0,
        title = KeysStrings.pref_title_smb_delivery_ratio_min,
        summary = KeysStrings.openapsama_smb_delivery_ratio_min_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfSmbDeliveryRatioMax(
        key = "openapsama_smb_delivery_ratio_max",
        defaultValue = 0.5,
        min = 0.5,
        max = 1.0,
        title = KeysStrings.pref_title_smb_delivery_ratio_max,
        summary = KeysStrings.openapsama_smb_delivery_ratio_max_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfSmbMaxRangeExtension(
        key = "openapsama_smb_max_range_extension",
        defaultValue = 1.0,
        min = 1.0,
        max = 5.0,
        title = KeysStrings.pref_title_smb_max_range_extension,
        summary = KeysStrings.openapsama_smb_max_range_extension_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),

    // Boost
    ApsBoostBolus(key = "boost_bolus_cap", defaultValue = 2.5, min = 0.1, max = 10.0, title = KeysStrings.pref_title_boost_bolus_cap, summary = KeysStrings.pref_summary_boost_bolus, defaultedBySM = true, unitType = UnitType.INSULIN),
    ApsBoostMaxIob(key = "boost_max_iob", defaultValue = 1.0, min = 0.1, max = 12.0, title = KeysStrings.pref_title_boost_max_iob, summary = KeysStrings.pref_summary_boost_max_iob, defaultedBySM = true, unitType = UnitType.INSULIN),
    ApsBoostInsulinReqPct(key = "boost_insulin_req_pct", defaultValue = 50.0, min = 30.0, max = 100.0, title = KeysStrings.pref_title_boost_insulin_req_pct, summary = KeysStrings.pref_summary_boost_insulin_req_pct, defaultedBySM = true, unitType = UnitType.PERCENT),
    ApsBoostScale(key = "boost_scale_value", defaultValue = 1.0, min = 0.1, max = 3.0, title = KeysStrings.pref_title_boost_scale_value, summary = KeysStrings.pref_summary_boost_scale, defaultedBySM = true, unitType = UnitType.DOUBLE),
    ApsBoostDynIsfVelocity(key = "boost_dynisf_velocity", defaultValue = 100.0, min = 0.0, max = 100.0, title = KeysStrings.pref_title_boost_dynisf_velocity, summary = KeysStrings.pref_summary_boost_dyn_isf_velocity, defaultedBySM = true, dependency = BooleanKey.ApsBoostUseTdd, unitType = UnitType.PERCENT),
    ApsBoostSleepInHours(key = "boost_sleep_in_hrs", defaultValue = 2.0, min = 0.0, max = 18.0, title = KeysStrings.pref_title_boost_sleep_in_hours, summary = KeysStrings.pref_summary_boost_sleep_in_hours, defaultedBySM = true, unitType = UnitType.HOURS_DOUBLE),
    ApsBoostInactivityPct(key = "boost_inactivity_pct", defaultValue = 130.0, min = 100.0, max = 200.0, title = KeysStrings.pref_title_boost_inactivity_pct, summary = KeysStrings.pref_summary_boost_inactivity_pct, defaultedBySM = true, unitType = UnitType.PERCENT),
    ApsBoostActivityPct(key = "boost_activity_pct", defaultValue = 80.0, min = 30.0, max = 150.0, title = KeysStrings.pref_title_boost_activity_pct, summary = KeysStrings.pref_summary_boost_activity_pct, defaultedBySM = true, unitType = UnitType.PERCENT),
    ApsBoostPostExerciseRecoveryHours(key = "boost_post_exercise_recovery_hours", defaultValue = 2.0, min = 0.5, max = 8.0, title = KeysStrings.pref_title_boost_post_exercise_recovery_hours, summary = KeysStrings.pref_summary_boost_post_exercise_recovery_hours, defaultedBySM = true, unitType = UnitType.HOURS_DOUBLE),
    ApsBoostPostExerciseRecoveryScale(key = "boost_post_exercise_recovery_scale", defaultValue = 0.5, min = 0.0, max = 1.0, title = KeysStrings.pref_title_boost_post_exercise_recovery_scale, summary = KeysStrings.pref_summary_boost_post_exercise_recovery_scale, defaultedBySM = true, unitType = UnitType.DOUBLE),
    ApsBoostCumulativeSmbCap60Min(key = "boost_cumulative_smb_cap_60min", defaultValue = 10.0, min = 0.0, max = 10.0, title = KeysStrings.pref_title_boost_cumulative_smb_cap_60min, summary = KeysStrings.pref_summary_boost_cumulative_smb_cap60_min, defaultedBySM = true, unitType = UnitType.INSULIN),
    ApsBoostV5Aggression(key = "boost_v5_aggression", defaultValue = 1.0, min = 0.7, max = 1.6, title = KeysStrings.pref_title_boost_v5_aggression, summary = KeysStrings.pref_summary_boost_v5_aggression, defaultedBySM = true, unitType = UnitType.DOUBLE),
    ApsBoostV5ConfirmedCapU(key = "boost_v5_confirmed_cap_u", defaultValue = 2.5, min = 0.0, max = 7.5, title = KeysStrings.pref_title_boost_v5_confirmed_cap_u, summary = KeysStrings.pref_summary_boost_v5_confirmed_cap_u, defaultedBySM = true, unitType = UnitType.INSULIN),
    ApsBoostV5CommittedCapU(key = "boost_v5_committed_cap_u", defaultValue = 0.5, min = 0.0, max = 2.5, title = KeysStrings.pref_title_boost_v5_committed_cap_u, summary = KeysStrings.pref_summary_boost_v5_committed_cap_u, defaultedBySM = true, unitType = UnitType.INSULIN),
    ApsBoostV5TrancheFraction(key = "boost_v5_tranche_fraction", defaultValue = 0.5, min = 0.3, max = 1.0, title = KeysStrings.pref_title_boost_v5_tranche_fraction, summary = KeysStrings.pref_summary_boost_v5_tranche_fraction, unitType = UnitType.DOUBLE_2, defaultedBySM = true),
    ApsBoostV5TrancheThreshold(key = "boost_v5_tranche_threshold", defaultValue = 0.48, min = 0.20, max = 0.80, title = KeysStrings.pref_title_boost_v5_tranche_threshold, summary = KeysStrings.pref_summary_boost_v5_tranche_threshold, unitType = UnitType.DOUBLE_2, defaultedBySM = true),
    ApsBoostV5PrimerCapU(key = "boost_v5_primer_cap_u", defaultValue = 0.0, min = 0.0, max = 2.5, title = KeysStrings.pref_title_boost_v5_primer_cap_u, summary = KeysStrings.pref_summary_boost_v5_primer_cap_u, unitType = UnitType.DOUBLE_2, defaultedBySM = true),
    ApsBoostV5HypoCaution(key = "boost_v5_hypo_caution", defaultValue = 1.0, min = 1.0, max = 2.0, title = KeysStrings.pref_title_boost_v5_hypo_caution, summary = KeysStrings.pref_summary_boost_v5_hypo_caution, defaultedBySM = true, unitType = UnitType.DOUBLE),
    ApsBoostV5Sensitivity(key = "boost_v5_sensitivity", defaultValue = 1.0, min = 0.8, max = 1.2, title = KeysStrings.pref_title_boost_v5_sensitivity, summary = KeysStrings.pref_summary_boost_v5_sensitivity, defaultedBySM = true, unitType = UnitType.DOUBLE),
    ApsBoostV6PreMealTargetMgdl(key = "boost_v6_pre_meal_target_mgdl", defaultValue = 72.0, min = 65.0, max = 90.0, title = KeysStrings.pref_title_boost_v6_pre_meal_target_mgdl, summary = KeysStrings.pref_summary_boost_v6_pre_meal_target_mgdl, defaultedBySM = true, unitType = UnitType.DOUBLE),
    ApsBoostV6PreMealLeadMin(key = "boost_v6_pre_meal_lead_min", defaultValue = 60.0, min = 30.0, max = 90.0, title = KeysStrings.pref_title_boost_v6_pre_meal_lead_min, summary = KeysStrings.pref_summary_boost_v6_pre_meal_lead_min, defaultedBySM = true, unitType = UnitType.DOUBLE),
    ;

}
