package org.openmw.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.InputDevice
import androidx.annotation.RequiresApi
import kotlinx.coroutines.InternalCoroutinesApi
import org.openmw.EngineActivity

/**
 * The gyroscope of a controller connected over Bluetooth.
 *
 * The engine reads a controller's gyroscope through SDL, which on Android has it only for controllers
 * on USB. Over Bluetooth Android has it itself, from Android 12, for controllers whose driver reports
 * one, like the DualSense and the DualShock 4. It is read here and passed on, and the engine uses it
 * while SDL has none (pad-gyro-android.patch). The readings are in rad/s, as SDL gives them.
 *
 * The first connected controller with a gyroscope is used, until it is disconnected.
 *
 * Android does not give every controller's axes in the same order: a DualSense turned left reads as
 * tilted up, and tilted up as turned left. [GyroControl.Axes] says whether the first two are swapped,
 * each taking the other's value with its sign reversed; Auto swaps them for Sony's.
 */
@OptIn(InternalCoroutinesApi::class)
object PadGyro {
    private const val TAG = "PadGyro"
    private const val SONY = 0x054c

    // All of the below is used on [thread] only, apart from [start] and [stop].
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var inputManager: InputManager? = null
    private var deviceId = -1
    private var sensorManager: SensorManager? = null
    private var axes = GyroControl.Axes.AUTO
    private var swapAxes = false

    // A controller's gyroscope reads a little turn even when it lies still, and the camera then
    // drifts. While the readings stay nearly the same for a while, the controller is taken to be
    // still and their average to be that offset, which is taken off every reading after it.
    private const val STILL_WINDOW_NS = 1_000_000_000L
    private const val STILL_MIN_READINGS = 20
    // How far apart the readings of a still controller may be, in rad/s.
    private const val STILL_SPREAD = 0.03f
    // The largest offset taken as one, in rad/s. A steady turn faster than this is never taken for one.
    private const val MAX_OFFSET = 0.1f

    private val offset = FloatArray(3)
    private val sum = FloatArray(3)
    private val low = FloatArray(3)
    private val high = FloatArray(3)
    private var readings = 0
    private var windowStart = 0L

    private fun resetWindow(time: Long) {
        windowStart = time
        readings = 0
        sum.fill(0f)
        low.fill(Float.MAX_VALUE)
        high.fill(-Float.MAX_VALUE)
    }

    private fun calibrate(values: FloatArray, time: Long) {
        if (readings == 0 && windowStart == 0L) resetWindow(time)
        for (i in 0..2) {
            sum[i] += values[i]
            low[i] = minOf(low[i], values[i])
            high[i] = maxOf(high[i], values[i])
        }
        readings++
        if (time - windowStart < STILL_WINDOW_NS) return
        val still = readings >= STILL_MIN_READINGS && (0..2).all { high[it] - low[it] < STILL_SPREAD }
        if (still) {
            val mean = FloatArray(3) { sum[it] / readings }
            if (mean.all { kotlin.math.abs(it) < MAX_OFFSET }) {
                if ((0..2).any { kotlin.math.abs(mean[it] - offset[it]) > 0.002f }) {
                    Log.i(TAG, "Offset %.4f %.4f %.4f".format(mean[0], mean[1], mean[2]))
                }
                mean.copyInto(offset)
            }
        }
        resetWindow(time)
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val v = event.values
            calibrate(v, event.timestamp)
            val x = v[0] - offset[0]
            val y = v[1] - offset[1]
            val z = v[2] - offset[2]
            runCatching { if (swapAxes) EngineActivity.padGyro(-y, -x, z) else EngineActivity.padGyro(x, y, z) }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) = pick()

        override fun onInputDeviceRemoved(id: Int) {
            if (id == deviceId) release()
            pick()
        }

        // A controller's sensors may show up a little after the controller itself.
        override fun onInputDeviceChanged(id: Int) = pick()
    }

    /** Start following controllers as the game starts. */
    fun start(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (thread != null) return
        if (GyroControl.source(context) != GyroControl.Source.AUTO) return
        val manager = context.getSystemService(InputManager::class.java) ?: return
        axes = GyroControl.axes(context)
        val t = HandlerThread(TAG).also { it.start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        inputManager = manager
        manager.registerInputDeviceListener(deviceListener, h)
        h.post { pick() }
    }

    /** Stop reading the gyroscope, as the game closes. */
    fun stop() {
        val t = thread ?: return
        inputManager?.unregisterInputDeviceListener(deviceListener)
        handler?.post {
            release()
            t.quitSafely()
        }
        thread = null
        handler = null
        inputManager = null
    }

    private fun pick() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (sensorManager != null) return
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            if (device.isVirtual || !device.isExternal) continue
            if (listen(device)) return
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun listen(device: InputDevice): Boolean {
        val manager = device.sensorManager
        val gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return false
        if (!manager.registerListener(sensorListener, gyro, SensorManager.SENSOR_DELAY_GAME, handler)) {
            Log.w(TAG, "Could not read the gyroscope of ${device.name}")
            return false
        }
        sensorManager = manager
        deviceId = device.id
        swapAxes = when (axes) {
            GyroControl.Axes.AUTO -> device.vendorId == SONY
            GyroControl.Axes.STANDARD -> false
            GyroControl.Axes.SWAPPED -> true
        }
        runCatching { EngineActivity.setPadGyro(true) }
        Log.i(TAG, "Gyroscope of ${device.name}" + if (swapAxes) ", axes swapped" else "")
        return true
    }

    private fun release() {
        sensorManager?.unregisterListener(sensorListener)
        if (sensorManager != null) runCatching { EngineActivity.setPadGyro(false) }
        sensorManager = null
        deviceId = -1
        offset.fill(0f)
        windowStart = 0L
        readings = 0
    }
}
