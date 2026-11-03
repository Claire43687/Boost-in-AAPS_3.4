package app.aaps.plugins.aps.openAPSBoost

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Registers [StepService] for the phone's step counter.
 *
 * Boost 6.1.2 did this in `MainApp.onCreate`. On AAPS 4.0 the Boost plugin does it from its own
 * `onStart`, so the registration stays with the code that reads the steps and needs nothing in the
 * app module. The checks and log lines are those of 6.1.2. Registration happens once per process;
 * when the ACTIVITY_RECOGNITION permission is missing it is retried on the next start, as 6.1.2
 * retried it on the next app start.
 */
object BoostStepSensor {

    private val registered = AtomicBoolean(false)

    fun register(context: Context, aapsLogger: AAPSLogger) {
        if (registered.get()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
            ) {
                aapsLogger.warn(LTag.APS, "ACTIVITY_RECOGNITION permission not granted — step counter for Boost will not work until permission is granted and Boost is restarted")
                return
            }
            val sensorManager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            if (stepSensor == null) {
                aapsLogger.warn(LTag.APS, "Step counter sensor not available on this device")
                return
            }
            if (registered.compareAndSet(false, true)) {
                sensorManager.registerListener(StepService, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
                aapsLogger.debug(LTag.APS, "Step counter sensor registered for Boost plugin")
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "Failed to register step counter sensor", e)
        }
    }
}
