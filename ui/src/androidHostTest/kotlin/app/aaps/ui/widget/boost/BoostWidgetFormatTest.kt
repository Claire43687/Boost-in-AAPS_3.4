package app.aaps.ui.widget.boost

import app.aaps.core.interfaces.overview.ActivityMode
import app.aaps.core.interfaces.overview.BoostTier
import app.aaps.core.interfaces.overview.BoostV5State
import app.aaps.ui.compose.overview.boost.BoostStatus
import app.aaps.ui.widget.boost.BoostWidgetFormat.TargetSource
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

/** The Boost widget's display rules, checked against the values the 6.1.2 widget used. */
class BoostWidgetFormatTest {

    private val locale = Locale.US

    @Test
    fun `zone colour follows the five 6_1_2 glucose bands`() {
        assertThat(BoostWidgetFormat.zoneColor(251.0)).isEqualTo(0xFFFF1744.toInt())
        assertThat(BoostWidgetFormat.zoneColor(250.0)).isEqualTo(0xFFFFEB3B.toInt())
        assertThat(BoostWidgetFormat.zoneColor(181.0)).isEqualTo(0xFFFFEB3B.toInt())
        assertThat(BoostWidgetFormat.zoneColor(180.0)).isEqualTo(0xFF4CAF50.toInt())
        assertThat(BoostWidgetFormat.zoneColor(70.0)).isEqualTo(0xFF4CAF50.toInt())
        assertThat(BoostWidgetFormat.zoneColor(69.9)).isEqualTo(0xFFFF5722.toInt())
        assertThat(BoostWidgetFormat.zoneColor(54.0)).isEqualTo(0xFFFF5722.toInt())
        assertThat(BoostWidgetFormat.zoneColor(53.9)).isEqualTo(0xFFD50000.toInt())
        // No reading draws the in-range green rather than a warning colour.
        assertThat(BoostWidgetFormat.zoneColor(0.0)).isEqualTo(0xFF4CAF50.toInt())
    }

    @Test
    fun `text sizes are the base sizes at 250 by 110 dp`() {
        val s = BoostWidgetFormat.computeTextSizes(250, 110)
        assertThat(s.label).isWithin(1e-4f).of(11f)
        assertThat(s.value).isWithin(1e-4f).of(16f)
        assertThat(s.tier).isWithin(1e-4f).of(14f)
        assertThat(s.timeAgo).isWithin(1e-4f).of(11f)
    }

    @Test
    fun `text sizes scale with the smaller axis and stay within their limits`() {
        // Height limits the scale to 2.0 although the width would allow 4.0.
        val tall = BoostWidgetFormat.computeTextSizes(1000, 220)
        assertThat(tall.value).isWithin(1e-4f).of(30f) // 32 capped at 30
        assertThat(tall.tier).isWithin(1e-4f).of(24f) // 28 capped at 24
        assertThat(tall.label).isWithin(1e-4f).of(16f) // 22 capped at 16

        // Scale floors at 0.8, then the per-field minimums apply.
        val tiny = BoostWidgetFormat.computeTextSizes(50, 20)
        assertThat(tiny.label).isWithin(1e-4f).of(9f)
        assertThat(tiny.value).isWithin(1e-4f).of(12f) // column cap, held at the 12 sp floor
        assertThat(tiny.tier).isWithin(1e-4f).of(12f)
    }

    @Test
    fun `bobble is 80 percent of the height between 100 and 240 dp`() {
        assertThat(BoostWidgetFormat.bobbleSizeDp(110)).isWithin(1e-4f).of(100f)
        assertThat(BoostWidgetFormat.bobbleSizeDp(200)).isWithin(1e-4f).of(160f)
        assertThat(BoostWidgetFormat.bobbleSizeDp(400)).isWithin(1e-4f).of(240f)
    }

    @Test
    fun `tdd prefers the weighted value, then the logged one, then the 7 day mean`() {
        assertThat(BoostWidgetFormat.tddValue(BoostStatus(tddWeighted = 40.0, tddFromDebug = 35.0, tdd7d = 30.0))).isEqualTo(40.0)
        assertThat(BoostWidgetFormat.tddValue(BoostStatus(tddFromDebug = 35.0, tdd7d = 30.0))).isEqualTo(35.0)
        assertThat(BoostWidgetFormat.tddValue(BoostStatus(tdd7d = 30.0))).isEqualTo(30.0)
        assertThat(BoostWidgetFormat.tddText(BoostStatus(tdd7d = 31.46), locale)).isEqualTo("31.5 U")
        assertThat(BoostWidgetFormat.tddText(BoostStatus(), locale)).isEqualTo("--")
    }

    @Test
    fun `dynisf shows one decimal or a placeholder`() {
        assertThat(BoostWidgetFormat.dynIsfText(2.345, locale)).isEqualTo("2.3")
        assertThat(BoostWidgetFormat.dynIsfText(null, locale)).isEqualTo("--")
    }

    @Test
    fun `headline is the tier unless V5 is the active APS`() {
        val tier = BoostStatus(tier = BoostTier.UAM_BOOST, tierLabel = "Tier 4 - UAM Boost")
        assertThat(BoostWidgetFormat.headline(tier, locale)).isEqualTo("Tier 4 - UAM Boost" to 0xFFFFC107.toInt())

        val v5 = tier.copy(isV5Active = true, v5State = BoostV5State.CONFIRMED, v5ActionMult = 1.25)
        assertThat(BoostWidgetFormat.headline(v5, locale)).isEqualTo("CONFIRM ×1.3" to 0xFFFF6E40.toInt())
    }

    @Test
    fun `activity cell becomes the meal score under V5`() {
        val legacy = BoostStatus(activityMode = ActivityMode.SLEEP_IN, activityDetail = "Sleep-in")
        assertThat(BoostWidgetFormat.activityCell(legacy, locale))
            .isEqualTo(BoostWidgetFormat.Cell("Activity", "Sleep-in", 0xFFAB47BC.toInt()))

        val v5 = legacy.copy(isV5Active = true, v5State = BoostV5State.OBSERVING, v5Score = 0.456)
        assertThat(BoostWidgetFormat.activityCell(v5, locale))
            .isEqualTo(BoostWidgetFormat.Cell("Score", "0.46", 0xFFFFC107.toInt()))
    }

    @Test
    fun `activity colours match the 6_1_2 widget`() {
        assertThat(BoostWidgetFormat.activityColor(ActivityMode.ACTIVE)).isEqualTo(0xFF42A5F5.toInt())
        assertThat(BoostWidgetFormat.activityColor(ActivityMode.INACTIVE)).isEqualTo(0xFFFF9800.toInt())
        assertThat(BoostWidgetFormat.activityColor(ActivityMode.BOOST_OFF)).isEqualTo(0xFF78909C.toInt())
        assertThat(BoostWidgetFormat.activityColor(ActivityMode.NORMAL)).isEqualTo(BoostWidgetFormat.WHITE)
    }

    @Test
    fun `profile percentage is highlighted only away from 100`() {
        assertThat(BoostWidgetFormat.profilePercentText(140)).isEqualTo("140%")
        assertThat(BoostWidgetFormat.profilePercentColor(140)).isEqualTo(BoostWidgetFormat.RIBBON_WARNING)
        assertThat(BoostWidgetFormat.profilePercentColor(100)).isEqualTo(BoostWidgetFormat.WHITE)
    }

    @Test
    fun `target source order is temp target, adjusted, profile`() {
        assertThat(BoostWidgetFormat.targetSource(true, 100.0, 120.0)).isEqualTo(TargetSource.TEMP_TARGET)
        assertThat(BoostWidgetFormat.targetSource(true, null, 0.0)).isEqualTo(TargetSource.TEMP_TARGET)
        assertThat(BoostWidgetFormat.targetSource(false, null, 120.0)).isEqualTo(TargetSource.NONE)
        assertThat(BoostWidgetFormat.targetSource(false, 100.0, 120.0)).isEqualTo(TargetSource.ALGORITHM_ADJUSTED)
        // Within 0.01 mg/dl counts as the profile target.
        assertThat(BoostWidgetFormat.targetSource(false, 100.0, 100.005)).isEqualTo(TargetSource.PROFILE)
        // A zero target means the algorithm has not reported one.
        assertThat(BoostWidgetFormat.targetSource(false, 100.0, 0.0)).isEqualTo(TargetSource.PROFILE)
    }

    @Test
    fun `target colour warns for temp and adjusted targets`() {
        assertThat(BoostWidgetFormat.targetColor(TargetSource.TEMP_TARGET)).isEqualTo(0xFFF4D700.toInt())
        assertThat(BoostWidgetFormat.targetColor(TargetSource.ALGORITHM_ADJUSTED)).isEqualTo(0xFFF4D700.toInt())
        assertThat(BoostWidgetFormat.targetColor(TargetSource.PROFILE)).isEqualTo(0xFFFFFFFF.toInt())
    }

    @Test
    fun `a wide short widget keeps each value inside its column`() {
        // 267 x 180 dp, the size seen on an emulator where "0.00 U" and "Normal" ran together.
        // The bobble takes 144 dp of the width, leaving two columns of 57.5 dp; the width scale
        // alone would have allowed 17.1 sp.
        val s = BoostWidgetFormat.computeTextSizes(267, 180)
        val column = (267f - BoostWidgetFormat.bobbleSizeDp(180) - 8f) / 2f
        assertThat(column).isWithin(1e-3f).of(57.5f)
        assertThat(s.value).isLessThan(16f * 267f / 250f)
        // Seven characters at 0.6 em fit the column.
        assertThat(s.value * 0.6f * 7).isAtMost(column + 1e-3f)
    }
}
