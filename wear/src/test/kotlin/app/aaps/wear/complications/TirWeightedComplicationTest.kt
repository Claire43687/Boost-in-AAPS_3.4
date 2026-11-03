package app.aaps.wear.complications

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.WeightedElementsComplicationData
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
internal class TirWeightedComplicationTest {

    private fun sut(): TirWeightedComplication =
        Robolectric.buildService(TirWeightedComplication::class.java).get().also { it.aapsLogger = AAPSLoggerTest() }

    private fun build(sut: TirWeightedComplication, tirWeights: String): WeightedElementsComplicationData {
        val preview = sut.getPreviewComplicationData()
        val data = preview.copy(statusData = preview.statusData.copy(tirWeights = tirWeights))
        val pi = PendingIntent.getActivity(sut, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
        return sut.buildComplicationData(ComplicationType.WEIGHTED_ELEMENTS, data, pi) as WeightedElementsComplicationData
    }

    @Test
    fun `five comma separated numbers parse to five weights`() {
        assertThat(TirWeightedComplication.parseWeights("1,4,80,12,3")).containsExactly(1f, 4f, 80f, 12f, 3f).inOrder()
        assertThat(TirWeightedComplication.parseWeights(" 0, 0 ,100,0,0 ")).containsExactly(0f, 0f, 100f, 0f, 0f).inOrder()
    }

    @Test
    fun `blank or malformed weights parse to null`() {
        assertThat(TirWeightedComplication.parseWeights("")).isNull()
        assertThat(TirWeightedComplication.parseWeights("   ")).isNull()
        assertThat(TirWeightedComplication.parseWeights("1,2,3,4")).isNull()
        assertThat(TirWeightedComplication.parseWeights("1,2,3,4,5,6")).isNull()
        // A non-numeric part is dropped, which leaves four and so rejects the whole string
        assertThat(TirWeightedComplication.parseWeights("1,x,80,12,3")).isNull()
    }

    @Test
    fun `centre text is the in-range percentage`() {
        assertThat(TirWeightedComplication.inRangeText(listOf(1f, 4f, 80f, 12f, 3f))).isEqualTo("80%")
        assertThat(TirWeightedComplication.inRangeText(null)).isEqualTo("--")
    }

    @Test
    fun `phone weights become five coloured elements`() {
        val sut = sut()
        val data = build(sut, "1,4,80,12,3")
        assertThat(data.elements.map { it.weight }).containsExactly(1f, 4f, 80f, 12f, 3f).inOrder()
        assertThat(data.elements.map { it.color }.toSet()).hasSize(5)
        assertThat(data.text!!.getTextAt(sut.resources, Instant.now()).toString()).isEqualTo("80%")
    }

    @Test
    fun `no data gives a single grey ring`() {
        assertThat(build(sut(), "").elements).hasSize(1)
        // All-zero weights are treated as no data too
        assertThat(build(sut(), "0,0,0,0,0").elements).hasSize(1)
    }

    @Test
    fun `preview builds a weighted-elements complication`() {
        assertThat(sut().getPreviewData(ComplicationType.WEIGHTED_ELEMENTS)).isInstanceOf(WeightedElementsComplicationData::class.java)
    }

    @Test
    fun `an unsupported complication type yields null`() {
        assertThat(sut().getPreviewData(ComplicationType.SHORT_TEXT)).isNull()
    }

    @Test
    fun `the provider canonical name identifies this complication`() {
        assertThat(sut().getProviderCanonicalName()).contains("TirWeightedComplication")
    }
}
