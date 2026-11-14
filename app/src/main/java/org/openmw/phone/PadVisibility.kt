package org.openmw.phone

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.openmw.isControllerConnected

/**
 * Whether the touch controller is shown.
 *
 * The player can hide it with its own button ([GameScreenPad]), and that choice is kept between
 * runs. A real controller hides it too, for as long as one is connected: the touch one is then
 * only in the way. The hide button shows it again over a real controller, until that is
 * disconnected and connected again.
 */
internal object PadVisibility {
    private const val PREFS = "phone_pad"
    private const val KEY_HIDDEN = "hidden"

    /** Hidden with the hide button. */
    fun hiddenByHand(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HIDDEN, false)

    fun setHiddenByHand(context: Context, hidden: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HIDDEN, hidden).apply()
    }

    /** Whether the controller over the game starts hidden: by hand, or a real controller is connected. */
    fun hiddenAtStart(context: Context): Boolean = hiddenByHand(context) || isControllerConnected(context)
}

/**
 * Whether a real controller is connected, kept up to date as controllers come and go. The touch
 * controller is not an Android input device (it is added to SDL directly), so it does not count.
 */
@Composable
internal fun rememberControllerConnected(): State<Boolean> {
    val context = LocalContext.current
    val connected = remember(context) { mutableStateOf(isControllerConnected(context)) }
    DisposableEffect(context) {
        val input = context.getSystemService(InputManager::class.java)
        val listener = object : InputManager.InputDeviceListener {
            private fun update() {
                connected.value = isControllerConnected(context)
            }

            override fun onInputDeviceAdded(deviceId: Int) = update()
            override fun onInputDeviceRemoved(deviceId: Int) = update()
            override fun onInputDeviceChanged(deviceId: Int) = update()
        }
        input?.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
        // Connected while the listener was not there yet.
        connected.value = isControllerConnected(context)
        onDispose { input?.unregisterInputDeviceListener(listener) }
    }
    return connected
}
