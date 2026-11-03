package app.aaps.core.interfaces.overview

/**
 * Boost V5 meal-hypothesis state machine. Replaces the tier model for the V5 engine.
 * [verb] is the one-word action shown on the chip; [colorHex] the chip colour
 * (grey idle -> amber watching -> orange-red acting -> orange covering -> teal easing).
 * Values copied verbatim from the live BoostOverviewHelper source.
 */
enum class BoostV5State(val label: String, val verb: String, val short: String, val colorHex: Long) {
    IDLE("Idle", "idle", "IDLE", 0xFF78909C),                  // blue-grey
    OBSERVING("Observing", "watching", "OBSERVE", 0xFFFFC107), // amber
    CONFIRMED("Confirmed", "acting", "CONFIRM", 0xFFFF6E40),   // orange-red
    COMMITTED("Committed", "covering", "COMMIT", 0xFFFF9800),  // orange
    RECOVERING("Recovering", "easing", "RECOVER", 0xFF26C6DA); // teal

    companion object {

        fun fromName(text: String?): BoostV5State =
            entries.firstOrNull { it.name.equals(text?.trim(), ignoreCase = true) } ?: IDLE
    }
}
