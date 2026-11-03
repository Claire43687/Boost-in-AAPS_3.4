package app.aaps.core.keys

import app.aaps.core.keys.interfaces.IntNonPreferenceKey

@Suppress("SpellCheckingInspection")
enum class IntNonKey(
    override val key: String,
    override val defaultValue: Int,
    override val exportable: Boolean = true,
) : IntNonPreferenceKey {

    ObjectivesManualEnacts("ObjectivesmanualEnacts", 0),
    TddCycleOffset("tdd_cycle_offset", 0),
    // Boost
    BoostV5AutoConfigSchemaVersion("boost_v5_autoconfig_schema_version", 0),
    BoostV5RedriveSchemaVersion("boost_v5_redrive_schema_version", 0),
    // The history sync describes this install's database, so it does not travel with exported settings.
    ApsBoostHistorySyncAttempts("boost_history_sync_attempts", 0, exportable = false),
    ApsBoostHistorySyncPreBg("boost_history_sync_pre_bg", 0, exportable = false),
    ApsBoostHistorySyncPreTreatments("boost_history_sync_pre_treatments", 0, exportable = false),
    ApsBoostHistorySyncSchemaVersion("boost_history_sync_schema_version", 0, exportable = false)
}
