package app.aaps.core.data.model

/**
 * The shape of the insulin action curve that [ICfg.iobCalcForTreatment] uses.
 *
 * Stored by name: in the database (`insulinModel` column of boluses, profileSwitches and
 * effectiveProfileSwitches), in the insulin catalogue JSON and in the Nightscout `icfg` object.
 * The names are therefore part of the stored format and must not be renamed.
 */
enum class InsulinModel {

    /** The oref bilinear curve, set by peak time and DIA. The default, and the only curve before this field existed. */
    OREF,

    /**
     * The Lyumjev pharmacodynamic curve from Boost 6.1.2: a Gaussian activity curve whose peak time depends on
     * the size of the dose, with a fixed end at 8 hours. Peak and DIA of the [ICfg] are not used by the curve.
     */
    LYUMJEV_PD;

    companion object {

        /**
         * Reads a stored name. A missing value is a record written before the field existed, so it is [OREF].
         * An unknown value (a newer version's curve) also falls back to [OREF], the curve every version knows.
         */
        fun fromStored(name: String?): InsulinModel = entries.firstOrNull { it.name == name } ?: OREF
    }
}
