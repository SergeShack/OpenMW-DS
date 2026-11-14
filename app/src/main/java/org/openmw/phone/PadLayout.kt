package org.openmw.phone

import android.content.Context

/** The parts of the touch controller over the game, each moved, resized and hidden on its own. */
internal enum class PadPart(val key: String) {
    LT("lt"),
    LB("lb"),
    BACK("back"),
    DPAD("dpad"),
    LEFT_STICK("left_stick"),
    START("start"),
    RB("rb"),
    RT("rt"),
    FACE("face"),
    ATTACK("attack"),
    LOOK("look"),

    /** The small edit and hide buttons. Never hidden, or the controller could not be brought back. */
    TOOLS("tools"),
}

/** Where a part is on the screen: its top left corner and its size, in dp. */
internal data class PartPlace(val x: Float, val y: Float, val w: Float, val h: Float, val hidden: Boolean = false) {
    /** Kept whole and inside a screen of [width] x [height] dp. */
    fun inside(width: Float, height: Float): PartPlace {
        val nw = w.coerceIn(PadLayout.MIN_SIZE_DP, maxOf(PadLayout.MIN_SIZE_DP, width))
        val nh = h.coerceIn(PadLayout.MIN_SIZE_DP, maxOf(PadLayout.MIN_SIZE_DP, height))
        return copy(
            x = x.coerceIn(0f, maxOf(0f, width - nw)),
            y = y.coerceIn(0f, maxOf(0f, height - nh)),
            w = nw,
            h = nh,
        )
    }
}

/**
 * The layout of the touch controller over the game ([GameScreenPad]), as the player arranged it in
 * the editor ([PadEditor]), and how see-through it is.
 *
 * A part the player never moved is where the two side columns put it. A moved one is kept as the
 * centre's share of the screen and the size in dp, so the same layout fits a phone with another
 * screen size: the parts keep their size and stay at the same place relative to the edges.
 */
internal object PadLayout {
    private const val PREFS = "phone_pad_layout"
    private const val KEY_OPACITY = "opacity"

    /** The smallest a part can be made, in dp. */
    const val MIN_SIZE_DP = 32f

    const val OPACITY_DEFAULT = 1f
    const val OPACITY_MIN = 0.2f

    // The side columns the defaults follow: as wide as before the editor, with the same padding,
    // rows and proportions.
    private const val SIDE = 150f
    private const val PAD = 8f
    private const val GAP = 8f
    private const val ROW = 44f
    private const val ROW_GAP = 6f
    private const val ATTACK = 60f
    private const val TOOL = 34f

    /** Room for the two tool buttons side by side: two buttons and the gap between them. */
    fun toolsWidth(h: Float) = h * 2 + ROW_GAP

    /** Where every part goes on a screen of [width] x [height] dp before the player moves it. */
    fun defaults(width: Float, height: Float): Map<PadPart, PartPlace> {
        val top = PAD + OVER_GAME_TOP.value
        val inner = SIDE - 2 * PAD
        val button = (inner - 2 * ROW_GAP) / 3
        val below = top + ROW + GAP

        // Left: a row of three buttons, then the d-pad and the stick share the rest as 1 to 1.3.
        val leftRest = (height - PAD) - below - GAP
        val dpad = leftRest / 2.3f
        val stick = leftRest - dpad
        val left = PAD

        // Right: a row of three buttons, the face buttons, the attack button and the look pad,
        // the face buttons and the look pad sharing the rest as 1 to 1.1.
        val rightRest = (height - PAD) - below - ATTACK - 2 * GAP
        val face = rightRest / 2.1f
        val look = rightRest - face
        val right = width - SIDE + PAD

        val places = mapOf(
            PadPart.LT to PartPlace(left, top, button, ROW),
            PadPart.LB to PartPlace(left + button + ROW_GAP, top, button, ROW),
            PadPart.BACK to PartPlace(left + 2 * (button + ROW_GAP), top, button, ROW),
            PadPart.DPAD to PartPlace(left, below, inner, dpad),
            PadPart.LEFT_STICK to PartPlace(left, below + dpad + GAP, inner, stick),
            PadPart.START to PartPlace(right, top, button, ROW),
            PadPart.RB to PartPlace(right + button + ROW_GAP, top, button, ROW),
            PadPart.RT to PartPlace(right + 2 * (button + ROW_GAP), top, button, ROW),
            PadPart.FACE to PartPlace(right, below, inner, face),
            PadPart.ATTACK to PartPlace(right, below + face + GAP, inner, ATTACK),
            PadPart.LOOK to PartPlace(right, below + face + GAP + ATTACK + GAP, inner, look),
            // The top right corner: the keyboard and quick slot buttons are in the top left one.
            PadPart.TOOLS to PartPlace(width - PAD - toolsWidth(TOOL), (top - TOOL) / 2, toolsWidth(TOOL), TOOL),
        )
        return places.mapValues { it.value.inside(width, height) }
    }

    /** The player's layout on a screen of [width] x [height] dp. */
    fun load(context: Context, width: Float, height: Float): Map<PadPart, PartPlace> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return defaults(width, height).mapValues { (part, place) ->
            val k = part.key
            if (!prefs.contains("$k.cx")) return@mapValues place.copy(hidden = prefs.getBoolean("$k.hidden", false) && part != PadPart.TOOLS)
            val w = prefs.getFloat("$k.w", place.w)
            val h = prefs.getFloat("$k.h", place.h)
            PartPlace(
                x = prefs.getFloat("$k.cx", 0.5f) * width - w / 2,
                y = prefs.getFloat("$k.cy", 0.5f) * height - h / 2,
                w = w,
                h = h,
                hidden = prefs.getBoolean("$k.hidden", false) && part != PadPart.TOOLS,
            ).inside(width, height)
        }
    }

    /**
     * Keeps [places] on a screen of [width] x [height] dp. A part still where the defaults put it
     * is stored as not moved, so it follows the defaults on another screen.
     */
    fun save(context: Context, places: Map<PadPart, PartPlace>, width: Float, height: Float) {
        val defaults = defaults(width, height)
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        for ((part, place) in places) {
            val k = part.key
            edit.putBoolean("$k.hidden", place.hidden)
            if (place.copy(hidden = false) == defaults[part]) {
                edit.remove("$k.cx").remove("$k.cy").remove("$k.w").remove("$k.h")
                continue
            }
            edit.putFloat("$k.cx", (place.x + place.w / 2) / width)
                .putFloat("$k.cy", (place.y + place.h / 2) / height)
                .putFloat("$k.w", place.w)
                .putFloat("$k.h", place.h)
        }
        edit.apply()
    }

    fun opacity(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_OPACITY, OPACITY_DEFAULT)
            .coerceIn(OPACITY_MIN, 1f)

    fun saveOpacity(context: Context, opacity: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_OPACITY, opacity).apply()
    }
}
