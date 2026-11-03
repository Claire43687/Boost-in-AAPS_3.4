package app.aaps.ui.compose.overview.boost

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import app.aaps.core.ui.compose.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.configuration.Constants
import app.aaps.core.graph.vico.Square
import app.aaps.core.interfaces.overview.graph.BgType
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.ui.UiStrings
import app.aaps.core.ui.compose.LocalProfileUtil
import app.aaps.ui.compose.overview.graphs.BucketedPointProvider
import app.aaps.ui.compose.overview.graphs.DEFAULT_GRAPH_ZOOM_MINUTES
import app.aaps.ui.compose.overview.graphs.GraphViewModel
import app.aaps.core.data.ui.MarkerTier
import app.aaps.core.ui.compose.LocalDecimalFormatter
import app.aaps.ui.compose.overview.graphs.formatBolusLabel
import app.aaps.ui.compose.overview.graphs.markerSize
import app.aaps.ui.compose.overview.graphs.tierOf
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.compose.common.Position
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import app.aaps.ui.compose.overview.graphs.GraphInspectOverlay
import app.aaps.ui.compose.overview.graphs.GraphInspectState
import app.aaps.ui.compose.overview.graphs.GraphsSection
import app.aaps.ui.compose.overview.graphs.SilentInspectMarker
import app.aaps.ui.compose.overview.graphs.closesInspectPillOnTouch
import app.aaps.ui.compose.overview.graphs.graphInspectTouch
import app.aaps.ui.compose.overview.graphs.rememberInspectLine
import app.aaps.ui.compose.overview.graphs.InvertedTriangleShape
import app.aaps.ui.compose.overview.graphs.MIN_GRAPH_ZOOM_MINUTES
import app.aaps.ui.compose.overview.graphs.MutableYRangeProvider
import app.aaps.ui.compose.overview.graphs.NoLabelsAxisItemPlacer
import app.aaps.ui.compose.overview.graphs.PREDICTION_AXIS_HEADROOM_MGDL
import app.aaps.ui.compose.overview.graphs.VisibleRangeHolder
import app.aaps.ui.compose.overview.graphs.bgAxisTop
import app.aaps.ui.compose.overview.graphs.niceScale
import app.aaps.ui.compose.overview.graphs.rememberVisibleRangeReporter
import app.aaps.ui.compose.overview.graphs.zeroFloorNiceRange
import app.aaps.ui.compose.overview.graphs.createNormalizerLine
import app.aaps.ui.compose.overview.graphs.createPredictionLine
import app.aaps.ui.compose.overview.graphs.normalizerX
import app.aaps.ui.compose.overview.graphs.NORMALIZER_Y
import app.aaps.ui.compose.overview.graphs.TriangleShape
import app.aaps.ui.compose.overview.graphs.processIobOverlays
import app.aaps.ui.compose.overview.graphs.processStepPoints
import app.aaps.ui.compose.overview.graphs.rememberBottomAxisItemPlacer
import app.aaps.ui.compose.overview.graphs.rememberNowLine
import app.aaps.ui.compose.overview.graphs.rememberTimeFormatter
import app.aaps.ui.compose.overview.graphs.timestampToX
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Scroll
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.decoration.HorizontalBox
import com.patrykandpatrick.vico.compose.cartesian.decoration.HorizontalLine
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.DashedShape
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.LineComponent
import com.patrykandpatrick.vico.compose.common.component.ShapeComponent
import com.patrykandpatrick.vico.compose.common.component.TextComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Two swappable chart implementations for the Boost V2 overview chart slot.
 *
 * - [BoostChartStyle.STANDARD]: reuses the 4.0 Vico graphs ([GraphsSection]) verbatim — BG +
 *   IOB/basal + secondary graphs with full Vico zoom/scroll/tap and the graph-config editor.
 * - [BoostChartStyle.V2]: a faithful rebuild of the original Boost V2 dark chart in Vico, using the
 *   [BoostV2Colors] palette (dark canvas, cyan in-range band, green target line, blue IOB, lime
 *   activity). Fed from the SAME [GraphViewModel] flows as the Standard graphs.
 *
 * The user flips between them with the segmented toggle in the card header; the choice is persisted
 * in [app.aaps.core.keys.BooleanKey.ApsBoostV2ChartFaithful] via [BoostV2ViewModel].
 */
enum class BoostChartStyle { STANDARD, V2 }

@Composable
fun BoostChart(
    style: BoostChartStyle,
    graphViewModel: GraphViewModel,
    rangeHours: Int,
    onSetRange: (Int) -> Unit,
    onTreatments: () -> Unit,
    onSelectStyle: (BoostChartStyle) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BoostV2Colors.cardBackground)
            .border(1.dp, BoostV2Colors.stroke, RoundedCornerShape(12.dp))
            .padding(6.dp)
    ) {
        // Card header — range buttons, then the segmented Standard | V2 toggle (right-aligned).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            RangeSelector(rangeHours = rangeHours, onSetRange = onSetRange)
            Spacer(Modifier.weight(1f))
            ChartStyleToggle(selected = style, onSelect = onSelectStyle)
        }

        when (style) {
            BoostChartStyle.STANDARD ->
                // Reuse the 4.0 graphs wholesale. GraphsSection owns its own scroll/zoom/tap and the
                // range selector does not drive it (Vico-zoom driven) — see report.
                GraphsSection(
                    graphViewModel = graphViewModel,
                    isSimpleMode = false,
                    modifier = Modifier.fillMaxWidth()
                )

            BoostChartStyle.V2       ->
                BoostV2Chart(
                    graphViewModel = graphViewModel,
                    rangeHours = rangeHours,
                    onTreatments = onTreatments,
                    modifier = Modifier.fillMaxWidth()
                )
        }
    }
}

// ── Segmented style toggle ──────────────────────────────────────────────────────

@Composable
private fun ChartStyleToggle(selected: BoostChartStyle, onSelect: (BoostChartStyle) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(BoostV2Colors.cardBackground)
            .padding(2.dp)
    ) {
        ChartStyleSegment(
            label = stringResource(UiStrings.boost_v2_chart_standard),
            active = selected == BoostChartStyle.STANDARD,
            onClick = { onSelect(BoostChartStyle.STANDARD) }
        )
        ChartStyleSegment(
            label = stringResource(UiStrings.boost_v2_chart_v2),
            active = selected == BoostChartStyle.V2,
            onClick = { onSelect(BoostChartStyle.V2) }
        )
    }
}

@Composable
private fun ChartStyleSegment(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (active) BoostV2Colors.accent else BoostV2Colors.textSecondary,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) BoostV2Colors.rangeActiveFill else BoostV2Colors.rangeInactiveFill)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 4.dp)
    )
}

// =================================================================================
// Style 2 — faithful V2 dark chart, rebuilt in Vico
// =================================================================================

private const val V2_BG_HEIGHT_DP = 133
private const val V2_IOB_HEIGHT_DP = 70
private const val V2_ACTIVITY_HEIGHT_DP = 70
private val V2_PREDICTION_COLORS = listOf(
    BgType.IOB_PREDICTION to BoostV2Colors.chartPredIob,
    BgType.COB_PREDICTION to BoostV2Colors.chartPredCob,
    BgType.A_COB_PREDICTION to BoostV2Colors.chartPredACob,
    BgType.UAM_PREDICTION to BoostV2Colors.chartPredUam,
    BgType.ZT_PREDICTION to BoostV2Colors.chartPredZt
)

@OptIn(FlowPreview::class)
@Composable
private fun BoostV2Chart(
    graphViewModel: GraphViewModel,
    rangeHours: Int,
    onTreatments: () -> Unit,
    modifier: Modifier = Modifier
) {
    val derivedTimeRange by graphViewModel.derivedTimeRange.collectAsStateWithLifecycle()
    val nowTimestamp by graphViewModel.nowTimestamp.collectAsStateWithLifecycle()
    // The V2 "activity" sub-graph plots heart rate + steps (as in the original Boost V2), not the
    // insulin-activity series — 4.0 exposes them as separate cache flows.
    val heartRateData by graphViewModel.heartRateGraphFlow.collectAsStateWithLifecycle()
    val stepsData by graphViewModel.stepsGraphFlow.collectAsStateWithLifecycle()

    // BG graph — primary interactive. rangeHours seeds the initial zoom (see report for how the
    // range selector interacts) and drives it live via the LaunchedEffect below.
    val bgScrollState = rememberVicoScrollState(scrollEnabled = true, initialScroll = Scroll.Absolute.End)
    val bgZoomState = rememberVicoZoomState(
        zoomEnabled = true,
        initialZoom = Zoom.x((rangeHours * 60).toDouble()),
        minZoom = Zoom.x(Constants.GRAPH_TIME_RANGE_HOURS * 60.0),
        maxZoom = Zoom.x(MIN_GRAPH_ZOOM_MINUTES)
    )

    // Sub-graphs — non-interactive, synced from BG.
    val iobScrollState = rememberVicoScrollState(scrollEnabled = false, initialScroll = Scroll.Absolute.End)
    val iobZoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.x(DEFAULT_GRAPH_ZOOM_MINUTES))
    val actScrollState = rememberVicoScrollState(scrollEnabled = false, initialScroll = Scroll.Absolute.End)
    val actZoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.x(DEFAULT_GRAPH_ZOOM_MINUTES))

    // Apply the range-selector choice to the BG zoom (skips the initial value; the sub-graphs follow
    // via the sync effect below).
    LaunchedEffect(rangeHours) {
        bgZoomState.zoom(Zoom.x((rangeHours * 60).toDouble()))
    }

    // Mirror BG scroll/zoom onto the sub-graphs (same pattern as GraphsSection).
    LaunchedEffect(bgScrollState, bgZoomState, iobScrollState, iobZoomState, actScrollState, actZoomState) {
        snapshotFlow { bgScrollState.value to bgZoomState.value }
            .debounce(30)
            .collect { (scroll, zoom) ->
                iobZoomState.zoom(Zoom.fixed(zoom))
                actZoomState.zoom(Zoom.fixed(zoom))
                delay(10)
                iobScrollState.scroll(Scroll.Absolute.pixels(scroll))
                actScrollState.scroll(Scroll.Absolute.pixels(scroll))
            }
    }

    // The window on screen, reported by the IOB graph, which never takes a gesture. As on the standard graphs the
    // axes wait for it to settle: changing them during a pinch breaks the gesture.
    val visibleRangeHolder = remember { VisibleRangeHolder() }
    var visibleRange by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    LaunchedEffect(visibleRangeHolder) {
        visibleRangeHolder.value.debounce(400).collect { visibleRange = it }
    }

    // shortcut: 4.0.0 splits heart rate at gaps and this joins them across a gap, draw per segment if gaps mislead.
    val heartRates = heartRateData.segments.flatten()
    val steps = stepsData.steps
    val hasHeartRate = heartRates.isNotEmpty()
    val hasSteps = steps.isNotEmpty()
    val hasActivity = hasHeartRate || hasSteps
    val bgReadings by graphViewModel.bgReadingsFlow.collectAsStateWithLifecycle()
    // Press and hold, as on the standard graphs: shared so the line runs through all three.
    val inspect = remember { GraphInspectState() }
    val activityLabel = when {
        hasHeartRate && hasSteps -> stringResource(UiStrings.boost_v2_hr_steps)
        hasHeartRate             -> stringResource(UiStrings.boost_v2_heart_rate)
        else                     -> stringResource(UiStrings.boost_v2_steps)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BoostV2Colors.chartBackground)
            .padding(vertical = 6.dp)
            .closesInspectPillOnTouch(inspect)
    ) {
        Box(modifier = Modifier.graphInspectTouch(inspect)) {
            // BG graph — tap anywhere opens Treatments. onLongPress is what stops the lift after a hold counting as a tap.
            V2BgGraph(
                viewModel = graphViewModel,
                scrollState = bgScrollState,
                zoomState = bgZoomState,
                derivedTimeRange = derivedTimeRange,
                nowTimestamp = nowTimestamp,
                visibleRange = visibleRange,
                inspect = inspect,
                modifier = Modifier
                    // Room for the pill, which would otherwise cover the dot at a high reading.
                    .padding(top = 30.dp)
                    .fillMaxWidth()
                    .height(V2_BG_HEIGHT_DP.dp)
                    .pointerInput(Unit) { detectTapGestures(onLongPress = {}, onTap = { onTreatments() }) }
            )
            GraphInspectOverlay(
                inspect = inspect,
                graphViewModel = graphViewModel,
                bgReadings = bgReadings,
                minTimestamp = derivedTimeRange?.first,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 2.dp, start = 36.dp, end = 36.dp)
            )
        }
        V2SubGraphLabel(stringResource(UiStrings.boost_v2_iob))
        V2LineGraph(
            data = graphViewModel.iobGraphFlow.collectAsStateWithLifecycle().value.iob,
            lineColor = BoostV2Colors.chartIobLine,
            fillColor = BoostV2Colors.chartIobFill,
            scrollState = iobScrollState,
            zoomState = iobZoomState,
            derivedTimeRange = derivedTimeRange,
            nowTimestamp = nowTimestamp,
            visibleRange = visibleRange,
            visibleRangeHolder = visibleRangeHolder,
            inspect = inspect,
            modifier = Modifier
                .fillMaxWidth()
                .height(V2_IOB_HEIGHT_DP.dp)
                .graphInspectTouch(inspect)
        )
        if (hasActivity) {
            V2SubGraphLabel(activityLabel)
            V2ActivityGraph(
                heartRates = heartRates,
                steps = steps,
                scrollState = actScrollState,
                zoomState = actZoomState,
                derivedTimeRange = derivedTimeRange,
                nowTimestamp = nowTimestamp,
                visibleRange = visibleRange,
                inspect = inspect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(V2_ACTIVITY_HEIGHT_DP.dp)
                    .graphInspectTouch(inspect)
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun V2SubGraphLabel(text: String) {
    Text(
        text = text,
        color = BoostV2Colors.chartAxisLabel,
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 36.dp, top = 2.dp)
    )
}

// ── V2 BG graph ─────────────────────────────────────────────────────────────────

/** Slot kinds for the V2 BG layer, in emit order. */
private sealed interface V2BgSlot {
    data object Regular : V2BgSlot
    data object Bucketed : V2BgSlot
    data class Prediction(val color: Color) : V2BgSlot
    data object InsulinActivity : V2BgSlot
    data object InsulinActivityPrediction : V2BgSlot
    data object Carbs : V2BgSlot
    data object SmbSmall : V2BgSlot
    data object SmbMedium : V2BgSlot
    data object SmbLarge : V2BgSlot
    data class ManualBolus(val maxAmount: Double?, val baseY: Double) : V2BgSlot
}

private class V2BgSpec(val x: List<Double>, val y: List<Double>, val slot: V2BgSlot)

@Composable
private fun V2BgGraph(
    viewModel: GraphViewModel,
    scrollState: VicoScrollState,
    zoomState: VicoZoomState,
    derivedTimeRange: Pair<Long, Long>?,
    nowTimestamp: Long,
    visibleRange: Pair<Double, Double>?,
    inspect: GraphInspectState,
    modifier: Modifier = Modifier
) {
    val bgReadings by viewModel.bgReadingsFlow.collectAsStateWithLifecycle()
    val bucketedData by viewModel.bucketedDataFlow.collectAsStateWithLifecycle()
    val predictions by viewModel.predictionsFlow.collectAsStateWithLifecycle()
    val targetData by viewModel.targetLineFlow.collectAsStateWithLifecycle()
    val chartConfig by viewModel.chartConfigFlow.collectAsStateWithLifecycle()
    val treatmentData by viewModel.treatmentGraphFlow.collectAsStateWithLifecycle()
    val basalData by viewModel.basalGraphFlow.collectAsStateWithLifecycle()
    val activityData by viewModel.activityGraphFlow.collectAsStateWithLifecycle()

    // Keep the last non-empty treatments through reset() cycles, as the IOB graph does.
    val lastTreatmentData = remember { mutableStateOf(treatmentData) }
    if (treatmentData.boluses.isNotEmpty() || treatmentData.carbs.isNotEmpty()) lastTreatmentData.value = treatmentData

    val (minTimestamp, maxTimestamp) = derivedTimeRange ?: run {
        val now = System.currentTimeMillis()
        (now - Constants.GRAPH_TIME_RANGE_HOURS * 60 * 60 * 1000L) to now
    }
    val maxX = remember(minTimestamp, maxTimestamp) { timestampToX(maxTimestamp, minTimestamp) }
    val stableTimeRange = remember(minTimestamp / 60000, maxTimestamp / 60000) { minTimestamp to maxTimestamp }

    val lowMark = chartConfig.lowMark
    val highMark = chartConfig.highMark

    // Y range: 0 up to the highest value in the window on screen, with the high mark always in view. The floor stays
    // at 0, unlike the standard graph, because basal and the bolus markers are placed from the bottom of this graph.
    val profileUtil = LocalProfileUtil.current
    val predictionHeadroom = remember(profileUtil.units) { profileUtil.fromMgdlToUnits(PREDICTION_AXIS_HEADROOM_MGDL) }
    val maxBgY = remember(bgReadings, bucketedData, predictions, highMark, visibleRange, predictionHeadroom, minTimestamp) {
        val real = (bgReadings + bucketedData).map { timestampToX(it.timestamp, minTimestamp) to it.value }
        val predicted = predictions.map { timestampToX(it.timestamp, minTimestamp) to it.value }
        val windowHasData = (real + predicted).inWindow(visibleRange).isNotEmpty()
        fun List<Pair<Double, Double>>.axisValues() = if (windowHasData) inWindow(visibleRange) else this
        niceScale(0.0, bgAxisTop(real.axisValues().maxOfOrNull { it.second }, predicted.axisValues().maxOfOrNull { it.second }, highMark, predictionHeadroom)).max
    }

    // BG series (regular, bucketed, predictions) built synchronously in emit order.
    val bgSpecs = remember(bgReadings, bucketedData, predictions, activityData, maxBgY, lastTreatmentData.value, stableTimeRange, lowMark) {
        buildList {
            if (bgReadings.isNotEmpty()) {
                val pts = bgReadings.map { timestampToX(it.timestamp, minTimestamp) to it.value }.sortedBy { it.first }
                add(V2BgSpec(pts.map { it.first }, pts.map { it.second }, V2BgSlot.Regular))
            }
            if (bucketedData.isNotEmpty()) {
                val pts = bucketedData.map { timestampToX(it.timestamp, minTimestamp) to it.value }.sortedBy { it.first }
                add(V2BgSpec(pts.map { it.first }, pts.map { it.second }, V2BgSlot.Bucketed))
            }
            V2_PREDICTION_COLORS.forEach { (type, color) ->
                val pts = predictions.filter { it.type == type }.map { timestampToX(it.timestamp, minTimestamp) to it.value }.sortedBy { it.first }
                if (pts.isNotEmpty()) add(V2BgSpec(pts.map { it.first }, pts.map { it.second }, V2BgSlot.Prediction(color)))
            }
            // Insulin activity peaks at 80 % of the glucose scale, as on the 6.x graph.
            val allActivity = (activityData.activity + activityData.activityPrediction).map { timestampToX(it.timestamp, minTimestamp) to it.value }
            val maxActivity = allActivity.inWindow(visibleRange).ifEmpty { allActivity }.maxOfOrNull { it.second } ?: 0.0
            if (maxActivity > 0.0) {
                fun addActivity(data: List<GraphDataPoint>, slot: V2BgSlot) {
                    val pts = data.map { timestampToX(it.timestamp, minTimestamp) to it.value * maxBgY * 0.8 / maxActivity }
                        .filter { it.first in 0.0..maxX }.sortedBy { it.first }
                    if (pts.size >= 2) add(V2BgSpec(pts.map { it.first }, pts.map { it.second }, slot))
                }
                addActivity(activityData.activity, V2BgSlot.InsulinActivity)
                addActivity(activityData.activityPrediction, V2BgSlot.InsulinActivityPrediction)
            }
            // Treatment markers sit on the glucose reading nearest in time, as on the 6.x graph.
            val glucose = (bucketedData.ifEmpty { bgReadings }).map { timestampToX(it.timestamp, minTimestamp) to it.value }
            fun addMarkers(xs: List<Double>, slot: V2BgSlot) {
                if (xs.isNotEmpty()) add(V2BgSpec(xs, xs.map { x -> glucose.minByOrNull { abs(it.first - x) }?.second ?: lowMark }, slot))
            }
            addMarkers(
                lastTreatmentData.value.carbs.map { timestampToX(it.timestamp, minTimestamp) }.filter { it in 0.0..maxX }.sorted(),
                V2BgSlot.Carbs
            )
            // Boluses sit on the low mark, as SMBs did on the 6.x graph: basal tops out at lowMark / 1.2, so they clear it.
            val overlays = processIobOverlays(lastTreatmentData.value, minTimestamp, 0.0, maxX)
            fun addBoluses(points: List<Pair<Double, Double>>, slot: V2BgSlot) {
                if (points.isNotEmpty()) points.sortedBy { it.first }.let { sorted ->
                    add(V2BgSpec(sorted.map { it.first }, sorted.map { lowMark + it.second }, slot))
                }
            }
            addBoluses(overlays.smallSmbs, V2BgSlot.SmbSmall)
            addBoluses(overlays.mediumSmbs, V2BgSlot.SmbMedium)
            addBoluses(overlays.largeSmbs, V2BgSlot.SmbLarge)
            addBoluses(overlays.normalBoluses.map { (x, amount) -> x to amount * MANUAL_BOLUS_Y_PER_UNIT }, V2BgSlot.ManualBolus(overlays.maxManualBolus, lowMark))
        }
    }

    val modelProducer = remember { CartesianChartModelProducer() }

    val basalActual = remember(basalData, stableTimeRange) { processStepPoints(basalData.actualBasal, minTimestamp, 0.0, maxX) }
    val basalProfile = remember(basalData, stableTimeRange) { processStepPoints(basalData.profileBasal, minTimestamp, 0.0, maxX) }

    // Mutated in place, not rebuilt, so that the chart keeps its identity during a gesture (see MutableYRangeProvider).
    val rangeProvider = remember { MutableYRangeProvider(maxX = maxX, minY = 0.0, maxY = maxBgY) }
    // The highest basal reaches lowMark / 1.2 on the glucose scale, as on the 6.x graph, so basal stays
    // below the in-range band. It has its own (hidden) axis, because layers on one axis share a Y range.
    val basalRangeProvider = remember { MutableYRangeProvider(maxX = maxX, minY = 0.0, maxY = 1.0) }

    LaunchedEffect(bgSpecs, targetData, basalActual, basalProfile, maxX, maxBgY, lowMark, basalData.maxBasal) {
        rangeProvider.maxX = maxX
        rangeProvider.maxY = maxBgY
        basalRangeProvider.maxX = maxX
        basalRangeProvider.maxY = maxOf(basalData.maxBasal, 0.1) / (lowMark / 1.2 / maxBgY).coerceIn(0.05, 1.0)
        withContext(NonCancellable) { modelProducer.runTransaction {
            // Layer 0 — basal, first so that the readings draw over it
            lineModel {
                listOf(basalActual, basalProfile).forEach { pts ->
                    if (pts.size >= 2) series(x = pts.map { it.first }, y = pts.map { it.second })
                    else series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                }
            }
            // Layer 1 — BG points and treatment markers
            lineModel {
                bgSpecs.forEach { series(x = it.x, y = it.y) }
                series(x = normalizerX(maxX), y = NORMALIZER_Y)
            }
            // Layer 2 — target line (step)
            lineModel {
                if (targetData.targets.size >= 2) {
                    val pts = targetData.targets.map { timestampToX(it.timestamp, minTimestamp) to it.value }.sortedBy { it.first }
                    series(x = pts.map { it.first }, y = pts.map { it.second })
                } else {
                    series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                }
            }
            extras { it[V2_AXIS_SCALE_KEY] = maxBgY to basalRangeProvider.maxY }
        } }
    }

    // Line styles matching bgSpecs order + normalizer.
    val bucketedLookup = remember(bucketedData, minTimestamp) {
        bucketedData.associateBy { timestampToX(it.timestamp, minTimestamp) }
    }
    val bucketedProvider = remember(bucketedLookup) {
        BucketedPointProvider(bucketedLookup, BoostV2Colors.bgLow, BoostV2Colors.bgInRange, BoostV2Colors.bgHigh)
    }
    val regularLine = remember {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
            areaFill = null,
            pointProvider = LineCartesianLayer.PointProvider.single(
                LineCartesianLayer.Point(
                    component = ShapeComponent(
                        fill = Fill(Color.Transparent),
                        shape = CircleShape,
                        strokeFill = Fill(BoostV2Colors.chartBgReading.copy(alpha = 0.5f)),
                        strokeThickness = 1.dp
                    ),
                    size = 5.dp
                )
            )
        )
    }
    val bucketedLine = remember(bucketedProvider) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
            areaFill = null,
            pointProvider = bucketedProvider
        )
    }
    val activityLine = remember {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(BoostV2Colors.chartInsulinActivity)),
            stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 1.dp),
            areaFill = null
        )
    }
    val activityPredictionLine = remember {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(BoostV2Colors.chartInsulinActivity)),
            stroke = LineCartesianLayer.LineStroke.Dashed(thickness = 1.dp, cap = StrokeCap.Round, dashLength = 2.dp, gapLength = 3.dp),
            areaFill = null
        )
    }
    val normalizerLine = remember { createNormalizerLine() }
    val carbsLine = remember { v2MarkerLine(BoostV2Colors.chartCarbs, InvertedTriangleShape, 22.dp) }
    val smbSmallLine = remember { v2MarkerLine(BoostV2Colors.chartBolus, TriangleShape, 10.dp) }
    val smbMediumLine = remember { v2MarkerLine(BoostV2Colors.chartBolus, TriangleShape, 16.dp) }
    val smbLargeLine = remember { v2MarkerLine(BoostV2Colors.chartBolus, TriangleShape, 22.dp) }
    val decimalFormatter = LocalDecimalFormatter.current
    val bolusLabel = remember { TextComponent(textStyle = TextStyle(color = BoostV2Colors.chartBolus, fontSize = 14.sp)) }
    val bolusLabelValue = remember(decimalFormatter, lowMark) {
        CartesianValueFormatter { _, y, _ -> formatBolusLabel((y - lowMark) / MANUAL_BOLUS_Y_PER_UNIT, decimalFormatter) }
    }

    val bgLines = remember(bgSpecs, regularLine, bucketedLine, normalizerLine) {
        buildList {
            bgSpecs.forEach {
                add(
                    when (it.slot) {
                        V2BgSlot.Regular    -> regularLine
                        V2BgSlot.Bucketed   -> bucketedLine
                        is V2BgSlot.Prediction -> createPredictionLine(it.slot.color)
                        V2BgSlot.InsulinActivity -> activityLine
                        V2BgSlot.InsulinActivityPrediction -> activityPredictionLine
                        V2BgSlot.Carbs      -> carbsLine
                        V2BgSlot.SmbSmall   -> smbSmallLine
                        V2BgSlot.SmbMedium  -> smbMediumLine
                        V2BgSlot.SmbLarge   -> smbLargeLine
                        is V2BgSlot.ManualBolus -> LineCartesianLayer.Line(
                            fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
                            areaFill = null,
                            pointProvider = V2ManualBolusPoints(it.slot.maxAmount, it.slot.baseY),
                            dataLabel = bolusLabel,
                            dataLabelPosition = Position.Vertical.Top,
                            dataLabelValueFormatter = bolusLabelValue
                        )
                    }
                )
            }
            add(normalizerLine)
        }
    }

    val basalLines = remember {
        listOf(
            LineCartesianLayer.Line(
                fill = LineCartesianLayer.LineFill.single(Fill(BoostV2Colors.chartBasalLine)),
                stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 1.dp),
                areaFill = LineCartesianLayer.AreaFill.single(Fill(BoostV2Colors.chartBasalFill)),
                interpolator = Square
            ),
            LineCartesianLayer.Line(
                fill = LineCartesianLayer.LineFill.single(Fill(BoostV2Colors.chartBasalLine)),
                stroke = LineCartesianLayer.LineStroke.Dashed(thickness = 1.dp, cap = StrokeCap.Round, dashLength = 1.dp, gapLength = 2.dp),
                areaFill = null,
                interpolator = Square
            )
        )
    }
    val targetLine = remember {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(BoostV2Colors.chartTargetLine)),
            stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 1.dp),
            areaFill = null,
            interpolator = Square
        )
    }

    // Decorations — in-range band + dashed-ish borders + now line.
    val inRangeBox = remember(lowMark, highMark) {
        HorizontalBox(
            y = { lowMark..highMark },
            box = ShapeComponent(fill = Fill(BoostV2Colors.chartInRangeFill)),
            verticalAxisPosition = Axis.Position.Vertical.Start
        )
    }
    // Dashed shape for the in-range band borders — matches the original V2 look (upper cyan dashed,
    // lower red dashed). HorizontalLine draws its LineComponent using the component's shape, so a
    // DashedShape renders the horizontal decoration as a dashed line.
    val dashedShape = remember { DashedShape(shape = RectangleShape, dashLength = 4.dp, gapLength = 4.dp) }
    val upperBorder = remember(highMark, dashedShape) {
        HorizontalLine(
            y = { highMark },
            line = LineComponent(fill = Fill(BoostV2Colors.chartInRangeUpperBorder), thickness = 1.dp, shape = dashedShape),
            verticalAxisPosition = Axis.Position.Vertical.Start
        )
    }
    val lowerBorder = remember(lowMark, dashedShape) {
        HorizontalLine(
            y = { lowMark },
            line = LineComponent(fill = Fill(BoostV2Colors.chartInRangeLowerBorder), thickness = 1.dp, shape = dashedShape),
            verticalAxisPosition = Axis.Position.Vertical.Start
        )
    }
    val nowLine = rememberNowLine(minTimestamp, nowTimestamp, BoostV2Colors.chartNowLine)
    val inspectLine = rememberInspectLine(inspect, dotOnReading = true)
    val decorations = remember(inRangeBox, upperBorder, lowerBorder, nowLine, inspectLine) {
        listOfNotNull(inRangeBox, upperBorder, lowerBorder, nowLine, inspectLine)
    }


    val timeFormatter = rememberTimeFormatter(minTimestamp)
    val bottomAxisItemPlacer = rememberBottomAxisItemPlacer(minTimestamp)

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(
                lineProvider = LineCartesianLayer.LineProvider.series(basalLines),
                rangeProvider = basalRangeProvider,
                verticalAxisPosition = Axis.Position.Vertical.End
            ),
            rememberLineCartesianLayer(
                lineProvider = LineCartesianLayer.LineProvider.series(bgLines),
                rangeProvider = rangeProvider,
                verticalAxisPosition = Axis.Position.Vertical.Start
            ),
            rememberLineCartesianLayer(
                lineProvider = LineCartesianLayer.LineProvider.series(listOf(targetLine)),
                rangeProvider = rangeProvider,
                verticalAxisPosition = Axis.Position.Vertical.Start
            ),
            startAxis = V2StartAxis(),
            bottomAxis = HorizontalAxis.rememberBottom(
                valueFormatter = timeFormatter,
                itemPlacer = bottomAxisItemPlacer,
                label = rememberTextComponent(style = TextStyle(color = BoostV2Colors.chartAxisLabel)),
                guideline = LineComponent(fill = Fill(BoostV2Colors.chartGrid))
            ),
            decorations = decorations,
            getXStep = { _, _, _ -> 1.0 },
            marker = SilentInspectMarker,
            markerVisibilityListener = inspect.visibilityListener,
            markerController = inspect.controller
        ),
        modelProducer = modelProducer,
        modifier = modifier,
        scrollState = scrollState,
        zoomState = zoomState
    )
}

private fun v2MarkerLine(color: Color, shape: Shape, size: Dp) = LineCartesianLayer.Line(
    fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
    areaFill = null,
    pointProvider = LineCartesianLayer.PointProvider.single(
        LineCartesianLayer.Point(component = ShapeComponent(fill = Fill(color), shape = shape), size = size)
    )
)

/** Offset of a manual bolus above the low mark. Vico gives a label and a point only the y, so y carries the amount. */
private const val MANUAL_BOLUS_Y_PER_UNIT = 1e-6

/**
 * Size of a manual bolus, as on the standard IOB graph: by its amount against the largest in the loaded range.
 * One series with a size per point, so that Vico shortens the labels of close boluses, see [TieredPointProvider].
 */
private class V2ManualBolusPoints(private val maxAmount: Double?, private val baseY: Double) : LineCartesianLayer.PointProvider {

    private val points = MarkerTier.entries.associateWith { tier ->
        LineCartesianLayer.Point(component = ShapeComponent(fill = Fill(BoostV2Colors.chartBolus), shape = InvertedTriangleShape), size = tier.markerSize)
    }

    override fun getPoint(entry: LineCartesianLayerModel.Entry, extraStore: ExtraStore): LineCartesianLayer.Point =
        points.getValue(tierOf((entry.y - baseY) / MANUAL_BOLUS_Y_PER_UNIT, maxAmount))

    override fun getLargestPoint(extraStore: ExtraStore): LineCartesianLayer.Point = points.getValue(MarkerTier.LARGE)
}

// ── Generic V2 sub-graph (IOB / activity) ────────────────────────────────────────

/** Makes Vico reprocess a transaction whose series are unchanged, so that it picks up a new Y range. */
private val V2_AXIS_SCALE_KEY = ExtraStore.Key<Any>()

/** The points inside the window on screen, as (x, value). Every point while the window is not known yet. */
internal fun List<Pair<Double, Double>>.inWindow(window: Pair<Double, Double>?): List<Pair<Double, Double>> =
    if (window == null) this else filter { it.first in window.first..window.second }

/** A sub-graph's Y range: fitted to the window on screen, falling back to all the data when it holds none. */
@Composable
private fun rememberV2SubGraphRange(
    points: List<Pair<Double, Double>>,
    visibleRange: Pair<Double, Double>?,
    maxX: Double,
    fromZero: Boolean = true
): CartesianLayerRangeProvider = remember(points, visibleRange, maxX, fromZero) {
    val values = points.inWindow(visibleRange).ifEmpty { points }.map { it.second }
    val low = values.minOrNull() ?: 0.0
    val high = values.maxOrNull() ?: 1.0
    val scale = if (fromZero) zeroFloorNiceRange(low, high.coerceAtLeast(0.0)) else niceScale(low, high)
    CartesianLayerRangeProvider.fixed(minX = 0.0, maxX = maxX, minY = scale.min, maxY = scale.max)
}

@Composable
private fun V2LineGraph(
    data: List<GraphDataPoint>,
    lineColor: Color,
    fillColor: Color,
    scrollState: VicoScrollState,
    zoomState: VicoZoomState,
    derivedTimeRange: Pair<Long, Long>?,
    nowTimestamp: Long,
    visibleRange: Pair<Double, Double>?,
    visibleRangeHolder: VisibleRangeHolder,
    inspect: GraphInspectState,
    modifier: Modifier = Modifier
) {
    val (minTimestamp, maxTimestamp) = derivedTimeRange ?: run {
        val now = System.currentTimeMillis()
        (now - Constants.GRAPH_TIME_RANGE_HOURS * 60 * 60 * 1000L) to now
    }
    val maxX = remember(minTimestamp, maxTimestamp) { timestampToX(maxTimestamp, minTimestamp) }
    val stableTimeRange = remember(minTimestamp / 60000, maxTimestamp / 60000) { minTimestamp to maxTimestamp }

    val processed = remember(data, stableTimeRange) {
        data.map { timestampToX(it.timestamp, minTimestamp) to it.value }
            .filter { it.first in 0.0..maxX }
            .sortedBy { it.first }
    }

    val modelProducer = remember { CartesianChartModelProducer() }
    val rangeProvider = rememberV2SubGraphRange(processed, visibleRange, maxX)
    // A new range only reaches Vico with a real transaction, and it skips one whose series and extras repeat the last.
    // Not cancelled half-way: that leaves the lines part-way between two scales, as SecondaryGraphCompose records.
    LaunchedEffect(processed, maxX, rangeProvider) {
        withContext(NonCancellable) { modelProducer.runTransaction {
            lineModel {
                if (processed.size >= 2) {
                    series(x = processed.map { it.first }, y = processed.map { it.second })
                } else {
                    series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                }
                series(x = normalizerX(maxX), y = NORMALIZER_Y)
            }
            extras { it[V2_AXIS_SCALE_KEY] = rangeProvider }
        } }
    }

    val line = remember(lineColor, fillColor) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(lineColor)),
            stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 1.5.dp),
            areaFill = LineCartesianLayer.AreaFill.single(Fill(fillColor)),
            interpolator = Square
        )
    }
    val normalizerLine = remember { createNormalizerLine() }
    val lines = remember(line, normalizerLine) { listOf(line, normalizerLine) }

    val visibleRangeReporter = rememberVisibleRangeReporter(visibleRangeHolder)
    val timeFormatter = rememberTimeFormatter(minTimestamp)
    val bottomAxisItemPlacer = rememberBottomAxisItemPlacer(minTimestamp)
    val nowLine = rememberNowLine(minTimestamp, nowTimestamp, BoostV2Colors.chartNowLine)
    val inspectLine = rememberInspectLine(inspect)

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(
                lineProvider = LineCartesianLayer.LineProvider.series(lines),
                rangeProvider = rangeProvider,
                verticalAxisPosition = Axis.Position.Vertical.Start
            ),
            startAxis = V2StartAxis(),
            bottomAxis = HorizontalAxis.rememberBottom(
                valueFormatter = timeFormatter,
                itemPlacer = bottomAxisItemPlacer,
                label = rememberTextComponent(style = TextStyle(color = BoostV2Colors.chartAxisLabel)),
                guideline = LineComponent(fill = Fill(BoostV2Colors.chartGrid))
            ),
            decorations = remember(nowLine, inspectLine, visibleRangeReporter) { listOfNotNull(nowLine, inspectLine, visibleRangeReporter) },
            getXStep = { _, _, _ -> 1.0 },
            marker = SilentInspectMarker,
            markerVisibilityListener = inspect.visibilityListener,
            markerController = inspect.controller
        ),
        modelProducer = modelProducer,
        modifier = modifier,
        scrollState = scrollState,
        zoomState = zoomState
    )
}

// ── V2 activity sub-graph (heart rate + steps) ───────────────────────────────────

/**
 * Activity sub-graph plotting heart rate and steps in distinct colours, mirroring the original Boost V2.
 * Either may be absent. As on the standard graphs each has its own axis when both are present: steps on
 * the left from zero, heart rate on the right fitted to its own values. A lone series uses the left axis.
 */
@Composable
private fun V2ActivityGraph(
    heartRates: List<GraphDataPoint>,
    steps: List<GraphDataPoint>,
    scrollState: VicoScrollState,
    zoomState: VicoZoomState,
    derivedTimeRange: Pair<Long, Long>?,
    nowTimestamp: Long,
    visibleRange: Pair<Double, Double>?,
    inspect: GraphInspectState,
    modifier: Modifier = Modifier
) {
    val (minTimestamp, maxTimestamp) = derivedTimeRange ?: run {
        val now = System.currentTimeMillis()
        (now - Constants.GRAPH_TIME_RANGE_HOURS * 60 * 60 * 1000L) to now
    }
    val maxX = remember(minTimestamp, maxTimestamp) { timestampToX(maxTimestamp, minTimestamp) }
    val stableTimeRange = remember(minTimestamp / 60000, maxTimestamp / 60000) { minTimestamp to maxTimestamp }

    fun project(data: List<GraphDataPoint>): List<Pair<Double, Double>> =
        data.map { timestampToX(it.timestamp, minTimestamp) to it.value }
            .filter { it.first in 0.0..maxX }
            .sortedBy { it.first }

    val hrPoints = remember(heartRates, stableTimeRange) { project(heartRates) }
    val stepsPoints = remember(steps, stableTimeRange) { project(steps) }

    val hasHr = hrPoints.size >= 2
    val hasSteps = stepsPoints.size >= 2
    val leftPoints = if (hasSteps) stepsPoints else if (hasHr) hrPoints else emptyList()
    val rightPoints = if (hasSteps && hasHr) hrPoints else emptyList()

    val modelProducer = remember { CartesianChartModelProducer() }
    val leftRange = rememberV2SubGraphRange(leftPoints, visibleRange, maxX, fromZero = hasSteps)
    val rightRange = rememberV2SubGraphRange(rightPoints, visibleRange, maxX, fromZero = false)
    // A new range only reaches Vico with a real transaction, and it skips one whose series and extras repeat the last.
    // Not cancelled half-way: that leaves the lines part-way between two scales, as SecondaryGraphCompose records.
    LaunchedEffect(leftPoints, rightPoints, maxX, leftRange, rightRange) {
        withContext(NonCancellable) { modelProducer.runTransaction {
            listOf(leftPoints, rightPoints).forEach { pts ->
                lineModel {
                    if (pts.isEmpty()) series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                    else series(x = pts.map { it.first }, y = pts.map { it.second })
                    series(x = normalizerX(maxX), y = NORMALIZER_Y)
                }
            }
            extras { it[V2_AXIS_SCALE_KEY] = leftRange to rightRange }
        } }
    }

    val hrLine = remember {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(BoostV2Colors.chartHeartRateLine)),
            stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 1.5.dp),
            areaFill = null,
            interpolator = Square
        )
    }
    val stepsLine = remember {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(BoostV2Colors.chartActivityLine)),
            stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 1.5.dp),
            areaFill = LineCartesianLayer.AreaFill.single(Fill(BoostV2Colors.chartActivityFill)),
            interpolator = Square
        )
    }
    val normalizerLine = remember { createNormalizerLine() }
    val leftLines = remember(hasSteps, hrLine, stepsLine, normalizerLine) { listOf(if (hasSteps) stepsLine else hrLine, normalizerLine) }
    val rightLines = remember(hrLine, normalizerLine) { listOf(hrLine, normalizerLine) }
    val hasRightAxis = rightPoints.isNotEmpty()

    val timeFormatter = rememberTimeFormatter(minTimestamp)
    val bottomAxisItemPlacer = rememberBottomAxisItemPlacer(minTimestamp)
    val nowLine = rememberNowLine(minTimestamp, nowTimestamp, BoostV2Colors.chartNowLine)
    val inspectLine = rememberInspectLine(inspect)

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(
                lineProvider = LineCartesianLayer.LineProvider.series(leftLines),
                rangeProvider = leftRange,
                verticalAxisPosition = Axis.Position.Vertical.Start
            ),
            rememberLineCartesianLayer(
                lineProvider = LineCartesianLayer.LineProvider.series(rightLines),
                rangeProvider = rightRange,
                verticalAxisPosition = Axis.Position.Vertical.End
            ),
            startAxis = V2StartAxis(),
            // Labels inside the plot in the colour of the series, as on the standard graphs, so it takes no width.
            endAxis = VerticalAxis.rememberEnd(
                line = null,
                label = rememberTextComponent(style = TextStyle(color = BoostV2Colors.chartHeartRateLine)),
                horizontalLabelPosition = VerticalAxis.HorizontalLabelPosition.Inside,
                tick = null,
                guideline = null,
                itemPlacer = remember(hasRightAxis) { if (hasRightAxis) VerticalAxis.ItemPlacer.step() else NoLabelsAxisItemPlacer }
            ),
            bottomAxis = HorizontalAxis.rememberBottom(
                valueFormatter = timeFormatter,
                itemPlacer = bottomAxisItemPlacer,
                label = rememberTextComponent(style = TextStyle(color = BoostV2Colors.chartAxisLabel)),
                guideline = LineComponent(fill = Fill(BoostV2Colors.chartGrid))
            ),
            decorations = remember(nowLine, inspectLine) { listOfNotNull(nowLine, inspectLine) },
            getXStep = { _, _, _ -> 1.0 },
            marker = SilentInspectMarker,
            markerVisibilityListener = inspect.visibilityListener,
            markerController = inspect.controller
        ),
        modelProducer = modelProducer,
        modifier = modifier,
        scrollState = scrollState,
        zoomState = zoomState
    )
}

@Composable
private fun V2StartAxis() = VerticalAxis.rememberStart(
    label = rememberTextComponent(
        style = TextStyle(color = BoostV2Colors.chartAxisLabel),
        minWidth = TextComponent.MinWidth.fixed(30.dp)
    ),
    guideline = LineComponent(fill = Fill(BoostV2Colors.chartGrid))
)
