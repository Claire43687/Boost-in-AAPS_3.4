package app.aaps.workflow

import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.widget.WidgetUpdater
import app.aaps.core.interfaces.workflow.CalculationSignalsEmitter
import app.aaps.core.objects.workflow.WorkOutcome
import app.aaps.shared.tests.TestBaseWithProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertIs

class PostCalculationRunnerTest : TestBaseWithProfile() {

    @Mock lateinit var workflowChainData: WorkflowChainData
    @Mock lateinit var loop: Loop
    @Mock lateinit var widgetUpdater: WidgetUpdater
    @Mock lateinit var processedDeviceStatusData: ProcessedDeviceStatusData

    private fun runner() =
        PostCalculationRunner(
            aapsLogger, workflowChainData, loop,
            widgetUpdater, config, processedDeviceStatusData, profileUtil, preferences
        )

    // Nothing in these tests stops a run part way.
    private suspend fun run() = runner().run(job = "main", generation = 1L, isStopped = { false })

    private fun dataWith(runLoopAndWidgetPhase: Boolean): PostCalculationData {
        val cache = mock<OverviewDataCache>()
        whenever(cache.timeRangeFlow).thenReturn(MutableStateFlow(null))
        return PostCalculationData(
            overviewData = mock<OverviewData>(),
            cache = cache,
            signals = mock<CalculationSignalsEmitter>(),
            runLoopAndWidgetPhase = runLoopAndWidgetPhase
        )
    }

    @BeforeEach
    fun setup() {
        whenever(config.APS).thenReturn(false)
    }

    @Test
    fun `missing or stale data returns failure`() = runTest {
        whenever(workflowChainData.postFor(any(), any())).thenReturn(null)

        assertIs<WorkOutcome.Failure>(run())
    }

    @Test
    fun `predictions only phase returns success without loop or widget`() = runTest {
        val data = dataWith(runLoopAndWidgetPhase = false)
        whenever(workflowChainData.postFor(any(), any())).thenReturn(data)

        val result = run()

        Assertions.assertEquals(WorkOutcome.Success, result)
        verify(loop, never()).invoke(any(), any(), any())
        verify(widgetUpdater, never()).update(any())
    }

    // The loop no longer runs here: PrepareGraphDataRunner starts it after IOB/COB (CalculationLoopTrigger).
    @Test
    fun `full phase updates the widget and does not run the loop itself`() = runTest {
        val data = dataWith(runLoopAndWidgetPhase = true)
        whenever(workflowChainData.postFor(any(), any())).thenReturn(data)

        val result = run()

        Assertions.assertEquals(WorkOutcome.Success, result)
        verify(widgetUpdater).update("WorkFlow")
        verify(loop, never()).invoke(any(), any(), any())
    }
}
