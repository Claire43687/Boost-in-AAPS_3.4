package app.aaps.core.nssdk.localmodel.treatment

import kotlinx.serialization.Serializable

@Serializable
data class NSICfg(
    val insulinLabel: String,
    val insulinEndTime: Long,
    val insulinPeakTime: Long,
    val concentration: Double,
    /** Curve model name, null for oref (see `RemoteICfg.insulinModel`). */
    val insulinModel: String? = null
)