package app.aaps.ui.compose.overview.boost

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class V2VisibleWindowTest {

    private val points = listOf(0.0 to 300.0, 60.0 to 120.0, 120.0 to 90.0)

    @Test fun `an unknown window keeps every point`() {
        assertThat(points.inWindow(null)).isEqualTo(points)
    }

    @Test fun `a high outside the window does not count`() {
        assertThat(points.inWindow(30.0 to 120.0).maxOf { it.second }).isEqualTo(120.0)
    }

    @Test fun `a window with no data gives nothing, so callers fall back`() {
        assertThat(points.inWindow(200.0 to 300.0)).isEmpty()
    }
}
