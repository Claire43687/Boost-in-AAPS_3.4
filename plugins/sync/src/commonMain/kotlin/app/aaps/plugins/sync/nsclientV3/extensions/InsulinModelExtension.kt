package app.aaps.plugins.sync.nsclientV3.extensions

import app.aaps.core.data.model.InsulinModel

/**
 * The `icfg.insulinModel` value uploaded to Nightscout: null for oref, so the key is left out and an oref
 * treatment is uploaded exactly as before the field existed. Reading it back goes through
 * [InsulinModel.fromStored], which turns a missing key into oref.
 */
internal fun InsulinModel.toNsInsulinModel(): String? = if (this == InsulinModel.OREF) null else name
