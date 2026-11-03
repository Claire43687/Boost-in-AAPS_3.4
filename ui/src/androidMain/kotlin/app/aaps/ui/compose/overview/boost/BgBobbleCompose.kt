package app.aaps.ui.compose.overview.boost

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aaps.core.data.model.TrendArrow
import kotlin.math.cos
import kotlin.math.sin

/**
 * Compose reimplementation of the Boost BG "bobble" (V2 overview centre element).
 *
 * Draws a full outer ring coloured by 5-zone glucose classification (see [bgZoneColor]),
 * a faint filled inner circle, the BG value + units label, and a trend-chevron badge positioned
 * on the ring circumference. The BG text is struck through when the reading is not an actual CGM
 * value ([BoostUiState.isActualBg] = false), mirroring the source `BgBobbleView`.
 *
 * Not clickable — carries a [contentDescription] for accessibility only.
 */
@Composable
fun BoostBgBobble(
    uiState: BoostUiState,
    modifier: Modifier = Modifier,
    size: Dp = 140.dp
) {
    val density = LocalDensity.current
    val bgMgdl = uiState.bgValueMgdl
    val zoneColor = bgZoneColor(bgMgdl)
    val (trendAngle, chevronRotation) = trendToAngles(uiState.trendArrow)

    val bgText = uiState.bgDisplay
    // The profile's units. bgValueMgdl is always mg/dL, so testing its size labelled every mmol/L reading "mg/dL".
    val unitsLabel = uiState.glucoseUnits
    val isStruck = !uiState.isActualBg

    val secondaryArgb = BoostV2Colors.textSecondary.toArgb()
    val surfaceColor = BoostV2Colors.cardBackground
    val strokeColor = BoostV2Colors.stroke

    val a11y = "Blood glucose $bgText $unitsLabel, ${uiState.deltaDisplay}, ${uiState.timeAgoDisplay}"

    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = a11y }
    ) {
        val dp = density.density
        val sz = this.size.width
        val cx = sz / 2f
        val cy = sz / 2f
        val sw = 8f * dp                        // ring stroke width
        val r = (sz - sw * 2 - 16 * dp) / 2f    // ring radius
        val br = 14f * dp                       // badge radius

        // Faint background ring
        drawCircle(zoneColor.copy(alpha = 0.06f), radius = r, center = Offset(cx, cy), style = Stroke(width = sw))
        // Solid zone ring
        drawCircle(zoneColor, radius = r, center = Offset(cx, cy), style = Stroke(width = sw))
        // Inner disc — zone colour, low alpha
        val innerR = r - sw / 2f - 4f * dp
        drawCircle(zoneColor.copy(alpha = 0.12f), radius = innerR, center = Offset(cx, cy))

        drawContext.canvas.nativeCanvas.apply {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
                textSize = innerR * 0.58f
                color = zoneColor.toArgb()
                if (isStruck) flags = flags or Paint.STRIKE_THRU_TEXT_FLAG
            }
            drawText(bgText, cx, cy + paint.textSize * 0.15f, paint)

            val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                textSize = innerR * 0.16f
                color = secondaryArgb
            }
            drawText(unitsLabel, cx, cy + paint.textSize * 0.15f + unitPaint.textSize * 1.8f, unitPaint)
        }

        drawTrendBadge(
            cx = cx, cy = cy, ringRadius = r, badgeRadius = br,
            trendAngleDeg = trendAngle, chevronRotateDeg = chevronRotation,
            zoneColor = zoneColor, surfaceColor = surfaceColor, ringDim = strokeColor, dp = dp
        )
    }
}

private fun DrawScope.drawTrendBadge(
    cx: Float, cy: Float, ringRadius: Float, badgeRadius: Float,
    trendAngleDeg: Float, chevronRotateDeg: Float,
    zoneColor: Color, surfaceColor: Color, ringDim: Color, dp: Float
) {
    val rad = Math.toRadians((trendAngleDeg - 90).toDouble())
    val bx = cx + ringRadius * cos(rad).toFloat()
    val by = cy + ringRadius * sin(rad).toFloat()

    drawCircle(surfaceColor, radius = badgeRadius, center = Offset(bx, by))
    drawCircle(ringDim, radius = badgeRadius - 2f * dp, center = Offset(bx, by))
    drawCircle(zoneColor, radius = badgeRadius - 2f * dp, center = Offset(bx, by), style = Stroke(width = 2.5f * dp))

    val cs = badgeRadius * 0.45f
    val chevron = Path().apply {
        moveTo(-cs * 0.6f, -cs)
        lineTo(cs * 0.6f, 0f)
        lineTo(-cs * 0.6f, cs)
    }
    translate(left = bx, top = by) {
        // Pivot on the badge: the default pivot is the canvas centre, which moves the arrow out of its circle.
        rotate(degrees = chevronRotateDeg, pivot = Offset.Zero) {
            drawPath(chevron, color = zoneColor, style = Stroke(width = 3f * dp))
        }
    }
}

/**
 * Convert a [TrendArrow] to (ringAngle, chevronRotation). Ring angle positions the badge on the
 * circumference (0° = 12 o'clock); chevron rotation points the ">" in the trend direction.
 * Same mapping as the source `BgBobbleView.trendToAngles`. The home-screen Boost widget draws its
 * bobble with the same mapping, so it is internal rather than private.
 */
internal fun trendToAngles(trend: TrendArrow?): Pair<Float, Float> = when (trend) {
    TrendArrow.DOUBLE_UP       -> 0f to -90f
    TrendArrow.TRIPLE_UP       -> 0f to -90f
    TrendArrow.SINGLE_UP       -> 30f to -60f
    TrendArrow.FORTY_FIVE_UP   -> 60f to -30f
    TrendArrow.FLAT            -> 90f to 0f
    TrendArrow.FORTY_FIVE_DOWN -> 120f to 30f
    TrendArrow.SINGLE_DOWN     -> 150f to 60f
    TrendArrow.DOUBLE_DOWN     -> 180f to 90f
    TrendArrow.TRIPLE_DOWN     -> 180f to 90f
    else                       -> 90f to 0f   // NONE / unknown → flat
}
