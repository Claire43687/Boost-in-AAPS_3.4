package app.aaps.plugins.sync.garmin

import app.aaps.core.data.iob.CobInfo
import app.aaps.core.data.model.EPS
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.HR
import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.SC
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TB
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.bolus.MasterActionExecutor
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.shared.tests.TestBase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.milliseconds

class LoopHubTest : TestBase() {

    @Mock lateinit var commandQueue: CommandQueue
    @Mock lateinit var constraints: ConstraintsChecker
    @Mock lateinit var iobCobCalculator: IobCobCalculator
    @Mock lateinit var loop: Loop
    @Mock lateinit var profileFunction: ProfileFunction
    @Mock lateinit var profileUtil: ProfileUtil
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var processedTbrEbData: ProcessedTbrEbData
    @Mock lateinit var masterActionExecutor: MasterActionExecutor

    private lateinit var loopHub: LoopHubImpl
    private val clock = Clock.fixed(Instant.ofEpochMilli(10_000), ZoneId.of("UTC"))
    private val testScope = CoroutineScope(Dispatchers.Unconfined)

    @BeforeEach
    fun setup() {
        whenever(profileUtil.convertToMgdl(any(), any())).thenAnswer { i ->
            val v: Double = i.getArgument(0)
            val u: GlucoseUnit = i.getArgument(1)
            if (u == GlucoseUnit.MGDL) v else (18.0 * v)
        }
        loopHub = LoopHubImpl(
            aapsLogger, commandQueue, constraints, iobCobCalculator, loop,
            profileFunction, profileUtil, persistenceLayer, preferences, processedTbrEbData,
            masterActionExecutor, testScope
        )
        loopHub.clock = clock
    }

    @AfterEach
    fun verifyNoFurtherInteractions() {
        verifyNoMoreInteractions(commandQueue)
        verifyNoMoreInteractions(constraints)
        verifyNoMoreInteractions(iobCobCalculator)
        verifyNoMoreInteractions(loop)
        verifyNoMoreInteractions(profileFunction)
        verifyNoMoreInteractions(persistenceLayer)
    }

    @Test
    fun testCurrentProfile() = runTest {
        val profile = mock<EffectiveProfile>()
        whenever(profileFunction.getProfile()).thenReturn(profile)
        assertEquals(profile, loopHub.currentProfile)
        verify(profileFunction, times(1)).getProfile()
    }

    @Test
    fun testCurrentProfileName() = runTest {
        whenever(profileFunction.getProfileName()).thenReturn("pro")
        assertEquals("pro", loopHub.currentProfileName)
        verify(profileFunction, times(1)).getProfileName()
    }

    @Test
    fun testGlucoseRangeMgDl() {
        whenever(preferences.get(StringKey.GeneralUnits)).thenReturn("mg/dl")
        whenever(preferences.get(UnitDoubleKey.OverviewLowMark)).thenReturn(76.0)
        whenever(preferences.get(UnitDoubleKey.OverviewHighMark)).thenReturn(125.0)
        assertEquals(76.0, loopHub.lowGlucoseMark, 1e-6)
        assertEquals(125.0, loopHub.highGlucoseMark, 1e-6)
        verify(preferences, atLeast(1)).get(StringKey.GeneralUnits)
        verify(preferences).get(UnitDoubleKey.OverviewLowMark)
        verify(preferences).get(UnitDoubleKey.OverviewHighMark)
    }

    @Test
    fun testGlucoseRangeMmolL() {
        whenever(preferences.get(StringKey.GeneralUnits)).thenReturn("mmol")
        whenever(preferences.get(UnitDoubleKey.OverviewLowMark)).thenReturn(3.0)
        whenever(preferences.get(UnitDoubleKey.OverviewHighMark)).thenReturn(8.0)
        assertEquals(54.0, loopHub.lowGlucoseMark, 1e-6)
        assertEquals(144.0, loopHub.highGlucoseMark, 1e-6)
        verify(preferences, atLeast(1)).get(StringKey.GeneralUnits)
        verify(preferences).get(UnitDoubleKey.OverviewLowMark)
        verify(preferences).get(UnitDoubleKey.OverviewHighMark)
    }

    @Test
    fun testGlucoseUnit() {
        whenever(preferences.get(StringKey.GeneralUnits)).thenReturn("mg/dl")
        assertEquals(GlucoseUnit.MGDL, loopHub.glucoseUnit)
        whenever(preferences.get(StringKey.GeneralUnits)).thenReturn("mmol")
        assertEquals(GlucoseUnit.MMOL, loopHub.glucoseUnit)
    }

    @Test
    fun testInsulinOnBoard() = runTest {
        val iobTotal = IobTotal(time = 0).apply { iob = 23.9 }
        whenever(iobCobCalculator.calculateIobFromBolus()).thenReturn(iobTotal)
        assertEquals(23.9, loopHub.insulinOnboard, 1e-10)
        verify(iobCobCalculator, times(1)).calculateIobFromBolus()
    }

    @Test
    fun testBasalOnBoard() = runTest {
        val iobBasal = IobTotal(time = 0).apply { basaliob = 23.9 }
        whenever(iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended()).thenReturn(iobBasal)
        assertEquals(23.9, loopHub.insulinBasalOnboard, 1e-10)
        verify(iobCobCalculator, times(1)).calculateIobFromTempBasalsIncludingConvertedExtended()
    }

    @Test
    fun testCarbsOnBoard() = runTest {
        val cobInfo = CobInfo(0, 12.0, 0.0)
        whenever(iobCobCalculator.getCobInfo(anyString())).thenReturn(cobInfo)
        assertEquals(12.0, loopHub.carbsOnboard)
        verify(iobCobCalculator, times(1)).getCobInfo(anyString())
    }

    @Test
    fun testIsConnected() = runTest {
        whenever(loop.runningMode()).thenReturn(RM.Mode.CLOSED_LOOP)
        assertEquals(true, loopHub.isConnected)
        verify(loop, times(1)).runningMode()
    }

    private fun effectiveProfileSwitch(duration: Long) = EPS(
        timestamp = 100,
        basalBlocks = emptyList(),
        isfBlocks = emptyList(),
        icBlocks = emptyList(),
        targetBlocks = emptyList(),
        glucoseUnit = GlucoseUnit.MGDL,
        originalProfileName = "foo",
        originalCustomizedName = "bar",
        originalTimeshift = 0,
        originalPercentage = 100,
        originalDuration = duration,
        originalEnd = 100 + duration,
        iCfg = ICfg("label", 0, 0)
    )

    @Test
    fun testIsTemporaryProfileTrue() = runTest {
        val eps = effectiveProfileSwitch(10)
        whenever(persistenceLayer.getEffectiveProfileSwitchActiveAt(clock.millis())).thenReturn(eps)
        assertEquals(true, loopHub.isTemporaryProfile)
        verify(persistenceLayer, times(1)).getEffectiveProfileSwitchActiveAt(clock.millis())
    }

    @Test
    fun testIsTemporaryProfileFalse() = runTest {
        val eps = effectiveProfileSwitch(0)
        whenever(persistenceLayer.getEffectiveProfileSwitchActiveAt(clock.millis())).thenReturn(eps)
        assertEquals(false, loopHub.isTemporaryProfile)
        verify(persistenceLayer).getEffectiveProfileSwitchActiveAt(clock.millis())
    }

    @Test
    fun testTemporaryBasal() = runTest {
        val profile = mock<EffectiveProfile>()
        whenever(profileFunction.getProfile()).thenReturn(profile)
        val tb = mock<TB> {
            on { isAbsolute }.thenReturn(false)
            on { rate }.thenReturn(45.0)
        }
        whenever(processedTbrEbData.getTempBasalIncludingConvertedExtended(clock.millis())).thenReturn(tb)
        assertEquals(0.45, loopHub.temporaryBasal, 1e-6)
        verify(profileFunction, times(1)).getProfile()
    }

    @Test
    fun testTemporaryBasalAbsolute() = runTest {
        val profile = mock<EffectiveProfile> {
            on { getBasal(clock.millis()) }.thenReturn(2.0)
        }
        whenever(profileFunction.getProfile()).thenReturn(profile)
        val tb = mock<TB> {
            on { isAbsolute }.thenReturn(true)
            on { rate }.thenReturn(0.9)
        }
        whenever(processedTbrEbData.getTempBasalIncludingConvertedExtended(clock.millis())).thenReturn(tb)
        assertEquals(0.45, loopHub.temporaryBasal, 1e-6)
        verify(profileFunction, times(1)).getProfile()
    }

    @Test
    fun testTemporaryBasalNoRun() = runTest {
        val profile = mock<EffectiveProfile>()
        whenever(profileFunction.getProfile()).thenReturn(profile)
        whenever(processedTbrEbData.getTempBasalIncludingConvertedExtended(clock.millis())).thenReturn(null)
        assertTrue(loopHub.temporaryBasal.isNaN())
        verify(profileFunction, times(1)).getProfile()
    }

    @Test
    fun testConnectPump() = runTest {
        whenever(persistenceLayer.cancelCurrentRunningMode(clock.millis(), Action.RECONNECT, Sources.Garmin)).thenReturn(PersistenceLayer.TransactionResult())
        loopHub.connectPump()
        verify(persistenceLayer).cancelCurrentRunningMode(clock.millis(), Action.RECONNECT, Sources.Garmin)
        verify(commandQueue).cancelTempBasal(enforceNew = true, autoForced = false)
    }

    @Test
    fun testDisconnectPump() = runTest {
        val profile = mock<EffectiveProfile>()
        whenever(profileFunction.getProfile()).thenReturn(profile)
        loopHub.disconnectPump(23)
        verify(profileFunction).getProfile()
        verify(loop).handleRunningModeChange(
            durationInMinutes = 23, profile = profile, newRM = RM.Mode.DISCONNECTED_PUMP, action = Action.DISCONNECT,
            source = Sources.Garmin, listValues = listOf(ValueWithUnit.Minute(23))
        )
    }

    @Test
    fun testGetGlucoseValues() = runTest {
        val glucoseValues = listOf(
            GV(
                timestamp = 1_000_000L, raw = 90.0, value = 93.0,
                trendArrow = TrendArrow.FLAT, noise = null,
                sourceSensor = SourceSensor.DEXCOM_G6
            )
        )
        whenever(persistenceLayer.getBgReadingsDataFromTime(1001_000, false))
            .thenReturn(glucoseValues)
        assertArrayEquals(
            glucoseValues.toTypedArray(),
            loopHub.getGlucoseValues(Instant.ofEpochMilli(1001_000), false).toTypedArray()
        )
        verify(persistenceLayer).getBgReadingsDataFromTime(1001_000, false)
    }

    @Test
    fun testPostCarbs() {
        val constraint = mock<Constraint<Int>> {
            on { value() }.thenReturn(99)
        }
        whenever(constraints.getMaxCarbsAllowed()).thenReturn(constraint)
        loopHub.postCarbs(100)
        verify(constraints).getMaxCarbsAllowed()
        runBlocking {
            // Carbs delivery now rides the shared executor (one audited path).
            verify(masterActionExecutor).deliverCarbs(eq(99), isNull(), eq(Sources.Garmin), any(), any())
        }
    }

    @Test
    fun testStoreHeartRate() = runTest {
        val samplingStart = Instant.ofEpochMilli(1_001_000)
        val samplingEnd = Instant.ofEpochMilli(1_101_000)
        val hr = HR(
            timestamp = samplingStart.toEpochMilli(),
            duration = samplingEnd.toEpochMilli() - samplingStart.toEpochMilli(),
            dateCreated = clock.millis(),
            beatsPerMinute = 101.0,
            device = "Test Device"
        )
        whenever(persistenceLayer.insertOrUpdateHeartRates(listOf(hr))).thenReturn(
            PersistenceLayer.TransactionResult()
        )
        loopHub.storeHeartRate(
            samplingStart, samplingEnd, 101, "Test Device"
        )
        kotlinx.coroutines.delay(100.milliseconds) // Give time for GlobalScope.launch to complete
        verify(persistenceLayer).insertOrUpdateHeartRates(listOf(hr))
    }

    @Test
    fun testStoreHeartRates_dropsInvalidSamplesAndStoresOneMinuteRows() = runTest {
        val rows = listOf(
            HR(timestamp = 60_000L, duration = 60_000L, dateCreated = clock.millis(), beatsPerMinute = 72.0, device = "venu3"),
            HR(timestamp = 120_000L, duration = 60_000L, dateCreated = clock.millis(), beatsPerMinute = 75.0, device = "venu3"),
        )
        whenever(persistenceLayer.getHeartRatesFromTimeToTime(60_000L, 120_000L)).thenReturn(emptyList())
        whenever(persistenceLayer.insertOrUpdateHeartRates(rows)).thenReturn(PersistenceLayer.TransactionResult())
        // 10 bpm and a zero timestamp are both rejected; the two valid samples go in one batch.
        loopHub.storeHeartRates(listOf(60_000L to 72, 90_000L to 10, 0L to 80, 120_000L to 75), "venu3")
        verify(persistenceLayer).getHeartRatesFromTimeToTime(60_000L, 120_000L)
        verify(persistenceLayer).insertOrUpdateHeartRates(rows)
    }

    // A batch the watch resends after a reconnect is not stored again.
    @Test
    fun testStoreHeartRates_replayedMinutesAreSkipped() = runTest {
        val stored = HR(timestamp = 60_000L, duration = 60_000L, dateCreated = 1L, beatsPerMinute = 72.0, device = "venu3")
        val newRow = HR(timestamp = 120_000L, duration = 60_000L, dateCreated = clock.millis(), beatsPerMinute = 75.0, device = "venu3")
        whenever(persistenceLayer.getHeartRatesFromTimeToTime(60_000L, 120_000L)).thenReturn(listOf(stored))
        whenever(persistenceLayer.insertOrUpdateHeartRates(listOf(newRow))).thenReturn(PersistenceLayer.TransactionResult())
        loopHub.storeHeartRates(listOf(60_000L to 72, 120_000L to 75, 120_000L to 75), "venu3")
        verify(persistenceLayer).getHeartRatesFromTimeToTime(60_000L, 120_000L)
        verify(persistenceLayer).insertOrUpdateHeartRates(listOf(newRow))
    }

    @Test
    fun testStoreHeartRates_nothingValidStoresNothing() {
        loopHub.storeHeartRates(listOf(60_000L to 5), null)
        // verifyNoMoreInteractions(persistenceLayer) in the AfterEach asserts no write.
    }

    @Test
    fun testStoreSteps_clampsCumulativeCounter() = runTest {
        // The Venu 3 fault: the day's cumulative counter in all six windows.
        val sc = SC(
            duration = 300_000L, timestamp = 1_700_000_000_000L,
            steps5min = 1000, steps10min = 1919, steps15min = 1919,
            steps30min = 1919, steps60min = 1919, steps180min = 1919,
            device = "Garmin", dateCreated = clock.millis()
        )
        whenever(persistenceLayer.insertOrUpdateStepsCounts(listOf(sc))).thenReturn(PersistenceLayer.TransactionResult())
        loopHub.storeSteps(1_700_000_000_000L, 1919, 1919, 1919, 1919, 1919, 1919, null)
        verify(persistenceLayer).insertOrUpdateStepsCounts(listOf(sc))
    }

    @Test
    fun testStoreSteps_noTimestampStoresNothing() {
        loopHub.storeSteps(0L, 100, 200, 300, 400, 500, 600, "venu3")
    }

    @Test
    fun testLoopStatus() = runTest {
        whenever(loop.runningMode()).thenReturn(RM.Mode.CLOSED_LOOP_LGS)
        assertEquals("LGS", loopHub.loopStatus)
        whenever(loop.runningMode()).thenReturn(RM.Mode.SUSPENDED_BY_USER)
        assertEquals("SUSPEND", loopHub.loopStatus)
        whenever(loop.runningMode()).thenReturn(RM.Mode.RESUME)
        assertEquals("LOOP", loopHub.loopStatus)
        verify(loop, times(3)).runningMode()
    }
}
