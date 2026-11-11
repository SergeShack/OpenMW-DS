package org.openmw.phone

import android.app.Activity
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import kotlin.math.roundToInt

/**
 * The touch controller over the game itself, for the Phone + external screen profile with no
 * external screen connected. The phone is then the only screen, so the same controller the
 * companion has beside it sits at the two edges of the game instead, narrower and over the
 * picture. The old touch layer is not shown on this profile (see [PhoneExternal]).
 */
object GameScreenPad {

    /** Width of each side, in dp. */
    private const val SIDE_WIDTH_DP = 150

    fun install(activity: Activity, container: FrameLayout) {
        val width = (SIDE_WIDTH_DP * activity.resources.displayMetrics.density).roundToInt()
        container.addView(
            ComposeView(activity).apply { setContent { SidePadLeft(overGame = true) } },
            FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START)
        )
        container.addView(
            ComposeView(activity).apply { setContent { SidePadRight(overGame = true) } },
            FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END)
        )
    }
}
