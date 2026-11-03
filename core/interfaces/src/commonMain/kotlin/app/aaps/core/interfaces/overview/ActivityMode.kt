package app.aaps.core.interfaces.overview

/**
 * Boost activity / exercise mode parsed from the algorithm scriptDebug.
 * Values copied verbatim from the live BoostOverviewHelper source.
 */
enum class ActivityMode(val label: String) {
    NORMAL("Normal"),
    ACTIVE("Active"),
    INACTIVE("Inactive"),
    SLEEP_IN("Sleep-in"),
    BOOST_OFF("Boost off")
}
