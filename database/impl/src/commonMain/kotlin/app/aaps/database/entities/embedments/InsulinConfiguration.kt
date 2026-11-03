package app.aaps.database.entities.embedments

import androidx.room.ColumnInfo

data class InsulinConfiguration(
    var insulinLabel: String,
    var insulinEndTime: Long,  // DIA before [milliseconds]
    var insulinPeakTime: Long, // [milliseconds]  ** Here I propose to rename this value to be consistent with content in milliseconds (and not in min for peak everywhere else)
    var concentration: Double,  // multiplication factor, 1.0 for U100, 2.0 for U200...
    /**
     * Name of the curve model (`InsulinModel` in core:data). Added in schema 37; the migration fills existing
     * rows with the default, OREF, which is the curve all of them were computed with.
     */
    @ColumnInfo(defaultValue = INSULIN_MODEL_DEFAULT)
    var insulinModel: String = INSULIN_MODEL_DEFAULT
) {

    companion object {

        const val INSULIN_MODEL_DEFAULT = "OREF"
    }
}
