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

    /** Whether the side controller goes over the game: this profile, with the game on the phone. */
    fun wantsPadOverGame(pinnedGameDisplay: Int?): Boolean = pinnedGameDisplay == Display.DEFAULT_DISPLAY
}
