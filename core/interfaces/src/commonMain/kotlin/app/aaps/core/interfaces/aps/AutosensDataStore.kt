package app.aaps.core.interfaces.aps

import androidx.collection.LongSparseArray
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.GV
import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.utils.DateUtil

interface AutosensDataStore {

    /** Guards [bgReadings], [autosensDataTable], [bucketedData] and [bucketedDataNative]. Hold it across compound work. */
    val dataLock: AapsLock

    var bgReadings: List<GV>
    var autosensDataTable: LongSparseArray<AutosensData>
    var bucketedData: MutableList<InMemoryGlucoseValue>?

    /**
     * The same glucose at the sensor's OWN cadence, rather than resampled to five minutes.
     *
     * [bucketedData] is built by stepping back in fixed five-minute increments and interpolating,
     * so on a one-minute sensor four of every five readings are replaced by an interpolation of
     * their neighbours. That is deliberate for the sensitivity chain, which defines deviations
     * and carbohydrate impact per five-minute bucket and would be silently rescaled by a change
     * of interval. Consumers that work in elapsed time rather than sample counts, the smoother
     * and the delta calculation, can use this instead and see every reading.
     *
     * The same list as [bucketedData] on any feed slower than two minutes, which is every feed
     * that is not a one-minute sensor. Null until [createBucketedData] runs.
     */
    var bucketedDataNative: MutableList<InMemoryGlucoseValue>?

    /** Median spacing of the raw readings in minutes, or null when it cannot be established. */
    var detectedCadenceMinutes: Double?

    var lastUsed5minCalculation: Boolean?

    /**
     * Return last valid (>39) InMemoryGlucoseValue from bucketed data or null if db is empty
     *
     * @return InMemoryGlucoseValue or null
     */
    fun lastBg(): InMemoryGlucoseValue?

    /**
     * Provide last bucketed InMemoryGlucoseValue or null if none exists within the last 9 minutes
     *
     * @return InMemoryGlucoseValue or null
     */
    fun actualBg(): InMemoryGlucoseValue?

    /**
     * Last NATIVE-cadence InMemoryGlucoseValue within the last 9 minutes, or null.
     *
     * [actualBg] reads the five-minute bucketed series, so on a faster sensor its timestamp only
     * advances every five minutes. This accessor exists so the loop can be driven at the sensor's
     * own cadence instead, and is used only where that is explicitly enabled
     * (`ApsLoopAtNativeCadence`).
     *
     * On a five-minute feed the native series IS the bucketed series, so this returns exactly what
     * [actualBg] does.
     */
    fun actualBgNative(): InMemoryGlucoseValue?
    fun lastDataTime(dateUtil: DateUtil): String
    fun clone(): AutosensDataStore
    fun getBgReadingsDataTableCopy(): List<GV>
    fun getLastAutosensData(reason: String, aapsLogger: AAPSLogger, dateUtil: DateUtil): AutosensData?
    fun getAutosensDataAtTime(fromTime: Long): AutosensData?
    fun getBucketedDataTableCopy(): MutableList<InMemoryGlucoseValue>?

    /**
     * Copy of [bucketedDataNative], which falls back to [bucketedData] when no distinct native
     * series exists. For consumers that work in elapsed time and want every reading the sensor
     * produced.
     */
    fun getBucketedDataNativeTableCopy(): MutableList<InMemoryGlucoseValue>?
    fun createBucketedData(aapsLogger: AAPSLogger, dateUtil: DateUtil)
    fun slowAbsorptionPercentage(timeInMinutes: Int): Double
    /**
     * True when this store already holds [gv] with the same data, so a change carrying it cannot alter
     * what a recalculation is built from.
     *
     * A database change tells us a row was written, not that anything the calculation reads is
     * different. The Nightscout id write-back is the common case: `updateExistingEntry` puts the row
     * into the same change list an insert uses, so a reading that only got its `nightscoutId` filled in
     * schedules a full stop, invalidate and restart of the calculation (issue #5101).
     *
     * Answers false unless the last load already saw a reading with this id and exactly this content.
     * Not held, held but different, store not loaded yet - all answer false. The answer is worded this
     * way round on purpose: false means "recalculate", which is both the safe direction and what a
     * mocked store returns by default.
     */
    fun holdsSameData(gv: GV): Boolean

    fun newHistoryData(time: Long, aapsLogger: AAPSLogger, dateUtil: DateUtil)

    /**
     * Drops autosens entries older than [time], the counterpart of [newHistoryData] at the old end.
     *
     * Without it the table only ever grows, because nothing else removes an entry that has aged out of
     * the window the calculation works on: about 288 entries a day, each holding an autosens result,
     * kept for the whole life of the process (issue #5101).
     *
     * This is a memory bound, not a speed fix. Measured, the aged out head costs nothing worth naming:
     * the sensitivity plugins only compare its timestamps and move on. What costs time is how many
     * entries fall INSIDE the window, and that is bounded by the window itself once the bucket grid
     * stops moving.
     *
     * [time] must be at or older than the oldest data any reader can still ask for. The caller that
     * knows that is the one loading BG data, because the same window bounds the bucketed data.
     */
    fun pruneOlderThan(time: Long, aapsLogger: AAPSLogger, dateUtil: DateUtil)
    fun roundUpTime(time: Long): Long
    fun reset()
}