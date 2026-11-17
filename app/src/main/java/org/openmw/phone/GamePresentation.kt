package org.openmw.phone

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import org.libsdl.app.SDLActivity
import org.libsdl.app.SDLControllerManager
import org.openmw.CompanionActivity
import org.openmw.Constants
import org.openmw.companion.CompanionScreen
import org.openmw.companion.UiPreferences
import java.io.File

/**
 * The game drawn on the external screen from a Presentation, with the game activity on the phone.
 *
 * Android does not let an app start an activity on an external screen it does not trust, like a
 * wireless cast on some phones: the display has no FLAG_TRUSTED, and the launch is refused. A
 * Presentation may be shown on any presentation display, so on such a screen the game activity
 * stays on the phone and shows the companion and the touch controller in its own window, and the
 * game's views (the game surface and what is drawn over it) go into a Presentation on the
 * external screen. The activity is the phone's focused window, so a real controller's keys reach it
 * and are handed to the game ([forwardKey]).
 *
 * Chosen once when the game starts ([chooseAtStart]). On a trusted screen the game activity goes
 * there as before. A file named [FORCE_FILE] in the app's storage folder makes it use the
 * Presentation on any external screen, for testing.
 *
 * When the external screen goes away the Presentation is closed by Android. The game's views then
 * come back into the activity and the game goes on as on the phone alone
 * ([PhoneExternal.moveGameToPhone]).
 */
object GamePresentation {
    private const val TAG = "GamePresentation"

    /** Display.FLAG_TRUSTED, which the SDK hides. */
    private const val FLAG_TRUSTED = 0x80

    private const val FORCE_FILE = "game-presentation"

    /** The external screen the game is drawn on for this run, or null when it is not used. */
    @Volatile
    private var displayId: Int? = null

    private var presentation: Presentation? = null

    /** The game's views, moved between the Presentation and the activity. */
    private var gameContent: View? = null

    private var dismissedByUs = false

    /** Called when the companion goes away from the activity, for windows it keeps of its own. */
    private var onCompanionGone: (() -> Unit)? = null

    /** True while the game is drawn on the external screen and the companion is in the activity. */
    val active: Boolean get() = presentation != null

    /** Whether the game would be drawn from a Presentation on [display]. */
    private fun wantedOn(display: Display): Boolean =
        display.flags and FLAG_TRUSTED == 0 || forced()

    private fun forced(): Boolean =
        runCatching { File(Constants.USER_FILE_STORAGE, FORCE_FILE).exists() }.getOrDefault(false)

    /** Whether the launcher should start the game activity on the phone, for a Presentation. */
    fun wanted(context: Context): Boolean {
        val display = PhoneExternal.externalDisplay(context) ?: return false
        return wantedOn(display)
    }

    /** Decides for this run, as the game activity is created. True when the Presentation is used. */
    fun chooseAtStart(context: Context): Boolean {
        val display = PhoneExternal.externalDisplay(context)
        displayId = display?.takeIf { wantedOn(it) }?.displayId
        Log.w(
            TAG, "external display ${display?.displayId} flags 0x${Integer.toHexString(display?.flags ?: 0)}, " +
                "forced ${forced()}: game ${if (displayId != null) "in a presentation" else "activity there"}"
        )
        return displayId != null
    }

    /** Whether this run draws the game from a Presentation, until the external screen goes away. */
    val chosen: Boolean get() = displayId != null

    /**
     * Sets the game activity's content for this run. Returns false when the Presentation is not
     * used, or could not be shown, and the activity is to take [layout] itself.
     *
     * The game's views are inflated into the Presentation, with the external screen's density, and
     * the activity shows the companion framed with the touch controller beside it, as the companion
     * activity does when the game has a screen of its own.
     */
    fun setContent(activity: ComponentActivity, layout: Int, companionGone: () -> Unit): Boolean {
        val id = displayId ?: return false
        val display = activity.getSystemService(DisplayManager::class.java)?.getDisplay(id)
        val content = display?.let { show(activity, it, null) { context -> LayoutInflater.from(context).inflate(layout, null) } }
        if (content == null) {
            Log.e(TAG, "presentation not shown on display $id, the game stays on the phone")
            displayId = null
            PhoneExternal.gameMovedToPhone.value = true
            UiPreferences.useGameWindowsUntilRestart()
            return false
        }
        gameContent = content
        onCompanionGone = companionGone
        FramedCompanion.install(activity, CompanionActivity.visible) { CompanionScreen() }
        return true
    }

    /**
     * Shows a Presentation on [display] with the game's views: [existing] moved over, or new ones
     * from [inflate]. Returns the views, or null when it could not be shown.
     */
    private fun show(
        activity: ComponentActivity,
        display: Display,
        existing: View?,
        inflate: ((Context) -> View)?,
    ): View? {
        val p = Presentation(activity, display)
        p.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(activity)
            decor.setViewTreeViewModelStoreOwner(activity)
            decor.setViewTreeSavedStateRegistryOwner(activity)
            decor.setViewTreeOnBackPressedDispatcherOwner(activity)
        }
        p.window?.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        val view = existing ?: inflate?.invoke(p.context) ?: return null
        (view.parent as? ViewGroup)?.removeView(view)
        p.setContentView(view)
        p.setOnDismissListener { onDismissed(activity, p) }
        return runCatching {
            p.show()
            presentation = p
            Log.w(TAG, "game shown on display ${display.displayId}")
            view
        }.onFailure {
            Log.e(TAG, "presentation show failed on display ${display.displayId}", it)
            (view.parent as? ViewGroup)?.removeView(view)
        }.getOrNull()
    }

    /**
     * Android closes a Presentation when its screen goes away, or changes its size. On a screen
     * that is still there the game is shown again in a new one; otherwise it comes over to the
     * phone.
     */
    private fun onDismissed(activity: ComponentActivity, dismissed: Presentation) {
        if (presentation !== dismissed) return
        presentation = null
        if (dismissedByUs || activity.isFinishing || activity.isDestroyed) return
        val content = gameContent ?: return
        val id = displayId
        val display = id?.let { activity.getSystemService(DisplayManager::class.java)?.getDisplay(it) }
        if (display != null) {
            Log.w(TAG, "presentation closed while display $id is still there, showing it again")
            if (show(activity, display, content, null) != null) return
        }
        Log.w(TAG, "external display $id gone, the game comes to the phone")
        displayId = null
        (content.parent as? ViewGroup)?.removeView(content)
        onCompanionGone?.invoke()
        onCompanionGone = null
        FramedCompanion.onCompanionDestroyed()
        activity.setContentView(content)
        PhoneExternal.moveGameToPhone(activity)
    }

    /** One of the game's views, while they are in the Presentation. */
    fun <T : View> findView(id: Int): T? = presentation?.findViewById(id)

    /** The window the game is drawn in: the Presentation's while it is shown. */
    fun gameWindow(): Window? = presentation?.window

    /** The game activity went to the background: so does the game on the external screen. */
    fun onStop() {
        runCatching { presentation?.hide() }
    }

    fun onStart() {
        runCatching { presentation?.show() }
    }

    /** The game activity is going away. */
    fun release() {
        dismissedByUs = true
        runCatching { presentation?.dismiss() }
        presentation = null
        gameContent = null
        onCompanionGone = null
    }

    /**
     * A controller key that reached the game activity's window on the phone. While the game is in
     * the Presentation its surface is not in a focused window, so the keys are handed to SDL here,
     * the way the surface would. Only controller devices, like the companion activity does: the
     * phone's back gesture and volume keys are left to the system.
     */
    fun forwardKey(event: KeyEvent): Boolean {
        if (presentation == null || SDLActivity.mBrokenLibraries) return false
        return runCatching {
            SDLControllerManager.isDeviceSDLJoystick(event.deviceId) &&
                SDLActivity.handleKeyEvent(null, event.keyCode, event, null)
        }.getOrDefault(false)
    }

    /** The sticks and triggers half of [forwardKey]. */
    fun forwardMotion(event: MotionEvent): Boolean {
        if (presentation == null || SDLActivity.mBrokenLibraries) return false
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        return runCatching { SDLControllerManager.handleJoystickMotionEvent(event) }.getOrDefault(false)
    }

    /** For the bug report. */
    fun describe(context: Context): String =
        "wanted now: ${wanted(context)}, chosen at start: ${displayId != null}, " +
            "shown: ${presentation != null}, forced: ${forced()}"
}
