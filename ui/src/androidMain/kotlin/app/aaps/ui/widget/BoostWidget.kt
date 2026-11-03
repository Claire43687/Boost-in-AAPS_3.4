package app.aaps.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.widget.RemoteViews
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.di.injectMetroMembers
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.ui.R
import app.aaps.ui.widget.boost.BoostBobbleBitmapRenderer
import app.aaps.ui.widget.boost.BoostWidgetFormat
import app.aaps.ui.widget.boost.BoostWidgetState
import app.aaps.ui.widget.boost.BoostWidgetStateLoader
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Boost home-screen widget, ported from Boost 6.1.2.
 *
 * Layout: glucose bobble with time-ago and the Boost tier (or the V5/V6 state) on the left, and a
 * 2 x 3 table of DynISF, TDD, IOB, activity mode (the V5/V6 meal score while V5/V6 is the active
 * APS), profile percentage and target on the right. Tapping it opens the Boost V2 overview.
 *
 * It is a classic [AppWidgetProvider] with RemoteViews, like [SmallWidget], because the bobble is a
 * bitmap and the text sizes are set from the size the launcher reports. App-initiated refreshes go
 * through `WidgetUpdaterImpl`, which sends this receiver an explicit broadcast so the other widgets
 * do not receive it.
 */
class BoostWidget : AppWidgetProvider() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var boostWidgetStateLoader: BoostWidgetStateLoader
    @Inject lateinit var config: Config

    override fun onReceive(context: Context, intent: Intent) {
        context.injectMetroMembers(this)
        aapsLogger.debug(LTag.WIDGET, "BoostWidget onReceive ${intent.action}")
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Only this provider's ids: an update broadcast that reached it without an explicit
        // component could carry another widget's ids, which caused cross-talk in 3.4.
        val myIds = appWidgetManager.getAppWidgetIds(ComponentName(context, BoostWidget::class.java)).toSet()
        updateWidgets(context, appWidgetManager, appWidgetIds.filter { it in myIds })
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
        updateWidgets(context, appWidgetManager, listOf(appWidgetId))
    }

    private fun updateWidgets(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: List<Int>) {
        if (appWidgetIds.isEmpty()) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                for (appWidgetId in appWidgetIds) {
                    val views = RemoteViews(context.packageName, R.layout.boost_widget_layout)
                    views.setOnClickPendingIntent(R.id.boost_widget_layout, openBoostOverviewIntent(context))

                    val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
                    val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
                    val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110)
                    applyTextSizes(views, BoostWidgetFormat.computeTextSizes(widthDp, heightDp))

                    // Before initialisation the layout keeps its placeholders, as in 6.1.2.
                    if (config.appInitialized) {
                        val state = boostWidgetStateLoader.loadState(appWidgetId)
                        applyState(context, views, state, heightDp)
                    }
                    appWidgetManager.updateAppWidget(appWidgetId, views)
                }
            } catch (t: Throwable) {
                aapsLogger.error(LTag.WIDGET, "BoostWidget update failed: ${t.message}", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun openBoostOverviewIntent(context: Context): PendingIntent? {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launchIntent.action = ACTION_OPEN_BOOST_OVERVIEW
        return PendingIntent.getActivity(context, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun applyTextSizes(views: RemoteViews, s: BoostWidgetFormat.TextSizes) {
        views.setTextViewTextSize(R.id.tier_label, TypedValue.COMPLEX_UNIT_SP, s.tier)
        views.setTextViewTextSize(R.id.time_ago, TypedValue.COMPLEX_UNIT_SP, s.timeAgo)
        for (id in VALUE_IDS) views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, s.value)
        for (id in LABEL_IDS) views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, s.label)
    }

    private fun applyState(context: Context, views: RemoteViews, state: BoostWidgetState, heightDp: Int) {
        state.backgroundColor?.let { views.setInt(R.id.boost_widget_layout, "setBackgroundColor", it) }

        val density = context.resources.displayMetrics.density
        val sizePx = (BoostWidgetFormat.bobbleSizeDp(heightDp) * density).toInt()
        views.setImageViewBitmap(R.id.bg_bobble, BoostBobbleBitmapRenderer.render(sizePx, density, state.bobble))
        views.setTextViewText(R.id.time_ago, state.timeAgoText)

        views.setTextViewText(R.id.tier_label, state.headlineText)
        views.setTextColor(R.id.tier_label, state.headlineColor)

        views.setTextViewText(R.id.dynisf, state.dynIsfText)
        views.setTextViewText(R.id.tdd, state.tddText)
        views.setTextViewText(R.id.iob, state.iobText)

        views.setTextViewText(R.id.label_activity, state.activityLabel)
        views.setTextViewText(R.id.activity_mode, state.activityText)
        views.setTextColor(R.id.activity_mode, state.activityColor)

        views.setTextViewText(R.id.profile_pct, state.profilePctText)
        views.setTextColor(R.id.profile_pct, state.profilePctColor)

        views.setTextViewText(R.id.temp_target, state.targetText)
        views.setTextColor(R.id.temp_target, state.targetColor)
    }

    companion object {

        /**
         * Action of the widget's tap intent. The main activity opens the Boost V2 overview when it
         * receives it; any other launch opens the main screen.
         */
        const val ACTION_OPEN_BOOST_OVERVIEW = "app.aaps.ui.widget.OPEN_BOOST_OVERVIEW"

        private val VALUE_IDS = intArrayOf(R.id.dynisf, R.id.tdd, R.id.iob, R.id.activity_mode, R.id.profile_pct, R.id.temp_target)
        private val LABEL_IDS = intArrayOf(R.id.label_dynisf, R.id.label_tdd, R.id.label_iob, R.id.label_activity, R.id.label_profile, R.id.label_target)
    }
}
