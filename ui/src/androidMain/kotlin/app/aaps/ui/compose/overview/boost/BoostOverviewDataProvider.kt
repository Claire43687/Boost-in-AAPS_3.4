package app.aaps.ui.compose.overview.boost

import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.overview.ActivityMode
import app.aaps.core.interfaces.overview.BoostTier
import app.aaps.core.interfaces.overview.BoostV5Brake
import app.aaps.core.interfaces.overview.BoostV5State
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.DateUtil
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Extracts Boost-specific algorithm data from the last APS run result.
 * Parses APSResult JSON for decision tier, DynISF, TDD, activity mode.
 *
 * Uses a time-based cache (30s expiry) to avoid repeated heavy DB queries
 * (TDD calculations, therapy record lookups) on every UI event.
 *
 * Port of `BoostOverviewHelper` (Boost 6.1.2 on AAPS 3.4) to AAPS 4.0. The 4.0 TDD and persistence
 * APIs are `suspend`, so [getBoostStatus] is `suspend` here (the 3.4 version was blocking).
 * `APSResult.json()` is a kotlinx [JsonObject] in 4.0 instead of an org.json object; [optDouble] and
 * [optInt] below keep the org.json defaults, so a missing key still reads as 0 and the RT fallback
 * is still only reached when there is no JSON at all, as in 3.4.
 */
@SingleIn(AppScope::class)
@Inject
class BoostOverviewDataProvider(
    private val loop: Loop,
    private val processedDeviceStatusData: ProcessedDeviceStatusData,
    private val iobCobCalculator: IobCobCalculator,
    private val tddCalculator: TddCalculator,
    private val persistenceLayer: PersistenceLayer,
    private val dateUtil: DateUtil
) {

    // Cache to avoid recalculating TDD and querying DB on every UI event
    @Volatile private var cachedStatus: BoostStatus? = null
    @Volatile private var cachedAt: Long = 0L

    /**
     * Returns cached status if less than CACHE_TTL_MS old, otherwise recalculates.
     * Call [invalidate] when you know the underlying data has changed (e.g. new loop run).
     */
    suspend fun getBoostStatus(): BoostStatus {
        val now = System.currentTimeMillis()
        cachedStatus?.let { cached ->
            if (now - cachedAt < CACHE_TTL_MS) return cached
        }
        val status = computeBoostStatus()
        cachedStatus = status
        cachedAt = now
        return status
    }

    /** Force next [getBoostStatus] call to recalculate. */
    fun invalidate() {
        cachedAt = 0L
    }

    private suspend fun computeBoostStatus(): BoostStatus {
        val request = loop.lastRun?.request
        // The live RT is held inside the APSResult wrapper (DetermineBasalResult.rawData()); the
        // APSResult itself is NOT an RT (that legacy `request as? RT` cast could never succeed in 4.0
        // and is dropped). Pull rawData() first; AAPSClient fallback = NS-synced suggested RT.
        val rt: RT? = (request?.rawData() as? RT) ?: processedDeviceStatusData.openAPSData.suggested
        val json = try { request?.json() } catch (_: Exception) { null }
        val reason = request?.reason ?: rt?.reason?.toString() ?: ""
        val scriptDebug = request?.scriptDebug ?: rt?.consoleLog ?: emptyList()
        val debugText = scriptDebug.joinToString("\n")
        // Combine all text sources for parsing — scriptDebug, reason, and JSON reason field
        val allText = "$debugText\n$reason"

        // Parse tier from all available text (e.g. "Tier 5 - Percent Scale")
        val tierResult = parseTierFromText(allText) ?: Pair(BoostTier.REGULAR_OREF1, BoostTier.REGULAR_OREF1.label)
        val tier = tierResult.first
        val tierLabel = tierResult.second

        val deltaAccl = json?.optDouble("delta_accl", 0.0) ?: rt?.deltaAcceleration ?: 0.0

        // Parse activity/exercise mode from all text (e.g. "Inactive - 140% profile")
        val activityResult = parseActivityFromText(allText)
        val activityMode = activityResult.first
        val activityDetail = activityResult.second

        val profilePct = json?.optInt("current_profile_percentage", 0) ?: rt?.boostProfileSwitch ?: 0
        // Parse profile % from scriptDebug header: "Boost V2 (...) | Profile: 140%"
        val profileFromDebug = PROFILE_HEADER_REGEX.find(debugText)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val effectiveProfilePct = when {
            profilePct > 0       -> profilePct
            profileFromDebug > 0 -> profileFromDebug
            else                 -> 100
        }

        val tdds7d = tddCalculator.calculate(7L, allowMissingDays = true)
        val avg7d = tddCalculator.averageTDD(tdds7d)
        val tdd7d = avg7d?.data?.totalAmount ?: 0.0
        val tdd24h = tddCalculator.calculateDaily(-24L, 0L)?.totalAmount ?: 0.0

        val lastBg = iobCobCalculator.ads.actualBg()?.recalculated ?: 0.0

        // Use actual DynISF values from the running APS algorithm, with NS RT fallback
        // Boost stores its profile in oapsProfileBoost; oapsProfile is empty on a Boost result.
        val oapsProfile = request?.oapsProfileBoost
        val variableSens = request?.variableSens ?: rt?.variable_sens ?: 0.0
        val apsTdd = oapsProfile?.TDD ?: rt?.tdd ?: 0.0
        val apsInsulinDivisor = oapsProfile?.insulinDivisor ?: 0

        // Use the algorithm's actual target
        val targetBgMgdl = request?.targetBG ?: rt?.targetBG ?: 0.0

        // Autosens ratio
        val autosensRatio = request?.autosensResult?.ratio ?: rt?.sensitivityRatio ?: 1.0

        // Fast-carb protection state
        val fastCarbProtection = rt?.fastCarbProtection ?: false

        // Try to parse TDD from scriptDebug (Boost may write "TDD: 48.3" or similar)
        val tddFromDebug = parseTddFromText(allText)

        // ── Boost V5 state machine (active-only) ──
        // The engine stamps rt.boostV5_active=true only when V5 is the selected/active doser (not
        // shadow). This is R8-safe (a serialized flag, not a class name) and needs no plugin coupling.
        val v5Active = rt?.boostV5_active == true
        val v5State = BoostV5State.fromName(rt?.boostV5_state)
        // Inside this branch rt is smart-cast non-null (v5Active implies rt != null).
        val v5Brakes = if (v5Active) parseGateReductions(rt.boostV5_gateReduction) else emptyList()

        return BoostStatus(
            tier = tier, tierLabel = tierLabel, tierReason = reason,
            dynIsfValue = variableSens,
            tdd7d = tdd7d, tdd24h = tdd24h,
            tddWeighted = apsTdd,
            tddFromDebug = tddFromDebug,
            insulinDivisor = apsInsulinDivisor,
            lastBg = lastBg,
            targetBgMgdl = targetBgMgdl,
            autosensRatio = autosensRatio,
            activityMode = activityMode, activityDetail = activityDetail,
            profilePercentage = effectiveProfilePct,
            deltaAccl = deltaAccl, variableSens = variableSens,
            scriptDebugText = debugText,
            cannulaAgeDays = getAgeDays(TE.Type.CANNULA_CHANGE),
            sensorAgeDays = getAgeDays(TE.Type.SENSOR_CHANGE),
            fastCarbProtection = fastCarbProtection,
            deviationSensRatio = rt?.deviationSensRatio,
            deviationSensSource = rt?.deviationSensSource ?: "none",
            deviationSensClean = rt?.deviationSensClean ?: 0,
            deviationSensTotal = rt?.deviationSensTotal ?: 0,
            sensNormalTarget = rt?.sensNormalTarget ?: 0.0,
            isV5Active = v5Active,
            v5State = v5State,
            v5Age = rt?.boostV5_age ?: 0,
            v5Score = rt?.boostV5_score ?: 0.0,
            v5ActionMult = rt?.boostV5_actionMult ?: 1.0,
            v5Budget = rt?.boostV5_budget ?: 0.0,
            v5FinalDose = rt?.boostV5_finalDose ?: 0.0,
            v5Brakes = v5Brakes,
            v5GateRaw = rt?.boostV5_gateReduction ?: "",
        )
    }

    /** Parse "Tier N - Label" from combined text. Returns (BoostTier, display label) or null. */
    private fun parseTierFromText(text: String): Pair<BoostTier, String>? {
        val match = TIER_REGEX.find(text) ?: return null
        val tierNum = match.groupValues[1]
        val tierName = match.groupValues[2].trim()
        // Trim at sentence boundary if reason has extra text after tier label
        val cleanName = tierName.split(TIER_NAME_SPLIT_REGEX).firstOrNull()?.trim()
            ?.trimEnd('<', '>', ' ') ?: tierName
        val label = "Tier $tierNum - $cleanName"
        val tier = BoostTier.fromLabel(cleanName)
        return Pair(tier, label)
    }

    /** Parse activity/exercise mode from Boost scriptDebug.
     *  Formats:
     *    "✓ BOOST ACTIVE (INACTIVE)"  → Boost running, exercise=inactive, profile adjusted
     *    "✓ BOOST ACTIVE (ACTIVE)"    → Boost running, exercise=active
     *    "✗ BOOST INACTIVE: Outside boost time window" → Boost off
     *    "✗ BOOST INACTIVE: Sleep-in (...)" → Sleep-in mode */
    private fun parseActivityFromText(text: String): Pair<ActivityMode, String> {
        // Case 1: "BOOST ACTIVE (INACTIVE)" or "BOOST ACTIVE (ACTIVE)" etc.
        val activeMatch = BOOST_ACTIVE_REGEX.find(text)
        if (activeMatch != null) {
            // The engine's state name, which may be HR_UNAVAILABLE or HR_ELEVATED.
            val modeName = activeMatch.groupValues[1].trim().replace('_', ' ')
            // Extract profile percentage from "→ profile 140%"
            val pct = PROFILE_PCT_REGEX.find(text)?.groupValues?.get(1) ?: ""
            val mode = when (modeName.uppercase()) {
                "INACTIVE"             -> ActivityMode.INACTIVE
                "ACTIVE"               -> ActivityMode.ACTIVE
                "SLEEP-IN", "SLEEP IN" -> ActivityMode.SLEEP_IN
                else                   -> ActivityMode.NORMAL
            }
            val detail = if (pct.isNotEmpty()) "$modeName - $pct%" else modeName
            return Pair(mode, detail)
        }

        // Case 2: "BOOST INACTIVE: Sleep-in (...)"
        if (SLEEP_IN_REGEX.containsMatchIn(text)) {
            return Pair(ActivityMode.SLEEP_IN, "Sleep-in")
        }

        // Case 3: "BOOST INACTIVE: Outside boost time window"
        if (OUTSIDE_WINDOW_REGEX.containsMatchIn(text)) {
            return Pair(ActivityMode.BOOST_OFF, "Outside window")
        }

        // Case 4: Any other "BOOST INACTIVE: <reason>"
        val inactiveMatch = BOOST_INACTIVE_REGEX.find(text)
        if (inactiveMatch != null) {
            val reason = inactiveMatch.groupValues[1].trim().split("\n").first().trim()
            return Pair(ActivityMode.BOOST_OFF, reason)
        }

        return Pair(ActivityMode.NORMAL, "Normal")
    }

    /** Parse TDD from Boost scriptDebug.
     *  Priority: "Final TDD=31.5" > "Blended TDD=31.5" > "TDD: 31.5" */
    private fun parseTddFromText(text: String): Double {
        // Most specific: "Final TDD=31.5"
        FINAL_TDD_REGEX.find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.let { return it }

        // Next: "Blended TDD=31.5"
        BLENDED_TDD_REGEX.find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.let { return it }

        // Fallback: first "TDD: 31.5" (the algorithm's working TDD line)
        TDD_COLON_REGEX.find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.let { return it }

        return 0.0
    }

    private suspend fun getAgeDays(type: TE.Type): Double {
        val event = persistenceLayer.getLastTherapyRecordUpToNow(type)
        return if (event != null) (dateUtil.now() - event.timestamp) / (24.0 * 60 * 60 * 1000) else -1.0
    }

    companion object {

        /** Cache TTL — [getBoostStatus] returns cached result within this window */
        private const val CACHE_TTL_MS = 30_000L  // 30 seconds

        /**
         * Parse `boostV5_gateReduction` into displayable brakes. Pure/static so it is unit-testable.
         * Format is a comma-separated list of:
         *   `HARD:<reason>`         → binary disable (isHard, factor 0)
         *   `maxIOB` / `spike`      → a hard clamp (isHard, factor 0)
         *   `<name>:<factor>`       → soft brake; only surfaced when it actually bit (factor < ~1.0)
         *   `none`                  → ignored
         * Returns [] for null/empty/"none". Hard gates first, then strongest soft brakes.
         */
        fun parseGateReductions(raw: String?): List<BoostV5Brake> {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty() || text.equals("none", ignoreCase = true)) return emptyList()
            val brakes = mutableListOf<BoostV5Brake>()
            for (tokenRaw in text.split(',')) {
                val token = tokenRaw.trim()
                if (token.isEmpty() || token.equals("none", ignoreCase = true)) continue
                when {
                    token.startsWith("HARD:", ignoreCase = true) ->
                        brakes += BoostV5Brake(token.substringAfter(':').trim(), 0.0, isHard = true)

                    token.equals("maxIOB", ignoreCase = true)    ->
                        brakes += BoostV5Brake("maxIOB", 0.0, isHard = true)

                    token.equals("spike", ignoreCase = true)     ->
                        brakes += BoostV5Brake("spike", 0.0, isHard = true)

                    token.contains(':')                          -> {
                        val name = token.substringBefore(':').trim()
                        val factor = token.substringAfter(':').trim().toDoubleOrNull() ?: continue
                        if (factor < 0.999) brakes += BoostV5Brake(name, factor, isHard = false)
                    }
                }
            }
            // Hard gates first; among soft brakes, the strongest (lowest factor) first.
            return brakes.sortedWith(compareByDescending<BoostV5Brake> { it.isHard }.thenBy { it.factor })
        }

        /** org.json `optDouble`: the value as a number, or [default] when missing or not numeric. */
        private fun JsonObject.optDouble(key: String, default: Double): Double {
            val primitive = this[key] as? JsonPrimitive ?: return default
            return primitive.doubleOrNull ?: default
        }

        /** org.json `optInt`: an integer, a number truncated to an integer, or [default]. */
        private fun JsonObject.optInt(key: String, default: Int): Int {
            val primitive = this[key] as? JsonPrimitive ?: return default
            return primitive.intOrNull ?: primitive.doubleOrNull?.toInt() ?: default
        }

        // Pre-compiled regexes (avoid recompilation on every getBoostStatus() call)
        private val TIER_REGEX = Regex("""tier\s+(\d+)\s*[-:]\s*(.+)""", RegexOption.IGNORE_CASE)
        private val BOOST_ACTIVE_REGEX = Regex("""BOOST\s+ACTIVE\s*\((\w[\w\s-]*)\)""", RegexOption.IGNORE_CASE)
        private val PROFILE_PCT_REGEX = Regex("""(?:→\s*)?profile\s+(\d+)%""", RegexOption.IGNORE_CASE)
        private val SLEEP_IN_REGEX = Regex("""BOOST\s+INACTIVE\s*:\s*Sleep-?in""", RegexOption.IGNORE_CASE)
        private val OUTSIDE_WINDOW_REGEX = Regex("""BOOST\s+INACTIVE\s*:\s*Outside\s+boost\s+time\s+window""", RegexOption.IGNORE_CASE)
        private val BOOST_INACTIVE_REGEX = Regex("""BOOST\s+INACTIVE\s*:\s*(.+)""", RegexOption.IGNORE_CASE)
        private val FINAL_TDD_REGEX = Regex("""Final\s+TDD\s*=\s*([\d.]+)""", RegexOption.IGNORE_CASE)
        private val BLENDED_TDD_REGEX = Regex("""Blended\s+TDD\s*=\s*([\d.]+)""", RegexOption.IGNORE_CASE)
        private val TDD_COLON_REGEX = Regex("""(?i)\bTDD\b\s*:\s*([\d.]+)""")
        private val TIER_NAME_SPLIT_REGEX = Regex("""[.;,\n]""")
        private val PROFILE_HEADER_REGEX = Regex("""Profile:\s*(\d+)%""")
    }
}
