package app.aaps.plugins.aps.openAPSBoost

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import org.json.JSONObject
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn

/**
 * Lightweight on-device hypo risk model for Boost.
 *
 * Loads a LightGBM model exported as JSON from the APK's assets and runs inference via
 * pure-Kotlin tree traversal — no native library dependencies.
 *
 * CURRENT model (v12): 100 trees, 53 features (17 static + 36 windowed lag0..5
 * lookback), ~368KB. The feature vector is assembled by [BoostMlFeatureBuilder] from cycle
 * state + a persisted 6-cycle ring buffer. The legacy 8-feature entry point
 * [predictHypoRisk] is retained for rollback to pre-v10 models (it no-ops via size-mismatch
 * → null when a 53-feature model is loaded).
 *
 * Trained on a 28-user cohort with Leave-One-User-Out cross-validation; out-of-cohort
 * transfer validated.
 *
 * Output: P(hypo event in next 4h) as a Double in [0, 1].
 *
 * CONSUMPTION (doc — the old "Layer A observability-only" text was stale):
 * Layer B is LIVE in the V1 engine (graduated riskScale on the microBolus + tier downgrade at
 * risk > 0.6), the V5/V6 AggressionBudget consumes it as mlHypoRiskScale, and
 * [predictAtProjectedIob] powers V5's Phase-3 postActionRiskCheck (expected ~never to fire
 * with the v12 model — see its KDoc).
 */
@SingleIn(AppScope::class)
class BoostRiskModel @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) {

    private var trees: List<TreeNode>? = null
    private var featureNames: List<String>? = null
    @Volatile private var loaded = false
    @Volatile private var loadAttempted = false
    private val loadLock = Any()
    private val defaultAssetPath = "boost/hypo_risk_model.json"

    /**
     * Idempotent, thread-safe lazy loader. Called from predictHypoRisk()
     * so the model is guaranteed available on first inference after a
     * process restart, regardless of whether the user has opened the
     * settings screen. Falls back to no-op once a load attempt has been
     * made, so we don't retry on every cycle if the asset is missing.
     */
    private fun ensureLoaded() {
        if (loaded || loadAttempted) return
        synchronized(loadLock) {
            if (loaded || loadAttempted) return
            loadModel(context, defaultAssetPath)
            loadAttempted = true
        }
    }

    data class TreeNode(
        val isLeaf: Boolean,
        val leafValue: Double = 0.0,
        val featureIndex: Int = -1,
        val threshold: Double = 0.0,
        val left: TreeNode? = null,
        val right: TreeNode? = null,
    )

    fun loadModel(context: Context, assetPath: String = "boost/hypo_risk_model.json"): Boolean {
        return try {
            val rawBytes = context.assets.open(assetPath).readBytes()
            val jsonStr = String(rawBytes, Charsets.UTF_8)
            aapsLogger.info(LTag.APS, "BoostRiskModel loading $assetPath (${rawBytes.size} bytes)")
            val json = JSONObject(jsonStr)

            featureNames = mutableListOf<String>().apply {
                val arr = json.getJSONArray("feature_names")
                for (i in 0 until arr.length()) add(arr.getString(i))
            }

            val treesArr = json.getJSONArray("trees")
            trees = mutableListOf<TreeNode>().apply {
                for (i in 0 until treesArr.length()) {
                    try {
                        add(parseNode(treesArr.getJSONObject(i)))
                    } catch (e: Exception) {
                        // Emit at BOTH error and info — exported log captures only info.
                        aapsLogger.error(LTag.APS, "BoostRiskModel tree $i parse failed: ${e.javaClass.simpleName}: ${e.message}")
                        aapsLogger.info(LTag.APS, "BoostRiskModel DIAG tree $i parse failed: ${e.javaClass.simpleName}: ${e.message}")
                        throw e
                    }
                }
            }

            loaded = true
            aapsLogger.info(LTag.APS, "BoostRiskModel loaded: ${trees?.size} trees, ${featureNames?.size} features from $assetPath")
            true
        } catch (e: Exception) {
            // Emit at BOTH error and info — exported log captures only info.
            aapsLogger.error(LTag.APS, "BoostRiskModel failed to load from $assetPath: ${e.javaClass.simpleName}: ${e.message}")
            aapsLogger.info(LTag.APS, "BoostRiskModel DIAG load FAILED from $assetPath: ${e.javaClass.simpleName}: ${e.message}")
            loaded = false
            false
        }
    }

    private fun parseNode(json: JSONObject): TreeNode {
        if (json.has("leaf")) {
            return TreeNode(isLeaf = true, leafValue = json.getDouble("leaf"))
        }
        return TreeNode(
            isLeaf = false,
            featureIndex = json.getInt("feature"),
            threshold = json.getDouble("threshold"),
            left = parseNode(json.getJSONObject("left")),
            right = parseNode(json.getJSONObject("right")),
        )
    }

    /**
     * Predict P(hypo event in next 4h) given the 8 input features.
     * Returns a Double in [0, 1], or null if the model isn't loaded.
     *
     * @param cgmMgdl Current BG in mg/dL
     * @param iobTotal Total insulin on board (U)
     * @param iobBasal Basal IOB component (signed, U)
     * @param bgAboveTarget BG minus algorithm target (mg/dL)
     * @param directionNum BG trend as numeric (-2 to +2)
     * @param hour Hour of day (0-23)
     * @param iobActivity Insulin activity (U/5min)
     * @param insulinReq Algorithm's insulin requirement this cycle (U)
     */
    fun predictHypoRisk(
        cgmMgdl: Double,
        iobTotal: Double,
        iobBasal: Double,
        bgAboveTarget: Double,
        directionNum: Double,
        hour: Int,
        iobActivity: Double,
        insulinReq: Double
    ): Double? {
        ensureLoaded()
        if (!loaded || trees == null) return null
        val features = doubleArrayOf(
            cgmMgdl, iobTotal, iobBasal, bgAboveTarget,
            directionNum, hour.toDouble(), iobActivity, insulinReq
        )
        return predict(features)
    }

    /**
     * Generic predict — accepts a feature vector matching the model's declared
     * featureNames order. Used by callers that ship richer feature schemas (v10+).
     * Returns null if the model hasn't loaded or the vector size mismatches.
     */
    fun predict(features: DoubleArray): Double? {
        ensureLoaded()
        val modelTrees = trees ?: return null
        if (!loaded) return null
        val expected = featureNames?.size ?: features.size
        if (features.size != expected) {
            aapsLogger.error(LTag.APS, "BoostRiskModel feature size mismatch: got ${features.size}, expected $expected")
            return null
        }
        // Cache this cycle's vector so postActionRiskCheck can re-score at projected IOB.
        lastFeatures = features.copyOf()
        return score(modelTrees, features)
    }

    // Most recent successfully-scored feature vector (this cycle's, in practice — predict() runs
    // once per cycle before any projected re-score). Basis for [predictAtProjectedIob].
    @Volatile private var lastFeatures: DoubleArray? = null

    /**
     * Re-score the hypo-risk model at the PROJECTED post-SMB state (current IOB + prospective dose),
     * reusing this cycle's cached feature vector with the post-state features adjusted: `iob_iob`
     * AND `iob_iob_lag0` (the schema duplicates current IOB in the lookback block — both must move
     * or the vector is internally inconsistent), `iob_bolusiob` += delta, `recent_smb_units_60m`
     * (+lag0) += delta, `time_since_last_smb_min` := 0. History lags (lag1..5) untouched.
     * Powers V5 Phase-3 `postActionRiskCheck` on the ACTIVE path.
     *
     * HONEST EXPECTATION (backtest, offline tree re-run over 210 real meal-state dose
     * vectors incl. a forced falling-BG probe up to +3U): the v12 model's learned IOB→risk response
     * is flat-to-INVERTED (high IOB co-occurs with meals in training → fewer hypos in 4h), so
     * projected risk ≤ current and the gate is expected to ~never fire with THIS model. Kept wired
     * because it is zero-risk (lower projection → pass-through, identical to the old null wiring),
     * costs one tree-walk, and becomes live automatically if the model is retrained with a
     * causally-correct IOB response. The doses' actual post-action protection remains
     * iobHeadroomBrake + the maxIOB clamp + the state caps. A DETERMINISTIC post-action check
     * (projected eventualBG = eventualBG − dose×ISF vs target) is the candidate real brake — own
     * design + backtest, not smuggled in here.
     * Returns null (→ gate passes through) when the model/vector/feature names are unavailable.
     */
    fun predictAtProjectedIob(projectedIob: Double): Double? {
        val modelTrees = trees ?: return null
        if (!loaded) return null
        val base = lastFeatures ?: return null
        val names = featureNames ?: return null
        val iobIdx = names.indexOf("iob_iob")
        if (iobIdx < 0 || iobIdx >= base.size) return null
        val f = base.copyOf()
        val delta = projectedIob - f[iobIdx]
        f[iobIdx] = projectedIob
        fun set(name: String, v: (Double) -> Double) {
            val i = names.indexOf(name); if (i in f.indices) f[i] = v(f[i])
        }
        set("iob_iob_lag0") { projectedIob }
        set("iob_bolusiob") { (it + delta).coerceAtLeast(0.0) }
        set("recent_smb_units_60m") { (it + delta).coerceAtLeast(0.0) }
        set("recent_smb_units_60m_lag0") { (it + delta).coerceAtLeast(0.0) }
        set("time_since_last_smb_min") { 0.0 }
        return score(modelTrees, f)
    }

    private fun score(modelTrees: List<TreeNode>, features: DoubleArray): Double {
        var rawScore = 0.0
        for (tree in modelTrees) {
            rawScore += walkTree(tree, features)
        }
        return 1.0 / (1.0 + Math.exp(-rawScore))
    }

    private fun walkTree(node: TreeNode, features: DoubleArray): Double {
        if (node.isLeaf) return node.leafValue
        return if (features[node.featureIndex] <= node.threshold) {
            walkTree(node.left!!, features)
        } else {
            walkTree(node.right!!, features)
        }
    }

    fun isLoaded(): Boolean = loaded
    fun getFeatureNames(): List<String>? {
        // trigger lazy-load on first access so callers that probe
        // the schema before the first predict() (e.g., dual-path inference
        // routing in DetermineBasalBoost) actually cause the asset to load.
        // Previously this was a plain getter that returned null forever if no
        // predict() had been called yet — which is exactly what the dual-path
        // code does on every cycle, perpetually short-circuiting load.
        ensureLoaded()
        return featureNames
    }
    fun getTreeCount(): Int = trees?.size ?: 0

    // ---------------------------------------------------------------------------------------
    // Shadow model. A second set of trees, scored on the SAME feature vector as the
    // live model and delivering nothing. It exists to answer, on real devices, whether the refit
    // trained on 183 Commons participants behaves as the offline comparison says it does.
    //
    // It is a second slot inside this class rather than a second injected instance deliberately.
    // Adding a qualifier and a provider would mean editing the Dagger graph for a component that
    // logs, and this engine has already lost five days of dosing to an annotation that drifted off
    // its class. A field costs nothing and cannot unbind anything.
    //
    // 🚨 THE TWO SCORES ARE NOT ON THE SAME SCALE AND MUST NOT BE COMPARED TO A FIXED THRESHOLD.
    // The refit was fitted on a 7.24% base rate and is far better calibrated to it, so on 1.7M
    // held-out rows it exceeds 0.30 on 42.2% of cycles where the live model exceeds it on 6.6%,
    // and 0.60 on 15.3% against 2.9%. Ranking is what improved: paired AUC +0.0143 [+0.0101,
    // +0.0185] with both models out of sample. Feeding this score to the live gates unchanged
    // would fire the damper six times as often. Any promotion has to re-place the thresholds on
    // the new distribution first; the equivalent-firing points measured offline are 0.786 and
    // 0.923.
    // ---------------------------------------------------------------------------------------
    private var shadowTrees: List<TreeNode>? = null
    private var shadowFeatureNames: List<String>? = null
    @Volatile private var shadowLoaded = false
    @Volatile private var shadowLoadAttempted = false
    private val shadowAssetPath = "boost/hypo_risk_model_v13_shadow.json"

    private fun ensureShadowLoaded() {
        if (shadowLoaded || shadowLoadAttempted) return
        synchronized(loadLock) {
            if (shadowLoaded || shadowLoadAttempted) return
            shadowLoadAttempted = true
            try {
                val raw = context.assets.open(shadowAssetPath).bufferedReader().readText()
                val json = JSONObject(raw)
                val names = json.getJSONArray("feature_names")
                shadowFeatureNames = (0 until names.length()).map { names.getString(it) }
                val arr = json.getJSONArray("trees")
                shadowTrees = buildList { for (i in 0 until arr.length()) add(parseNode(arr.getJSONObject(i))) }
                shadowLoaded = true
                aapsLogger.info(LTag.APS, "BoostRiskModel shadow loaded: ${shadowTrees?.size} trees, ${shadowFeatureNames?.size} features")
            } catch (e: Exception) {
                aapsLogger.info(LTag.APS, "BoostRiskModel shadow NOT loaded: ${e.javaClass.simpleName}: ${e.message}")
                shadowLoaded = false
            }
        }
    }

    /**
     * Score the shadow model on a feature vector already built for the live model.
     *
     * The caller passes the same array, so any difference between the two scores is the model and
     * not the feature build. Returns null when the shadow asset is absent or its schema does not
     * match, which is the intended behaviour on a build that ships no shadow: the live path is
     * untouched and nothing is logged.
     */
    fun predictShadow(features: DoubleArray): Double? {
        ensureShadowLoaded()
        val t = shadowTrees ?: return null
        if (!shadowLoaded) return null
        if (features.size != (shadowFeatureNames?.size ?: features.size)) return null
        return score(t, features)
    }

    fun isShadowLoaded(): Boolean { ensureShadowLoaded(); return shadowLoaded }
}
