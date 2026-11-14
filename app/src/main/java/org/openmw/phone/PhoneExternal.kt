package org.openmw.phone

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import org.openmw.utils.DisplayRoles

/**
 * The Phone + external screen profile: a phone with a TV or monitor, on a cable or a wireless
 * cast. The game goes on the external screen and the companion on the phone, which is the only one
 * of the two that can be touched, with a touch controller beside it ([SidePadLeft], [SidePadRight]).
 *
 * Everything the profile does lives in this package. The shared code only asks it questions at a
 * few points (display roles, the companion host, the game activity, the launcher), so the rest of
 * the app stays as it is on the other profiles.
 *
 * With no external screen connected it behaves as Single Screen, so the phone alone is playable.
 */
object PhoneExternal {
    const val PROFILE = "phone_external"

    fun isActive(context: Context): Boolean = DisplayRoles.profile(context) == PROFILE

    /**
     * The external screen: a presentation display if there is one (cables and casts both report
     * as one), else any other public display. Null when nothing is connected.
     */
    fun externalDisplay(context: Context): Display? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
        val others = dm.displays.filter {
            it.displayId != Display.DEFAULT_DISPLAY && it.flags and Display.FLAG_PRIVATE == 0
        }
        return others.firstOrNull { it.flags and Display.FLAG_PRESENTATION != 0 } ?: others.firstOrNull()
    }

    /** True when the profile is on but nothing is connected: the phone alone, as Single Screen. */
    fun phoneAlone(context: Context): Boolean = isActive(context) && externalDisplay(context) == null

    /** The game's display: the external screen, or the phone when none is connected. */
    fun gameDisplayId(context: Context): Int = externalDisplay(context)?.displayId ?: Display.DEFAULT_DISPLAY

    /**
     * The display the game should stay on for this run, or null when the profile is off.
     *
     * Taken once when the game starts. A screen connected while the game runs on the phone alone
     * must not pull it over, since the game was set up without a companion.
     */
    fun pinnedGameDisplay(context: Context): Int? = if (isActive(context)) gameDisplayId(context) else null

    /**
     * Whether the game screen gets the old touch layer. Never on this profile ([pinnedGameDisplay]
     * set): an external screen cannot be touched, and on the phone alone the side controller
     * replaces it ([wantsPadOverGame]).
     */
    fun legacyTouchControls(pinnedGameDisplay: Int?): Boolean = pinnedGameDisplay == null

    /**
     * Seconds the game's controller button hints (the bar along the bottom of a menu) stay up after
     * they last changed. On a phone they cover the menus, and the controller beside them already
     * names its buttons. They come back with the next window or menu.
     */
    private const val CONTROLLER_HINTS_SECONDS = 4f

    /** Engine side: phone-controller-hints-autohide.patch. 0 keeps the hints up. */
    @JvmStatic
    private external fun setControllerHintsAutoHide(seconds: Float)

    /** Called by the game activity at start. */
    fun onGameStart(context: Context) {
        if (!isActive(context)) return
        runCatching { setControllerHintsAutoHide(CONTROLLER_HINTS_SECONDS) }
            .onFailure { android.util.Log.e("PhoneExternal", "controller hints auto-hide not set", it) }
    }

    /** Whether the side controller goes over the game: this profile, with the game on the phone. */
    fun wantsPadOverGame(pinnedGameDisplay: Int?): Boolean = pinnedGameDisplay == Display.DEFAULT_DISPLAY

    /**
     * Called by the game activity before the game starts, which is when the engine reads its
     * settings. The phone build's own defaults are written on every profile ([PhoneDefaults]).
     * On this profile the game's windows go between the controller's sides when the controller is
     * over the game, and back to the whole width on an external screen or when the controller
     * starts hidden ([PhoneWindows], [PadVisibility]). The engine reads them only now, so a
     * controller shown or hidden later leaves them where they are until the next start.
     */
    fun beforeGameStart(context: Context, pinnedGameDisplay: Int?) {
        runCatching { PhoneDefaults.apply(context) }
            .onFailure { android.util.Log.e("PhoneExternal", "phone defaults not applied", it) }
        if (pinnedGameDisplay == null) return
        val padShown = wantsPadOverGame(pinnedGameDisplay) && !PadVisibility.hiddenAtStart(context)
        val (left, right) = if (padShown) GameScreenPad.menuSideFractions(context) else 0f to 0f
        runCatching { PhoneWindows.fit(context, left, right) }
            .onFailure { android.util.Log.e("PhoneExternal", "windows not fitted", it) }
    }
}
