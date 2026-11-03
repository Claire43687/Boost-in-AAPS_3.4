package app.aaps.database.persistence.converters

import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.InsulinModel
import app.aaps.database.entities.embedments.InsulinConfiguration

fun InsulinConfiguration.fromDb(): ICfg =
    ICfg(
        insulinLabel = this.insulinLabel,
        insulinEndTime = this.insulinEndTime,
        insulinPeakTime = this.insulinPeakTime,
        concentration = this.concentration,
        model = InsulinModel.fromStored(this.insulinModel)
    )

fun ICfg.toDb(): InsulinConfiguration =
    InsulinConfiguration(
        insulinLabel = this.insulinLabel,
        insulinEndTime = this.insulinEndTime,
        insulinPeakTime = this.insulinPeakTime,
        concentration = this.concentration,
        insulinModel = this.model.name
    )
