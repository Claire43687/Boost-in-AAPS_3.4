package app.aaps.core.interfaces.overview

/**
 * A single Phase-3 brake/gate that fired this cycle.
 * [isHard] = binary disable (red stop); soft brakes carry a [factor] < ~1.0.
 */
data class BoostV5Brake(val label: String, val factor: Double, val isHard: Boolean)
