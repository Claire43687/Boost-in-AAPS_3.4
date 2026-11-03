package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.SyncChannel
import app.aaps.core.keys.interfaces.SyncDirection
import app.aaps.core.keys.interfaces.SyncSpec
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.UnitDoublePreferenceKey

enum class UnitDoubleKey(
    override val key: String,
    override val defaultValue: Double,
    override val minMgdl: Int,
    override val maxMgdl: Int,
    override val title: TextRef,
    override val summary: TextRef? = null,
    override val preferenceType: PreferenceType = PreferenceType.TEXT_FIELD,
    override val defaultedBySM: Boolean = false,
    override val dependency: BooleanPreferenceKey? = null,
    override val sync: SyncSpec? = null
) : UnitDoublePreferenceKey {

    OverviewLowMark(key = "low_mark", defaultValue = 72.0, minMgdl = 25, maxMgdl = 160, title = KeysStrings.pref_title_low_mark, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    OverviewHighMark(key = "high_mark", defaultValue = 180.0, minMgdl = 90, maxMgdl = 250, title = KeysStrings.pref_title_high_mark, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    ApsLgsThreshold(
        key = "lgsThreshold",
        defaultValue = 65.0,
        minMgdl = 60,
        maxMgdl = 100,
        title = KeysStrings.pref_title_lgs_threshold,
        summary = KeysStrings.lgs_threshold_summary,
        defaultedBySM = true,
        dependency = BooleanKey.ApsUseDynamicSensitivity,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),

    // Boost
    ApsBoostDynIsfBgCap(key = "boost_dynisf_bg_cap", defaultValue = 210.0, minMgdl = 100, maxMgdl = 300, title = KeysStrings.pref_title_boost_dynisf_bg_cap, summary = KeysStrings.pref_summary_boost_dyn_isf_bg_cap, defaultedBySM = true),
    ApsBoostDynIsfNormalTarget(key = "boost_dynisf_normal_target", defaultValue = 99.0, minMgdl = 70, maxMgdl = 120, title = KeysStrings.pref_title_boost_dynisf_normal_target, summary = KeysStrings.pref_summary_boost_dyn_isf_normal_target, defaultedBySM = true),
    ApsBoostNightModeBgOffset(key = "boost_night_mode_bg_offset", defaultValue = 27.0, minMgdl = 0, maxMgdl = 90, title = KeysStrings.pref_title_boost_night_mode_bg_offset, summary = KeysStrings.pref_summary_boost_night_mode_bg_offset, defaultedBySM = true),
    ApsBoostPostExerciseRecoveryTarget(key = "boost_post_exercise_recovery_target", defaultValue = 144.0, minMgdl = 90, maxMgdl = 200, title = KeysStrings.pref_title_boost_post_exercise_recovery_target, summary = KeysStrings.pref_summary_boost_post_exercise_recovery_target, defaultedBySM = true),
    ;

}
