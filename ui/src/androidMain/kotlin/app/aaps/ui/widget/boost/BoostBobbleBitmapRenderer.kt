package app.aaps.ui.widget.boost

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import app.aaps.core.data.model.TrendArrow
import app.aaps.ui.compose.overview.boost.trendToAngles
import kotlin.math.cos
import kotlin.math.sin

/** Input for [BoostBobbleBitmapRenderer]; the texts are already in the user's units. */
data class BoostBobbleInput(
    val bgMgdl: Double,
    val bgText: String,
    val unitsLabel: String,
    val isActual: Boolean,
    val trend: TrendArrow?
)

/**
 * Draws the Boost widget's glucose bobble: a ring in the glucose zone colour, the value and units
 * inside it, and a trend chevron on a badge placed round the ring. A widget cannot host a custom
 * view, so the bobble is a bitmap. The drawing is the 6.1.2 widget's, unchanged.
 */
object BoostBobbleBitmapRenderer {

    fun render(sizePx: Int, density: Float, input: BoostBobbleInput): Bitmap {
        val (trendAngle, chevronRotation) = trendToAngles(input.trend)

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val dp = density
        val sz = sizePx.toFloat()
        val cx = sz / 2f
        val cy = sz / 2f
        val sw = 8f * dp
        val r = (sz - sw * 2 - 16 * dp) / 2f
        val br = 14f * dp
        val z = BoostWidgetFormat.zoneColor(input.bgMgdl)

        // Ring background
        val ringBgP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = sw
            color = Color.argb(15, Color.red(z), Color.green(z), Color.blue(z))
        }
        c.drawCircle(cx, cy, r, ringBgP)

        // Full ring
        val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = sw; strokeCap = Paint.Cap.ROUND; color = z
        }
        c.drawArc(cx - r, cy - r, cx + r, cy + r, -90f, 360f, false, ringP)

        // Inner fill
        val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(30, Color.red(z), Color.green(z), Color.blue(z))
        }
        c.drawCircle(cx, cy, r - sw / 2f - 4f * dp, fillP)

        // BG text, struck through when the reading is not current
        val ir = r - sw / 2f - 4f * dp
        val bgTxtP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER; isFakeBoldText = true
            color = z; textSize = ir * 0.58f
            if (!input.isActual) flags = flags or Paint.STRIKE_THRU_TEXT_FLAG
        }
        c.drawText(input.bgText, cx, cy + bgTxtP.textSize * 0.15f, bgTxtP)

        // Units label
        val unitP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER; color = Color.argb(128, 255, 255, 255)
            textSize = ir * 0.16f
        }
        c.drawText(input.unitsLabel, cx, cy + bgTxtP.textSize * 0.15f + unitP.textSize * 1.8f, unitP)

        // Trend chevron badge on the ring
        val rad = Math.toRadians((trendAngle - 90).toDouble())
        val bx = cx + r * cos(rad).toFloat()
        val by = cy + r * sin(rad).toFloat()

        val badgeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = BADGE_OUTER }
        c.drawCircle(bx, by, br, badgeP)
        badgeP.color = BADGE_INNER
        c.drawCircle(bx, by, br - 2f * dp, badgeP)
        val badgeBdrP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * dp; color = z }
        c.drawCircle(bx, by, br - 2f * dp, badgeBdrP)

        val cs = br * 0.45f
        val chevP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3f * dp; strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND; color = z
        }
        val path = Path().apply {
            moveTo(-cs * 0.6f, -cs)
            lineTo(cs * 0.6f, 0f)
            lineTo(-cs * 0.6f, cs)
        }
        c.save()
        c.translate(bx, by)
        c.rotate(chevronRotation)
        c.drawPath(path, chevP)
        c.restore()

        return bitmap
    }

    private const val BADGE_OUTER: Int = 0xFF121212.toInt()
    private const val BADGE_INNER: Int = 0xFF1E1E1E.toInt()
}
