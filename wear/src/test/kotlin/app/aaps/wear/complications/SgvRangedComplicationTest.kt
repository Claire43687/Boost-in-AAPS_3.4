package app.aaps.wear.complications

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import app.aaps.wear.AAPSLoggerTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
internal class SgvRangedComplicationTest {

    private fun sut(): SgvRangedComplication =
        Robolectric.buildService(SgvRangedComplication::class.java).get().also {
            it.aapsLogger = AAPSLoggerTest()
            it.sp = mock()
            whenever(it.sp.getBoolean(any<Int>(), any())).thenReturn(false)
            whenever(it.sp.getBoolean(any<String>(), any())).thenReturn(true)
        }

    private fun rangedValueFor(sgv: Double): RangedValueComplicationData {
        val sut = sut()
        val preview = sut.getPreviewComplicationData()
        val data = preview.copy(bgData = preview.bgData.copy(sgv = sgv))
        val pi = PendingIntent.getActivity(sut, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
        return sut.buildComplicationData(ComplicationType.RANGED_VALUE, data, pi) as RangedValueComplicationData
    }

    @Test
    fun `the ranged value is the BG in mg per dl`() {
        val data = rangedValueFor(120.0)
        assertThat(data.value).isEqualTo(120f)
        assertThat(data.min).isEqualTo(SgvRangedComplication.BG_MIN)
        assertThat(data.max).isEqualTo(SgvRangedComplication.BG_MAX)
    }

    @Test
    fun `the ranged value is clamped to the ring range`() {
        assertThat(rangedValueFor(20.0).value).isEqualTo(SgvRangedComplication.BG_MIN)
        assertThat(rangedValueFor(500.0).value).isEqualTo(SgvRangedComplication.BG_MAX)
    }

    @Test
    fun `preview builds both supported types`() {
        assertThat(sut().getPreviewData(ComplicationType.RANGED_VALUE)).isInstanceOf(RangedValueComplicationData::class.java)
        assertThat(sut().getPreviewData(ComplicationType.SHORT_TEXT)).isInstanceOf(ShortTextComplicationData::class.java)
    }

    @Test
    fun `an unsupported complication type yields null`() {
        assertThat(sut().getPreviewData(ComplicationType.LONG_TEXT)).isNull()
    }

    @Test
    fun `the provider canonical name identifies this complication`() {
        assertThat(sut().getProviderCanonicalName()).contains("SgvRangedComplication")
    }

    @Test
    fun `no reading gives no ranged value rather than 40`() {
        val sut = sut()
        val preview = sut.getPreviewComplicationData()
        val data = preview.copy(bgData = preview.bgData.copy(sgv = 0.0))
        val pi = PendingIntent.getActivity(sut, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
        assertThat(sut.buildComplicationData(ComplicationType.RANGED_VALUE, data, pi)).isInstanceOf(NoDataComplicationData::class.java)
    }

    @Test
    fun `a stale reading gives no ranged value and keeps its text form`() {
        val sut = sut()
        val data = sut.getPreviewComplicationData()
        val pi = PendingIntent.getActivity(sut, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
        assertThat(sut.buildOutdatedComplicationData(ComplicationType.RANGED_VALUE, data, pi)).isInstanceOf(NoDataComplicationData::class.java)
        assertThat(sut.buildOutdatedComplicationData(ComplicationType.SHORT_TEXT, data, pi)).isInstanceOf(ShortTextComplicationData::class.java)
    }
}
