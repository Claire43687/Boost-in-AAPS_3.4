package app.aaps.plugins.aps.openAPSBoostV5

/**
 * Install-time history-gap detection and bounded Nightscout backfill.
 *
 * WHY THIS EXISTS
 * ---------------
 * A user migrating from another AAPS fork lands on a FRESH local database. Every quantity Boost
 * derives from history is then computed from a few days of rows and is silently wrong:
 *  - `tddCalculator` reported 3.1-4.1 U/day against a true ~20, which drove dynamic ISF to
 *    5550-8944 mg/dL/U against a profile ISF of 100 and paralysed dosing for 3.5 h.
 *    (`tddImplausibleForProfile` in OpenAPSBoostPlugin is the SAFETY half of that fix; this is the
 *    DATA half — notice the gap and try to close it.)
 *  - `BoostV5AutoConfig.compute` declines below MIN_DAYS/MIN_BG_READINGS, so the user stays on
 *    factory caps with nothing to say why.
 *
 * WHY 30 DAYS
 * -----------
 * 30 days covers every consumer: the 7-day TDD blend, [BoostV5AutoConfig.LOOKBACK_DAYS] and the
 * 28-day [BoostV5AutoConfig.REDRIVE_LOOKBACK_DAYS]. Whether history is short is still judged over
 * the 14-day lookback, which is what onboarding needs. NSClient's own "full synchronization" already exists for this shape of problem, but it is
 * manual, unbounded (100 days) and also re-uploads everything; this asks for the download half of it,
 * bounded.
 *
 * SAFETY POSTURE
 * --------------
 * Pure decision logic — no I/O, no dosing input. The caller reads the local database off the dose
 * path and, at most, arms an asynchronous NSClient request. The
 * dose path is untouched whether the gap is found, filled, or never closed.
 */
object BoostHistorySync {

    /** Window handed to NSClient. Covers the 7-day TDD blend and auto-config's 14- and 28-day windows. */
    const val BACKFILL_DAYS = 30L

    /** Days carrying a non-zero TDD, out of the 14-day lookback. Mirrors BoostV5AutoConfig.MIN_DAYS. */
    const val MIN_DAYS_WITH_TDD = BoostV5AutoConfig.MIN_DAYS

    /** CGM rows in the 14-day lookback. Mirrors BoostV5AutoConfig.MIN_BG_READINGS (~7 days). */
    const val MIN_BG_READINGS = BoostV5AutoConfig.MIN_BG_READINGS

    /**
     * Boluses in the 14-day lookback. Deliberately slack: a looping user clears this inside a
     * day, and a manual-bolus-only user clears it inside a week, so it fires essentially only on a
     * database that is genuinely near-empty. It is the third of three OR-ed signals, not a gate.
     */
    const val MIN_TREATMENTS = 50

    /** Minimum spacing between looks at the database while history is short. */
    const val CHECK_INTERVAL_MS = 15L * 60 * 1000

    /** How often a running plugin asks whether a look is due. Reads preferences only, so an import is noticed soon. */
    const val WATCH_INTERVAL_MS = 60L * 1000

    /** Minimum spacing between requests. A thin install must not ask on every 5-minute cycle. */
    const val RETRY_COOLDOWN_MS = 6L * 60 * 60 * 1000

    /**
     * Hard cap on requests per install. If the site genuinely has no history there is nothing to
     * fetch, and repeating a 14-day re-download forever would be pure waste. Reset once the gap closes.
     */
    const val MAX_ATTEMPTS = 3

    /**
     * How long after the FIRST time this check runs a backfill may still be requested.
     *
     * The backfill works by asking NSClient for a full-sync-flagged load, and that flag is what
     * bypasses the NsClientAccept* preferences — all of which ship OFF. So each request opens a brief
     * window in which records this phone did not create are accepted. That is the right trade for a
     * genuinely new install and the wrong one later: a user who takes a long pump break, deletes
     * history, or changes sensor would otherwise silently reopen that window months on, and if a second
     * uploader were live on the same Nightscout its records would land during it.
     *
     * 48 h rather than a single shot because a first attempt can hit a dead network, an unconfigured
     * Nightscout, or a phone that is not yet online — all of which leave the user in exactly the state
     * this exists to fix. MAX_ATTEMPTS still bounds it inside the window.
     */
    const val NEW_INSTALL_WINDOW_MS = 48L * 60 * 60 * 1000

    /** Only a build that loops backfills, and it waits for Nightscout to be configured, which an import can do at any moment. */
    fun watchMayLook(apsBuild: Boolean, nsAddress: String?): Boolean = apsBuild && !nsAddress.isNullOrBlank()

    /** What the local database currently holds over the last [BACKFILL_DAYS]. */
    data class History(
        val daysWithTdd: Int,
        val bgReadings: Int,
        val treatments: Int
    )

    /** Persisted counters, round-tripped by the caller through preferences. */
    data class State(
        val attempts: Int,
        val lastAttemptMs: Long,
        val preBgReadings: Int,
        val preTreatments: Int,
        /**
         * When this check first ran on this install (epoch ms; 0 = never). Anchors
         * [NEW_INSTALL_WINDOW_MS]. Deliberately NOT the app install time or the first loop cycle —
         * it is stamped the first time Boost V6 evaluates history, which is the moment from which
         * "this install is new" is meaningful to Boost.
         */
        val firstSeenMs: Long = 0L
    )

    /**
     * True when the stored first-seen date predates this database's oldest bolus, so it describes
     * another phone's install: sync state used to travel in exported settings. An established phone
     * has boluses from before the date and keeps its closed window.
     */
    fun stateFromElsewhere(firstSeenMs: Long, oldestBolusMs: Long?): Boolean =
        firstSeenMs != 0L && (oldestBolusMs == null || oldestBolusMs > firstSeenMs)

    /**
     * Whether another look at the database is worth making. Preference-only, because it runs every
     * cycle. It stops one cooldown after the window, so a request made late in the window can still
     * report what it recovered, and once more outside it to report the closure.
     */
    fun checkDue(state: State, now: Long): Boolean =
        state.firstSeenMs == 0L || now - state.firstSeenMs <= NEW_INSTALL_WINDOW_MS + RETRY_COOLDOWN_MS ||
            (state.lastAttemptMs == 0L && state.attempts == 0)

    /**
     * Auto-config waits while a requested download runs, or it could derive settings from a partial
     * history. Bounded by the cooldown, so a download that never finishes cannot hold it forever.
     */
    fun holdsConfig(backfillActive: Boolean, state: State, now: Long): Boolean =
        backfillActive && now - state.lastAttemptMs < RETRY_COOLDOWN_MS

    /**
     * @param requestBackfill ask NSClient for the bounded re-download.
     * @param summary         breadcrumb to persist + replay into the reason string; null = leave the
     *                        previous one alone (nothing changed worth saying).
     * @param newState        counters to persist; null = leave them alone.
     */
    data class Decision(
        val requestBackfill: Boolean,
        val summary: String?,
        val newState: State?
    )

    fun isSufficient(h: History): Boolean =
        h.daysWithTdd >= MIN_DAYS_WITH_TDD && h.bgReadings >= MIN_BG_READINGS && h.treatments >= MIN_TREATMENTS

    /** Compact "what is missing" fragment, e.g. `days=2/7,bg=430/1500,tr=18/50`. */
    fun shortfall(h: History): String =
        "days=${h.daysWithTdd}/$MIN_DAYS_WITH_TDD,bg=${h.bgReadings}/$MIN_BG_READINGS,tr=${h.treatments}/$MIN_TREATMENTS"

    /**
     * The whole policy, as one pure function.
     *
     * @param nsAvailable an NsClient plugin is enabled and configured (the caller resolves this;
     *                    it never means "the network is up" — nothing here can know that).
     */
    fun decide(
        h: History,
        nsAvailable: Boolean,
        now: Long,
        isoNow: String,
        state: State
    ): Decision {
        // Stamp the anchor on first sight. Done BEFORE the sufficiency check so a user who starts with
        // adequate history still has a defined window if it later degrades — the window then simply
        // expires unused, which is the intended outcome.
        val firstSeen = if (state.firstSeenMs == 0L) now else state.firstSeenMs
        val stamped = state.copy(firstSeenMs = firstSeen)
        val windowOpen = now - firstSeen <= NEW_INSTALL_WINDOW_MS

        if (isSufficient(h)) {
            // Nothing was ever attempted -> stay silent. Users with normal history get no breadcrumb
            // and no preference writes at all.
            if (state.attempts <= 0)
                return Decision(false, null, if (state.firstSeenMs == 0L) stamped else null)
            // A backfill was attempted and history is now adequate: report what it recovered, ONCE.
            // Zeroing the counters is what closes the episode — the reported line then persists and
            // keeps being replayed, but this branch cannot fire again.
            val addedBg = h.bgReadings - state.preBgReadings
            val addedTr = h.treatments - state.preTreatments
            return Decision(
                requestBackfill = false,
                summary = "filled:${BACKFILL_DAYS}d,treatments=${signed(addedTr)},bg=${signed(addedBg)}@$isoNow",
                newState = State(
                    attempts = 0, lastAttemptMs = 0, preBgReadings = 0, preTreatments = 0,
                    // Keep the anchor: the episode is closed, and the window must not restart.
                    firstSeenMs = firstSeen
                )
            )
        }

        // History is short. A request is followed by the cooldown, so an install cannot ask every cycle.
        if (state.lastAttemptMs != 0L && now - state.lastAttemptMs < RETRY_COOLDOWN_MS)
            return Decision(false, null, if (state.firstSeenMs == 0L) stamped else null)

        // Past the new-install window: never request again, whatever the history looks like. Report the
        // closure ONCE (guarded by lastAttemptMs, which the cooldown above then suppresses) so a thin
        // install is not silently abandoned, then stay quiet forever.
        if (!windowOpen) {
            if (state.lastAttemptMs == 0L && state.attempts == 0)
                return Decision(
                    requestBackfill = false,
                    summary = "skipped:outside-new-install-window,${shortfall(h)}@$isoNow",
                    newState = stamped.copy(lastAttemptMs = now)
                )
            return Decision(false, null, if (state.firstSeenMs == 0L) stamped else null)
        }

        if (!nsAvailable)
            return Decision(
                requestBackfill = false,
                summary = "skipped:ns-unavailable,${shortfall(h)}@$isoNow",
                // Neither an attempt nor the cooldown: a user who configures Nightscout a minute later
                // gets the sync on the next check, not six hours on.
                newState = stamped
            )

        if (state.attempts >= MAX_ATTEMPTS)
            return Decision(
                requestBackfill = false,
                summary = "exhausted:${state.attempts},${shortfall(h)}@$isoNow",
                newState = stamped.copy(lastAttemptMs = now)
            )

        return Decision(
            requestBackfill = true,
            summary = "requested:${BACKFILL_DAYS}d,attempt=${state.attempts + 1}/$MAX_ATTEMPTS,${shortfall(h)}@$isoNow",
            newState = State(
                attempts = state.attempts + 1,
                lastAttemptMs = now,
                // Snapshot taken BEFORE the fetch, so the eventual "filled" line reports a real delta.
                preBgReadings = h.bgReadings,
                preTreatments = h.treatments,
                firstSeenMs = firstSeen
            )
        )
    }

    private fun signed(v: Int) = if (v >= 0) "+$v" else "$v"
}
