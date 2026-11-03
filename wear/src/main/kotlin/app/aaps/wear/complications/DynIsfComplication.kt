package app.aaps.wear.complications

import android.app.PendingIntent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import app.aaps.core.interfaces.logging.LTag
import app.aaps.wear.data.ComplicationData as ComplicationStore

/**
 * DynISF Complication (Boost)
 *
 * Shows the current dynamic ISF, the variable_sens of the last APS run, formatted on the phone in
 * the user's profile units (mg/dl/U or mmol/L/U). Display only: this value never influences dosing.
 * Title is "ISF"; text is the value, or "--" when the phone has none.
 * Tap opens the main menu.
 */
class DynIsfComplication : ModernBaseComplicationProviderService() {

    override fun buildComplicationData(
        type: ComplicationType,
        data: ComplicationStore,
        complicationPendingIntent: PendingIntent
    ): ComplicationData? {
        return when (type) {
            ComplicationType.SHORT_TEXT -> {
                val sens = data.statusData.variableSens.ifEmpty { "--" }
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(text = sens).build(),
                    contentDescription = PlainComplicationText.Builder(text = "DynISF $sens").build()
                )
                    .setTitle(PlainComplicationText.Builder(text = "ISF").build())
                    .setTapAction(complicationPendingIntent)
                    .build()
            }

            else                        -> {
                aapsLogger.warn(LTag.WEAR, "Unexpected complication type $type")
                null
            }
        }
    }

    override fun getProviderCanonicalName(): String = DynIsfComplication::class.java.canonicalName!!
    override fun getComplicationAction(): ComplicationAction = ComplicationAction.MENU
}
