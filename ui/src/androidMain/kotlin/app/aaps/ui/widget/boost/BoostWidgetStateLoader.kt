package app.aaps.ui.widget.boost

import android.graphics.Color
import androidx.annotation.ColorInt
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.overview.LastBgData
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.keys.BooleanComposedKey
import app.aaps.core.keys.IntComposedKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.round
import app.aaps.ui.compose.overview.boost.BoostOverviewDataProvider
import app.aaps.ui.widget.boost.BoostWidgetFormat.TargetSource
import dev.zacsweers.metro.Inject
import java.util.Locale

/** Everything one Boost widget instance shows, resolved to text and colours. */
data class BoostWidgetState(
    /** Null leaves the layout's own black background, as 6.1.2 did for a follower without "use black". */
    @ColorInt val backgroundColor: Int?,
    val bobble: BoostBobbleInput,
    val timeAgoText: String,
    val headlineText: String,
    @ColorInt val headlineColor: Int,
    val dynIsfText: String,
    val tddText: String,
    val iobText: String,
    val activityLabel: String,
    val activityText: String,
    @ColorInt val activityColor: Int,
    val profilePctText: String,
    @ColorInt val profilePctColor: Int,
    val targetText: String,
    @ColorInt val targetColor: Int
)

/**
 * Gathers the Boost widget's values. The Boost fields (tier, DynISF, TDD, activity mode, profile
 * percentage, V5/V6 state) come from [BoostOverviewDataProvider], the same source as the Boost V2
 * overview, which already falls back to the Nightscout device status on AAPSClient.
 */
@Inject
class BoostWidgetStateLoader(
    private val boostOverviewDataProvider: BoostOverviewDataProvider,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val lastBgData: LastBgData,
    private val trendCalculator: TrendCalculator,
    private val iobCobCalculator: IobCobCalculator,
    private val loop: Loop,
    private val persistenceLayer: PersistenceLayer,
    private val rh: TextResolver,
    private val dateUtil: DateUtil,
    private val config: Config,
    private val preferences: Preferences
) {

    suspend fun loadState(appWidgetId: Int): BoostWidgetState {
        val locale = Locale.getDefault()
        val alpha = preferences.get(IntComposedKey.WidgetOpacity, appWidgetId)
        val useBlack = preferences.get(BooleanComposedKey.WidgetUseBlack, appWidgetId)
        val backgroundColor = if (config.APS || useBlack) Color.argb(alpha, 0, 0, 0) else null

        val units = profileFunction.getUnits()
        val lastBg = lastBgData.lastBg()
        val bobble = BoostBobbleInput(
            bgMgdl = lastBg?.recalculated ?: 0.0,
            bgText = lastBg?.let { profileUtil.fromMgdlToStringInUnits(it.recalculated) } ?: "---",
            unitsLabel = if (units == GlucoseUnit.MGDL) "mg/dL" else "mmol/L",
            isActual = lastBgData.isActualBg(),
            trend = trendCalculator.getTrendArrow(iobCobCalculator.ads)
        )

        val status = boostOverviewDataProvider.getBoostStatus()
        val (headlineText, headlineColor) = BoostWidgetFormat.headline(status, locale)
        val activity = BoostWidgetFormat.activityCell(status, locale)
        val dynIsf = if (status.variableSens > 0) profileUtil.fromMgdlToUnits(status.variableSens, units) else null

        val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
        val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
        val totalIob = bolusIob.iob + basalIob.basaliob

        val tempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())
        val profile = profileFunction.getProfile()
        val targetUsed = loop.lastRun?.constraintsProcessed?.targetBG ?: status.targetBgMgdl
        val targetSource = BoostWidgetFormat.targetSource(tempTarget != null, profile?.getTargetMgdl(), targetUsed)
        val targetText = when (targetSource) {
            TargetSource.TEMP_TARGET        -> tempTarget?.let {
                profileUtil.toTargetRangeString(it.lowTarget, it.highTarget, GlucoseUnit.MGDL, units) + " " + dateUtil.untilString(it.end, rh)
            } ?: "--"

            TargetSource.ALGORITHM_ADJUSTED -> profileUtil.toTargetRangeString(targetUsed, targetUsed, GlucoseUnit.MGDL, units)
            TargetSource.PROFILE            -> profile?.let {
                profileUtil.toTargetRangeString(it.getTargetLowMgdl(), it.getTargetHighMgdl(), GlucoseUnit.MGDL, units)
            } ?: "--"

            TargetSource.NONE               -> "--"
        }

        return BoostWidgetState(
            backgroundColor = backgroundColor,
            bobble = bobble,
            timeAgoText = dateUtil.minOrSecAgo(rh, lastBg?.timestamp),
            headlineText = headlineText,
            headlineColor = headlineColor,
            dynIsfText = BoostWidgetFormat.dynIsfText(dynIsf, locale),
            tddText = BoostWidgetFormat.tddText(status, locale),
            iobText = rh.gs(InterfacesStrings.format_insulin_units, totalIob),
            activityLabel = activity.label,
            activityText = activity.text,
            activityColor = activity.color,
            profilePctText = BoostWidgetFormat.profilePercentText(status.profilePercentage),
            profilePctColor = BoostWidgetFormat.profilePercentColor(status.profilePercentage),
            targetText = targetText,
            targetColor = BoostWidgetFormat.targetColor(targetSource)
        )
    }
}
