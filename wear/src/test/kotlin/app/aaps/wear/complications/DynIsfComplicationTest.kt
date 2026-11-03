package app.aaps.wear.complications

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import app.aaps.wear.AAPSLoggerTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
internal class DynIsfComplicationTest {

    private fun sut(): DynIsfComplication =
        Robolectric.buildService(DynIsfComplication::class.java).get().also { it.aapsLogger = AAPSLoggerTest() }

    private fun textFor(variableSens: String): String {
        val sut = sut()
        val preview = sut.getPreviewComplicationData()
        val data = preview.copy(statusData = preview.statusData.copy(variableSens = variableSens))
        val pi = PendingIntent.getActivity(sut, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
        val complication = sut.buildComplicationData(ComplicationType.SHORT_TEXT, data, pi) as ShortTextComplicationData
        return complication.text.getTextAt(sut.resources, Instant.now()).toString()
    }

    @Test
    fun `shows the variable sensitivity sent by the phone`() {
        assertThat(textFor("2.7")).isEqualTo("2.7")
        assertThat(textFor("48")).isEqualTo("48")
    }

    @Test
    fun `shows dashes when the phone sends no value`() {
        assertThat(textFor("")).isEqualTo("--")
    }

    @Test
    fun `an unsupported complication type yields null`() {
        assertThat(sut().getPreviewData(ComplicationType.RANGED_VALUE)).isNull()
    }

    @Test
    fun `tapping the DynISF complication opens the main menu`() {
        assertThat(sut().getComplicationAction()).isEqualTo(ComplicationAction.MENU)
    }

    @Test
    fun `the provider canonical name identifies this complication`() {
        assertThat(sut().getProviderCanonicalName()).contains("DynIsfComplication")
    }
}
