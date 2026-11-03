package app.aaps.implementation.maintenance.migration

import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.InsulinModel
import app.aaps.core.interfaces.insulin.InsulinType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.objects.extensions.toJsonObject
import app.aaps.core.keys.ProfileIntKey
import app.aaps.core.keys.StringKey
import app.aaps.implementation.ImplementationStrings
import dev.zacsweers.metro.Inject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Which insulin the records that predate ICfg were delivered with.
 *
 * In v3 the insulin was a plugin and the DIA belonged to the profile. In v4 both are one thing, carried by
 * the record. The database migration to v33 marks every record it cannot fill with `insulinEndTime = -1`,
 * and this works out what to stamp onto those.
 *
 * ## Where the answer comes from, best first
 *
 * 1. **The record itself.** v3 wrote the insulin onto every profile switch - the plugin's label and peak
 *    plus `insulinEndTime = pureProfile.dia * 3600 * 1000` - and `migration32to33` now carries those three
 *    columns across. So most installs are answered by the row and nothing below runs.
 * 2. **The dead v3 preference names**, for the records the row cannot answer: boluses, which v3 never
 *    stamped, and any row whose own value was missing. [readLegacyInsulin] recovers the plugin selection and
 *    [harvestProfileDia] the DIA each local profile was using.
 * 3. **A guess**, when neither can say. Always reported to the user, because it is wrong IOB on historical
 *    records and it is one-way: the sentinel is consumed, so those rows are never revisited.
 *
 * ## Why this is not in `PreferenceMigrations`
 *
 * A preference migration MOVES a value the store already holds, and runs over an imported file as well as
 * over the device. This reads the store to fill a decision that is then written to the DATABASE, and there
 * is no database import in AAPS, so a fresh install plus import has no legacy rows to stamp. It is also a
 * pure read: nothing here writes, and `MainApp` removes the legacy names only once the value they carry is
 * safely in the insulin list.
 *
 * ## Why it is a class and not `MainApp` code
 *
 * It used to be three private methods on `MainApp`, which has no test and cannot have one. Everything else
 * in `doMigrations` was moved into `PreferenceMigrations` and covered; this half stayed behind untested, and
 * two defects lived there for months - a DIA that silently became the hard-limit maximum of 9 h, and the
 * two sources asked in the wrong order, so an install whose own record knew the answer got a
 * reconstruction anyway.
 */
@Inject
class InsulinMigration(
    private val aapsLogger: AAPSLogger,
    private val hardLimits: HardLimits,
    private val rh: TextResolver
) {

    /**
     * The insulin plugin a v3 install had selected.
     *
     * Falls back to [InsulinType.OREF_RAPID_ACTING] when no row is found, and that is NOT a guess. v3
     * declared that plugin `enableByDefault(true)` and wrote NO `ConfigBuilder_*INSULIN*` row at all until
     * the user changed the selection, so "no row" means rapid-acting. Checked on a real 3.4.2.1 install: a
     * fresh start has not one such key in its preferences, and Config Builder shows Rapid-Acting Oref as
     * the insulin in use. [LegacyInsulin.fromExplicitKey] records which of the two it was, because it
     * decides whether the DIA, if it cannot be found either, is worth telling the user about.
     *
     * Both spellings are checked: the legacy `ConfigBuilder_INSULIN_<X>_Enabled` and the
     * `ConfigBuilder_Enabled_INSULIN_<X>` the ConfigBuilder rename produces. So it does not matter whether
     * `PreferenceMigrations.configBuilder` has been past yet - only that this runs before
     * `PreferenceMigrations.insulinPlugins` deletes the names.
     */
    fun readLegacyInsulin(sp: KeyValueStore): LegacyInsulin {
        fun selected(plugin: String) =
            sp.getBoolean("ConfigBuilder_Enabled_INSULIN_$plugin", false) || sp.getBoolean("ConfigBuilder_INSULIN_${plugin}_Enabled", false)

        // Boost 3.4 only. The curve is a model in 4.0, not a template, so it cannot go through the `when`.
        if (selected("InsulinLyumjevU100PDPlugin") || selected("InsulinLyumjevU200PDPlugin"))
            return LegacyInsulin(LYUMJEV_PD_NICKNAME, ICfg.LYUMJEV_PD_NOMINAL_PEAK_MINUTES * 60_000L, fromExplicitKey = true, model = InsulinModel.LYUMJEV_PD)

        val explicit = when {
            selected("InsulinOrefRapidActingPlugin")      -> InsulinType.OREF_RAPID_ACTING
            selected("InsulinOrefUltraRapidActingPlugin") -> InsulinType.OREF_ULTRA_RAPID_ACTING
            selected("InsulinOrefFreePeakPlugin")         -> InsulinType.OREF_FREE_PEAK
            selected("InsulinLyumjevPlugin")              -> InsulinType.OREF_LYUMJEV
            else                                          -> null
        }
        val type = explicit ?: InsulinType.OREF_RAPID_ACTING
        // Free peak is the only one whose peak the user set, and 75 is the default the v3 key carried.
        val peakTime =
            if (type == InsulinType.OREF_FREE_PEAK) (sp.getInt("insulin_oref_peak", 75) * 60 * 1000).toLong()
            else type.insulinPeakTime
        return LegacyInsulin(nickname = rh.gs(type.label), peakTime = peakTime, fromExplicitKey = explicit != null)
    }

    /**
     * The DIA each v3 local profile was using, paired with the profile's name.
     *
     * Four names, because a profile field has been spelled two ways and the DIA key has not moved with the
     * rest: `LocalProfile_<n>_name` / `LocalProfile_<n>_dia` is the raw 3.3 spelling, and
     * `LocalProfile_name_<n>` / `LocalProfile_dia_<n>` is what the ConfigBuilder-era rename produced. No key
     * in this build owns a DIA any more - the insulin does - so both DIA spellings are collected for removal
     * rather than carried forward, and so is the raw `_name` that `PreferenceMigrations.localProfile`
     * deliberately copies without deleting.
     *
     * The caller removes [ProfileDiaHarvest.keysToRemove] only after the value they carry is in the insulin
     * list. Removing them as they are read put the only copy of those DIAs in memory, across a startup gap
     * that can take the process down, and a death in that gap lost them for good.
     */
    fun harvestProfileDia(sp: KeyValueStore): ProfileDiaHarvest {
        val indexToName = mutableMapOf<Int, String>()
        val indexToDia = mutableMapOf<Int, Double>()
        val keysToRemove = mutableListOf<String>()
        for ((key, value) in sp.getAll()) {
            val parts = key.split("_")
            if (key.startsWith(Constants.LOCAL_PROFILE + "_") && key.endsWith("_name"))
                parts.getOrNull(1)?.toIntOrNull()?.let { number ->
                    LegacyPreferenceValue.asString(value)?.let { name ->
                        indexToName[number] = name
                        keysToRemove += key
                    }
                }
            if (key.startsWith(Constants.LOCAL_PROFILE + "_name_"))
                parts.getOrNull(2)?.toIntOrNull()?.let { number ->
                    LegacyPreferenceValue.asString(value)?.let { indexToName[number] = it }
                }
            if (key.startsWith(Constants.LOCAL_PROFILE + "_") && key.endsWith("_dia"))
                parts.getOrNull(1)?.toIntOrNull()?.let { number ->
                    LegacyPreferenceValue.asDouble(value)?.let { dia ->
                        indexToDia[number] = dia
                        keysToRemove += key
                    }
                }
            if (key.startsWith(Constants.LOCAL_PROFILE + "_dia_"))
                parts.getOrNull(2)?.toIntOrNull()?.let { number ->
                    LegacyPreferenceValue.asDouble(value)?.let { dia ->
                        indexToDia[number] = dia
                        keysToRemove += key
                    }
                }
        }
        val nameToDia = indexToDia.mapNotNull { (index, dia) ->
            indexToName[index]?.let { name -> name to dia }
        }.toMap()
        return ProfileDiaHarvest(nameToDia, keysToRemove)
    }

    /**
     * The insulin to stamp onto the records that still carry the sentinel.
     *
     * [recordICfg] is the running record's own insulin, which after `migration32to33` is the v3 value and
     * therefore the right answer. It is consulted FIRST, which is the opposite of the old order: that asked
     * the legacy preferences whenever any DIA had been harvested, so an install whose row knew the answer
     * got a reconstruction anyway. It still has to be [ICfg.isUsable] - a row that carries the sentinel
     * reports a DIA of 0.0, and reading that back would write the sentinel over itself for ever.
     *
     * [activeProfileName] is the running record's `originalProfileName`, used to pick that profile's DIA out
     * of the harvest.
     */
    fun plan(recordICfg: ICfg?, legacy: LegacyInsulin?, harvest: ProfileDiaHarvest, activeProfileName: String?, upgradingFrom3x: Boolean = true): InsulinMigrationPlan {
        // Ahead of the record. 3.4 computed IOB with the selected plugin whatever the running row said, and the
        // curve fixes its own end, so nothing has to be found. Answers once: the keys are deleted at this start.
        // [upgradingFrom3x] false: earlier 4.0 builds left these keys behind, and that person may have chosen
        // another insulin since, so with a usable record the keys say nothing about what is in the pump now.
        if (legacy?.model == InsulinModel.LYUMJEV_PD && (upgradingFrom3x || recordICfg?.isUsable != true)) return plan(lyumjevPdICfg(), InsulinMigrationPlan.Source.LEGACY_KEYS)
        if (recordICfg?.isUsable == true) return plan(recordICfg, InsulinMigrationPlan.Source.RECORD)
        // Null only if nothing read the store, which the start-up order rules out. Same treatment as an
        // install with no row: v3's own default.
        val insulin = legacy ?: LegacyInsulin(rh.gs(InsulinType.OREF_RAPID_ACTING.label), InsulinType.OREF_RAPID_ACTING.insulinPeakTime, fromExplicitKey = false)
        val dia = harvest.diaFor(activeProfileName)
        // Concentration is 1.0 by construction, not a guess: these records predate concentration entirely,
        // so 1.0 is the identity that leaves historical doses unscaled. The label is left blank on purpose -
        // the insulin list builds it from the nickname and the curve, so there is one naming rule.
        val iCfg = ICfg(
            insulinLabel = "",
            insulinEndTime = ((dia ?: hardLimits.diaRange().endInclusive) * 3600 * 1000).toLong(),
            insulinPeakTime = insulin.peakTime,
            concentration = 1.0
        ).also { it.insulinNickname = insulin.nickname }
        val source = when {
            dia != null               -> InsulinMigrationPlan.Source.LEGACY_KEYS
            insulin.fromExplicitKey   -> InsulinMigrationPlan.Source.GUESSED_DIA
            else                      -> InsulinMigrationPlan.Source.NO_LEGACY_DATA
        }
        return plan(iCfg, source)
    }

    /**
     * The insulin list for a settings file from 3.x, which holds its insulin in the old names and has no list.
     * Null when the file has a list, or holds nothing from 3.x. [PreferenceMigrations] deletes those names, so
     * this reads the file before it runs.
     *
     * The file does not say which profile was running, so when its profiles disagree on the DIA the longest
     * stands in. Modelling choice: a DIA that is too long keeps insulin in IOB for longer, which is the side
     * that gives less. [ImportedInsulin.notice] tells the user whenever the DIA is not the file's one value.
     */
    fun importedInsulin(file: KeyValueStore, catalogueKey: String): ImportedInsulin? {
        if (file.contains(catalogueKey)) return null
        val legacy = readLegacyInsulin(file)
        // The curve fixes its own end, so there is no DIA to choose and nothing to check.
        if (legacy.model == InsulinModel.LYUMJEV_PD) return ImportedInsulin(catalogueOf(lyumjevPdICfg()), notice = null)
        val dias = harvestProfileDia(liveProfiles(file)).nameToDia.values.distinct()
        if (!legacy.fromExplicitKey && dias.isEmpty()) return null
        // The file's age, not this device's: the file is about to set it, and a DIA outside the limits stops the loop.
        val limits = HardLimits.AgeType.entries.firstOrNull { it.name.replace("_", "").equals(file.getString(StringKey.SafetyAge.key, ""), ignoreCase = true) }
            ?.let { HardLimits.LIMIT_DIA.getValue(it) } ?: hardLimits.diaRange()
        val dia = (dias.maxOrNull() ?: limits.endInclusive).coerceIn(limits)
        val iCfg = ICfg(insulinLabel = "", insulinEndTime = (dia * 3600 * 1000).toLong(), insulinPeakTime = legacy.peakTime, concentration = 1.0)
            .also { it.insulinNickname = legacy.nickname }
        return ImportedInsulin(
            catalogue = catalogueOf(iCfg),
            notice = if (dias.singleOrNull() == dia) null else rh.gs(ImplementationStrings.insulin_import_check_dia, dia, legacy.nickname)
        )
    }

    // Concentration stays 1.0 although the plugin may be U200: such a user halved basals and doubled ISFs, so
    // setting 2.0 would change what is delivered. Modelling choice, doses first; see the commit message.
    private fun lyumjevPdICfg() = ICfg(
        insulinLabel = "",
        peak = ICfg.LYUMJEV_PD_NOMINAL_PEAK_MINUTES,
        dia = ICfg.LYUMJEV_PD_END_HOURS,
        concentration = 1.0,
        model = InsulinModel.LYUMJEV_PD
    ).also { it.insulinNickname = LYUMJEV_PD_NICKNAME }

    // A deleted profile only lowers the count: its numbered names stay in the file, and no profile uses them.
    private fun liveProfiles(file: KeyValueStore): KeyValueStore {
        val count = file.getInt(ProfileIntKey.AmountOfProfiles.key, Int.MAX_VALUE)
        val numbered = Regex("^${Constants.LOCAL_PROFILE}_(?:(?:name|dia)_(\\d+)|(\\d+)_(?:name|dia))$")
        return FileKeyValueStore(file.getAll().filterKeys { key ->
            val number = numbered.find(key)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()
            number == null || number < count
        })
    }

    private fun catalogueOf(iCfg: ICfg): String =
        buildJsonObject { put("insulin", buildJsonArray { add(iCfg.toJsonObject()) }) }.toString()

    private fun plan(iCfg: ICfg, source: InsulinMigrationPlan.Source): InsulinMigrationPlan {
        aapsLogger.info(LTag.CORE, "Insulin migration: ${iCfg.peak} min peak, ${iCfg.dia} h DIA, from $source")
        return InsulinMigrationPlan(iCfg, source)
    }
}

/** What [InsulinMigration.importedInsulin] read from a file: the insulin list, and what to tell the user, if anything. */
data class ImportedInsulin(val catalogue: String, val notice: String?)

/**
 * The insulin plugin a v3 install had selected. Everything about it except the DIA, which the profile owned.
 *
 * [fromExplicitKey] is false when no ConfigBuilder row said so and v3's own default was taken instead. The
 * insulin is still right either way; the flag only says how much is left to guess if the DIA is missing too.
 */
data class LegacyInsulin(val nickname: String, val peakTime: Long, val fromExplicitKey: Boolean, val model: InsulinModel = InsulinModel.OREF)

/** Same text as the insulin editor's `insulin_lyumjev_pd_nickname`, which lives in `:ui`, out of reach here. */
private const val LYUMJEV_PD_NICKNAME = "Lyumjev PD"

/** The DIA each v3 local profile was using, and the legacy names those values came from. */
data class ProfileDiaHarvest(val nameToDia: Map<String, Double>, val keysToRemove: List<String>) {

    /**
     * The DIA to use: the running profile's own, or the one value every harvested profile agrees on.
     *
     * The agreement step is what keeps the common cases off the guess. One local profile is the usual setup,
     * and several profiles sharing a DIA is the next most usual; in both the answer is certain even when the
     * running record's name cannot be matched - it was renamed, or it came from Nightscout. Without it those
     * installs took the hard-limit maximum of 9 h, silently, over their whole history.
     */
    fun diaFor(profileName: String?): Double? =
        profileName?.let { nameToDia[it] } ?: nameToDia.values.distinct().singleOrNull()
}

/** The insulin the migration decided on, and how sure it is. */
data class InsulinMigrationPlan(val iCfg: ICfg, val source: Source) {

    enum class Source {

        /** The record's own v3 insulin. Certain. */
        RECORD,

        /** Rebuilt from the v3 insulin plugin selection and a harvested profile DIA. Certain. */
        LEGACY_KEYS,

        /** The right insulin, but no DIA could be found for it, so the hard-limit maximum stands in. */
        GUESSED_DIA,

        /**
         * Neither an insulin row nor a DIA. v3's default insulin and the hard-limit DIA stand in, and
         * nothing here says this is a v3 install at all - a fresh install looks exactly like this, which is
         * why the caller leaves the insulin list alone on this one.
         */
        NO_LEGACY_DATA
    }

    /**
     * True when the user was on a PD plugin but the running record is not PD. 3.4 made no profile switch when the
     * plugin changed, and new boluses take their insulin from the running record, so without a switch they
     * would be oref. [record] is the running insulin as it was before the stamp, null when nothing usable runs.
     */
    fun runningRecordNeedsSwitch(record: ICfg?): Boolean =
        iCfg.model == InsulinModel.LYUMJEV_PD && record != null && record.model != InsulinModel.LYUMJEV_PD

    /** True when the user has to be told, because part of [iCfg] was invented and will not be corrected later. */
    val isGuess: Boolean get() = source == Source.GUESSED_DIA || source == Source.NO_LEGACY_DATA
}
