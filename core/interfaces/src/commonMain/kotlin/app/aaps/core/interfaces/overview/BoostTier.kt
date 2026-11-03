package app.aaps.core.interfaces.overview

/**
 * Boost decision tiers. Each carries a display [label] and a [colorHex] (ARGB Long) used to
 * theme the tier chip. Values copied verbatim from the live BoostOverviewHelper source.
 */
enum class BoostTier(val label: String, val colorHex: Long) {
    BOOST_BOLUS("Boost Bolus", 0xFFE040FB),
    HIGH_BOOST("High Boost", 0xFFFF5252),
    PERCENT_SCALE("Percent Scale", 0xFFFF9800),
    UAM_BOOST("UAM Boost", 0xFFFFC107),
    ACCELERATION("Acceleration", 0xFFFFAB40),
    ENHANCED_OREF1("Enhanced oref1", 0xFF42A5F5),
    REGULAR_OREF1("Regular oref1", 0xFF66BB6A),
    INACTIVE("Inactive", 0xFF78909C);

    companion object {

        fun fromLabel(text: String): BoostTier {
            val t = text.lowercase()
            return when {
                t.contains("boost bolus") && !t.contains("high")               -> BOOST_BOLUS
                t.contains("high boost")                                        -> HIGH_BOOST
                t.contains("percent scale") || t.contains("pctscale")           -> PERCENT_SCALE
                t.contains("uam boost")                                         -> UAM_BOOST
                t.contains("acceleration bolus") || t.contains("acceleration")  -> ACCELERATION
                t.contains("enhanced oref1")                                    -> ENHANCED_OREF1
                t.contains("regular oref1") || t.contains("oref1 smb")          -> REGULAR_OREF1
                t.contains("outside boost") || t.contains("sleep-in")           -> INACTIVE
                else                                                            -> REGULAR_OREF1
            }
        }
    }
}
