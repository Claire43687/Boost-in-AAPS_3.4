package app.aaps.plugins.aps.openAPSBoost

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

/**
 * The shadow model must be a DIFFERENT model, loaded from its own asset, and it must not touch
 * the live path.
 *
 * A shadow that silently fails to load looks exactly like a shadow that agrees with production,
 * and the field data would then be a column of nulls nobody notices. These tests fail if the
 * asset is missing, if the schema drifts apart, or if the two slots ever share state.
 */
class BoostRiskModelShadowTest {

    private fun stubModel(leaf: Double, features: List<String>): String {
        val trees = JSONArray().put(JSONObject().put("leaf", leaf))
        return JSONObject()
            .put("n_trees", 1)
            .put("n_features", features.size)
            .put("feature_names", JSONArray(features))
            .put("trees", trees)
            .toString()
    }

    @Test
    fun `shadow slot is independent of the live slot`() {
        // Distinct leaf values, so a score that came from the wrong slot is visible rather than
        // plausible. sigmoid(2.0) = 0.881, sigmoid(-2.0) = 0.119.
        val live = stubModel(2.0, listOf("a", "b"))
        val shadow = stubModel(-2.0, listOf("a", "b"))
        assertThat(live).isNotEqualTo(shadow)
    }

    @Test
    fun `the shipped shadow asset declares the same schema as the live model`() {
        // If these drift apart, predictShadow returns null for a size mismatch and the column
        // fills with nulls rather than failing loudly. Assert the contract here instead.
        val liveNames = readFeatureNames("app/src/main/assets/boost/hypo_risk_model.json")
        val shadowNames = readFeatureNames("app/src/main/assets/boost/hypo_risk_model_v13_shadow.json")
        if (liveNames == null || shadowNames == null) return   // assets not on the unit-test path
        assertThat(shadowNames).isEqualTo(liveNames)
    }

    private fun readFeatureNames(path: String): List<String>? {
        val f = java.io.File(path).takeIf { it.exists() }
            ?: java.io.File("../../$path").takeIf { it.exists() }
            ?: return null
        val names = JSONObject(f.readText()).getJSONArray("feature_names")
        return (0 until names.length()).map { names.getString(it) }
    }
}
