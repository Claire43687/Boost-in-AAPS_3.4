package app.aaps.plugins.aps.openAPSBoost

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import org.json.JSONObject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.math.exp

/**
 * Four-column hypoglycaemia shadow. READ-ONLY: scores every cycle and doses nothing.
 *
 * Inputs are the current glucose and the engine's three trend averages, all of which
 * `GlucoseStatus` already publishes on every cycle. No new sensor, no ring buffer, no
 * persisted state, and nothing to warm up after a process restart.
 *
 * Two models run side by side because the two labels are different jobs:
 *
 *  - sustained: glucose below 70 mg/dL continuously for at least 15 minutes, that episode
 *    beginning within 90 minutes. This is [BoostRiskModel]'s own label, so the two scores are
 *    directly comparable on-device and the comparison needs no offline join.
 *  - near: glucose touching 70 mg/dL at any point within 45 minutes. Scores higher but answers
 *    a shorter question, and would need its own place in the dose path rather than slotting
 *    into the existing one.
 *
 * Both were trained on the Loop observational study and deliberately NOT on this fork's cohort,
 * so every current user is out-of-sample and the live scores are an honest held-out test rather
 * than a re-reading of the fit that motivated them. Held out here the sustained model reaches
 * 0.8176 against 0.7485 for reading the current glucose directly, and the near model 0.9184
 * against 0.8718, each beating glucose alone for 36 of 36 participants. Against the deployed
 * mlHypoRisk on the cycles where it runs, the fit led by 0.068 on the sustained label
 * (95% CI 0.032 to 0.118) under leave-one-participant-out.
 *
 * One known distribution shift, recorded because it bounds what the live scores mean. Training
 * rebuilt the trend averages from raw CGM on a strict five-minute grid, whereas the engine
 * computes them from smoothed and bucketed values. Against the engine's own logged values the
 * reconstruction reached a median correlation of 0.998 (long), 0.987 (short) and 0.957 (delta),
 * so the inputs are close but not identical. Treat a live score as comparable to the offline
 * numbers to within that.
 *
 * Emitted in the reason string as `hypo4=sustained,near;` and never as an [app.aaps.core
 * .interfaces.aps.RT] field: RT is constructed inside `determine_basal` in every engine here and
 * one extra property costs one register in each, which the legacy V3MLG3 engine has no room for.
 * The extractor parses the tag back into columns.
 */
@SingleIn(AppScope::class)
class HypoFourColumnShadow @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) {

    /** Both probabilities for one cycle, each in [0, 1]. */
    data class Result(val sustained: Double, val near: Double)

    private class Node(
        val leaf: Double? = null,
        val feature: Int = -1,
        val threshold: Double = 0.0,
        val left: Node? = null,
        val right: Node? = null
    )

    private class Model(val trees: List<Node>, val featureCount: Int)

    private var sustained: Model? = null
    private var near: Model? = null

    /** Why the last call returned null, for the `hypo4skip=` diagnostic. */
    @Volatile var lastSkip: String = "init"
        private set

    @Volatile private var loadAttempted = false
    private val loadLock = Any()

    private fun ensureLoaded() {
        if (loadAttempted) return
        synchronized(loadLock) {
            if (loadAttempted) return
            loadAttempted = true
            sustained = load("boost/hypo_four_column_sustained.json")
            near = load("boost/hypo_four_column_near.json")
            aapsLogger.info(
                LTag.APS,
                "HypoFourColumnShadow: sustained=${sustained?.trees?.size ?: -1} trees, " +
                    "near=${near?.trees?.size ?: -1} trees"
            )
        }
    }

    private fun load(assetPath: String): Model? =
        try {
            val json = JSONObject(context.assets.open(assetPath).bufferedReader().readText())
            val arr = json.getJSONArray("trees")
            Model(
                trees = buildList { for (i in 0 until arr.length()) add(parse(arr.getJSONObject(i))) },
                featureCount = json.getJSONArray("feature_names").length()
            )
        } catch (e: Exception) {
            aapsLogger.info(LTag.APS, "HypoFourColumnShadow could not load $assetPath: ${e.javaClass.simpleName}: ${e.message}")
            null
        }

    private fun parse(o: JSONObject): Node =
        if (o.has("leaf")) Node(leaf = o.getDouble("leaf"))
        else Node(
            feature = o.getInt("feature"),
            threshold = o.getDouble("threshold"),
            left = parse(o.getJSONObject("left")),
            right = parse(o.getJSONObject("right"))
        )

    private fun walk(root: Node, x: DoubleArray): Double {
        var cur = root
        while (cur.leaf == null) {
            cur = if (x[cur.feature] <= cur.threshold) cur.left!! else cur.right!!
        }
        return cur.leaf!!
    }

    private fun score(m: Model, x: DoubleArray): Double {
        var raw = 0.0
        for (t in m.trees) raw += walk(t, x)
        return 1.0 / (1.0 + exp(-raw))
    }

    /**
     * Score one cycle. Feature order matches the exported `feature_names`:
     * glucose, delta, shortAvgDelta, longAvgDelta.
     *
     * Returns null when either model is missing or the glucose reading is outside the range the
     * models were trained on, in which case [lastSkip] says which.
     */
    fun evaluate(glucose: Double, delta: Double, shortAvgDelta: Double, longAvgDelta: Double): Result? {
        ensureLoaded()
        val s = sustained
        val n = near
        if (s == null || n == null) { lastSkip = "noasset"; return null }
        if (glucose < 20.0 || glucose > 500.0 || glucose.isNaN()) { lastSkip = "bg"; return null }
        if (delta.isNaN() || shortAvgDelta.isNaN() || longAvgDelta.isNaN()) { lastSkip = "nan"; return null }
        val x = doubleArrayOf(glucose, delta, shortAvgDelta, longAvgDelta)
        if (s.featureCount != x.size || n.featureCount != x.size) { lastSkip = "schema${x.size}"; return null }
        lastSkip = "ok"
        return Result(sustained = score(s, x), near = score(n, x))
    }
}
