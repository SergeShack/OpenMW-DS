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
 */
@OptIn(InternalCoroutinesApi::class)
object PadGyro {
    private const val TAG = "PadGyro"

    // All of the below is used on [thread] only, apart from [start] and [stop].
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var inputManager: InputManager? = null
    private var deviceId = -1
    private var sensorManager: SensorManager? = null

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            runCatching { EngineActivity.padGyro(event.values[0], event.values[1], event.values[2]) }
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
        val manager = context.getSystemService(InputManager::class.java) ?: return
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
        runCatching { EngineActivity.setPadGyro(true) }
        Log.i(TAG, "Gyroscope of ${device.name}")
        return true
    }

    private fun release() {
        sensorManager?.unregisterListener(sensorListener)
        if (sensorManager != null) runCatching { EngineActivity.setPadGyro(false) }
        sensorManager = null
        deviceId = -1
    }
}
