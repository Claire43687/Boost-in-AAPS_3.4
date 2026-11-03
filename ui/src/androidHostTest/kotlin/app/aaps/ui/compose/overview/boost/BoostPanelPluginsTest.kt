package app.aaps.ui.compose.overview.boost

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class BoostPanelPluginsTest {

    private fun plugin(type: PluginType, enabled: Boolean = true, content: Boolean = true, listed: Boolean = true): PluginBase {
        val plugin = mock<PluginBase>()
        whenever(plugin.pluginDescription).thenReturn(PluginDescription().mainType(type))
        whenever(plugin.isEnabled(type)).thenReturn(enabled)
        whenever(plugin.hasComposeContent()).thenReturn(content)
        whenever(plugin.showInList()).thenReturn(listed)
        return plugin
    }

    @Test fun `a plugin needs to be enabled, listed and have a page`() {
        val shown = plugin(PluginType.SYNC)
        val panels = boostPanelPlugins(
            listOf(plugin(PluginType.SYNC, enabled = false), plugin(PluginType.SYNC, content = false), plugin(PluginType.SYNC, listed = false), shown)
        )
        assertThat(panels).containsExactly(shown)
    }

    @Test fun `the pump comes before the integrations`() {
        val sync = plugin(PluginType.SYNC)
        val aps = plugin(PluginType.APS)
        val pump = plugin(PluginType.PUMP)
        assertThat(boostPanelPlugins(listOf(sync, aps, pump))).containsExactly(pump, aps, sync).inOrder()
    }
}
