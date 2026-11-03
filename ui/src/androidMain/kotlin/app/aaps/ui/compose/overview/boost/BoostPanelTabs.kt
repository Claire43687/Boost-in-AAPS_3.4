package app.aaps.ui.compose.overview.boost

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.ui.compose.navigation.label
import app.aaps.core.ui.compose.stringResourceOrNull

/** The order 3.4 kept its plugin tabs in, near enough: the device first, the integrations last. */
private val PANEL_TYPE_ORDER = listOf(
    PluginType.PUMP, PluginType.APS, PluginType.LOOP, PluginType.BGSOURCE, PluginType.SENSITIVITY,
    PluginType.SMOOTHING, PluginType.CALIBRATION, PluginType.CONSTRAINTS, PluginType.SYNC, PluginType.GENERAL
)

/** The plugins with a page of their own, by the rule the quick-launch list and search use. */
fun boostPanelPlugins(plugins: List<PluginBase>): List<PluginBase> =
    plugins
        .filter { it.isEnabled(it.pluginDescription.mainType) && it.hasComposeContent() && it.showInList() }
        .sortedBy { PANEL_TYPE_ORDER.indexOf(it.pluginDescription.mainType) }

private class BoostPanelTab(val label: String, val onClick: () -> Unit)

/**
 * The panels 3.4 kept as tabs, one tap from the overview. Each opens its own screen and Back returns,
 * so the overview needs no pager and the graph keeps its sideways gestures.
 */
@Composable
internal fun BoostPanelTabs(
    plugins: List<PluginBase>,
    isTreatmentsEnabled: Boolean,
    actionsLabel: String,
    onActions: () -> Unit,
    onElement: (ElementType) -> Unit,
    onPlugin: (PluginBase) -> Unit,
    modifier: Modifier = Modifier
) {
    @Composable
    fun element(type: ElementType) = BoostPanelTab(stringResourceOrNull(type.label()).orEmpty()) { onElement(type) }

    val tabs = buildList {
        add(BoostPanelTab(actionsLabel, onActions))
        // The drawer holds Treatments back until there is a profile, for the same reason.
        if (isTreatmentsEnabled) add(element(ElementType.TREATMENTS))
        add(element(ElementType.INSULIN_MANAGEMENT))
        add(element(ElementType.PROFILE_MANAGEMENT))
        add(element(ElementType.AUTOMATION_MANAGEMENT))
        add(element(ElementType.FOOD_MANAGEMENT))
        add(element(ElementType.STATISTICS))
        add(element(ElementType.HISTORY_BROWSER))
        plugins.forEach { plugin -> add(BoostPanelTab(plugin.name) { onPlugin(plugin) }) }
        add(element(ElementType.MAINTENANCE))
        add(element(ElementType.CONFIGURATION))
    }

    LazyRow(modifier = modifier.fillMaxWidth().background(BoostV2Colors.cardBackground)) {
        items(tabs) { tab ->
            Text(
                text = tab.label,
                color = BoostV2Colors.textPrimary,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable(onClick = tab.onClick)
                    .heightIn(min = 48.dp)
                    .wrapContentHeight()
                    .padding(horizontal = 12.dp)
            )
        }
    }
}
