package org.openmw.phone

import android.content.Context

/** The parts of the touch controller, each moved, resized and hidden on its own. */
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

/**
 * Where the touch controller and the key buttons are shown. Each has a layout of its own, since the
 * room is different: over the game the whole screen, beside the companion only the two sides.
 */
internal enum class PadSurface(val key: String, val hasParts: Boolean) {
    /** Over the game, on the phone alone ([GameScreenPad]). */
    OVER_GAME("over_game", true),

    /** Beside the companion while a real controller is connected: key buttons only ([CompanionSidePad]). */
    BESIDE_COMPANION("beside_companion", false),

    /** Beside the companion with the touch controller ([CompanionSidePad]). */
    BESIDE_COMPANION_TOUCH("beside_companion_touch", true),
}

/** Where a part is on the screen: its top left corner and its size, in dp. */
internal data class PartPlace(val x: Float, val y: Float, val w: Float, val h: Float, val hidden: Boolean = false) {
    val centerX get() = x + w / 2
    val centerY get() = y + h / 2

    /** Kept whole and inside [area]. */
    fun inside(area: PadArea): PartPlace {
        val nw = w.coerceIn(PadLayout.MIN_SIZE_DP, maxOf(PadLayout.MIN_SIZE_DP, area.w))
        val nh = h.coerceIn(PadLayout.MIN_SIZE_DP, maxOf(PadLayout.MIN_SIZE_DP, area.h))
        return copy(
            x = x.coerceIn(area.x, maxOf(area.x, area.right - nw)),
            y = y.coerceIn(area.y, maxOf(area.y, area.bottom - nh)),
            w = nw,
            h = nh,
        )
    }

    /** Kept whole and inside the one of [areas] its centre is in, or the nearest one. */
    fun within(areas: List<PadArea>): PartPlace = inside(PadArea.nearest(areas, centerX, centerY))
}

/**
 * A part of the screen the controller may be placed in, in dp: the whole screen over the game, or
 * one of the two sides beside the companion.
 */
internal data class PadArea(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right get() = x + w
    val bottom get() = y + h

    /** How far the point ([px], [py]) is from this area; 0 inside it. */
    fun distanceTo(px: Float, py: Float): Float {
        val dx = maxOf(x - px, 0f, px - right)
        val dy = maxOf(y - py, 0f, py - bottom)
        return dx * dx + dy * dy
    }

    companion object {
        /** The one of [areas] the point is in, or the nearest one. [areas] must not be empty. */
        fun nearest(areas: List<PadArea>, px: Float, py: Float): PadArea =
            areas.minBy { it.distanceTo(px, py) }
    }
}

/**
 * The layout of the touch controller's parts on a [PadSurface] that has them, as the player
 * arranged it in the editor ([PadEditor]), and how see-through it is over the game.
 *
 * A part the player never moved is where the two columns put it ([defaults]). A moved one is kept as
 * the area it is in, its centre as a share of that area and its size in dp, so the same layout fits
 * a phone with another screen size: the parts keep their size and stay at the same place relative
 * to the edges.
 */
internal object PadLayout {
    // Over the game the layout keeps the name and the keys it had before there were other surfaces.
    private const val PREFS_OVER_GAME = "phone_pad_layout"
    private const val PREFS_BESIDE_COMPANION = "phone_pad_layout_beside_companion"
    private const val KEY_OPACITY = "opacity"

    /** The smallest a part can be made, in dp. */
    const val MIN_SIZE_DP = 32f

    const val OPACITY_DEFAULT = 1f
    const val OPACITY_MIN = 0.2f

    // The columns the defaults follow: over the game as wide as before the editor, with the same
    // padding, rows and proportions; beside the companion as wide as the sides.
    private const val SIDE = 150f
    private const val PAD = 8f
    private const val GAP = 8f
    private const val ROW = 44f
    private const val ROW_GAP = 6f
    private const val ATTACK = 60f
    private const val TOOL = 34f

    /** Room for the two tool buttons side by side: two buttons and the gap between them. */
    fun toolsWidth(h: Float) = h * 2 + ROW_GAP

    private fun prefsName(surface: PadSurface) =
        if (surface == PadSurface.OVER_GAME) PREFS_OVER_GAME else PREFS_BESIDE_COMPANION

    /**
     * Where every part goes before the player moves it. Over the game [areas] is the whole screen;
     * beside the companion it is the left side and the right side.
     */
    fun defaults(surface: PadSurface, areas: List<PadArea>): Map<PadPart, PartPlace> {
        val places = if (surface == PadSurface.OVER_GAME) {
            val screen = areas.first()
            val top = PAD + OVER_GAME_TOP.value
            columns(
                left = PadArea(screen.x, screen.y, SIDE, screen.h),
                right = PadArea(screen.right - SIDE, screen.y, SIDE, screen.h),
                top = top,
                // The top right corner: the keyboard and quick slot buttons are in the top left one.
                tools = PartPlace(screen.right - PAD - toolsWidth(TOOL), screen.y + (top - TOOL) / 2, toolsWidth(TOOL), TOOL),
            )
        } else {
            // A row for the tool buttons at the top of the right side, and both columns below it.
            val right = areas.last()
            columns(
                left = areas.first(),
                right = right,
                top = PAD + TOOL + GAP,
                tools = PartPlace(right.right - PAD - toolsWidth(TOOL), right.y + PAD, toolsWidth(TOOL), TOOL),
            )
        }
        return places.mapValues { it.value.within(areas) }
    }

    /** The two columns of the controller in [left] and [right], from [top] down. */
    private fun columns(left: PadArea, right: PadArea, top: Float, tools: PartPlace): Map<PadPart, PartPlace> {
        val below = top + ROW + GAP

        // Left: a row of three buttons, then the d-pad and the stick share the rest as 1 to 1.3.
        val l = left.x + PAD
        val leftInner = left.w - 2 * PAD
        val leftButton = (leftInner - 2 * ROW_GAP) / 3
        val leftRest = (left.bottom - PAD) - (left.y + below) - GAP
        val dpad = leftRest / 2.3f
        val stick = leftRest - dpad

        // Right: a row of three buttons, the face buttons, the attack button and the look pad,
        // the face buttons and the look pad sharing the rest as 1 to 1.1.
        val r = right.x + PAD
        val rightInner = right.w - 2 * PAD
        val rightButton = (rightInner - 2 * ROW_GAP) / 3
        val rightRest = (right.bottom - PAD) - (right.y + below) - ATTACK - 2 * GAP
        val face = rightRest / 2.1f
        val look = rightRest - face

        val lt = left.y + top
        val lb = left.y + below
        val rt = right.y + top
        val rb = right.y + below
        return mapOf(
            PadPart.LT to PartPlace(l, lt, leftButton, ROW),
            PadPart.LB to PartPlace(l + leftButton + ROW_GAP, lt, leftButton, ROW),
            PadPart.BACK to PartPlace(l + 2 * (leftButton + ROW_GAP), lt, leftButton, ROW),
            PadPart.DPAD to PartPlace(l, lb, leftInner, dpad),
            PadPart.LEFT_STICK to PartPlace(l, lb + dpad + GAP, leftInner, stick),
            PadPart.START to PartPlace(r, rt, rightButton, ROW),
            PadPart.RB to PartPlace(r + rightButton + ROW_GAP, rt, rightButton, ROW),
            PadPart.RT to PartPlace(r + 2 * (rightButton + ROW_GAP), rt, rightButton, ROW),
            PadPart.FACE to PartPlace(r, rb, rightInner, face),
            PadPart.ATTACK to PartPlace(r, rb + face + GAP, rightInner, ATTACK),
            PadPart.LOOK to PartPlace(r, rb + face + GAP + ATTACK + GAP, rightInner, look),
            PadPart.TOOLS to tools,
        )
    }

    /** The player's layout of [surface], in [areas] (see [defaults]). */
    fun load(context: Context, surface: PadSurface, areas: List<PadArea>): Map<PadPart, PartPlace> {
        val prefs = context.getSharedPreferences(prefsName(surface), Context.MODE_PRIVATE)
        return defaults(surface, areas).mapValues { (part, place) ->
            val k = part.key
            val hidden = prefs.getBoolean("$k.hidden", false) && part != PadPart.TOOLS
            if (!prefs.contains("$k.cx")) return@mapValues place.copy(hidden = hidden)
            val area = areas[prefs.getInt("$k.area", 0).coerceIn(0, areas.size - 1)]
            val w = prefs.getFloat("$k.w", place.w)
            val h = prefs.getFloat("$k.h", place.h)
            PartPlace(
                x = area.x + prefs.getFloat("$k.cx", 0.5f) * area.w - w / 2,
                y = area.y + prefs.getFloat("$k.cy", 0.5f) * area.h - h / 2,
                w = w,
                h = h,
                hidden = hidden,
            ).inside(area)
        }
    }

    /**
     * Keeps [places] of [surface], in [areas]. A part still where the defaults put it is stored as
     * not moved, so it follows the defaults on another screen.
     */
    fun save(context: Context, surface: PadSurface, places: Map<PadPart, PartPlace>, areas: List<PadArea>) {
        val defaults = defaults(surface, areas)
        val edit = context.getSharedPreferences(prefsName(surface), Context.MODE_PRIVATE).edit()
        for ((part, place) in places) {
            val k = part.key
            edit.putBoolean("$k.hidden", place.hidden)
            if (place.copy(hidden = false) == defaults[part]) {
                edit.remove("$k.area").remove("$k.cx").remove("$k.cy").remove("$k.w").remove("$k.h")
                continue
            }
            val area = PadArea.nearest(areas, place.centerX, place.centerY)
            edit.putInt("$k.area", areas.indexOf(area))
                .putFloat("$k.cx", (place.centerX - area.x) / area.w)
                .putFloat("$k.cy", (place.centerY - area.y) / area.h)
                .putFloat("$k.w", place.w)
                .putFloat("$k.h", place.h)
        }
        edit.apply()
    }

    /** How see-through the controller over the game is. Beside the companion it is always solid. */
    fun opacity(context: Context): Float =
        context.getSharedPreferences(PREFS_OVER_GAME, Context.MODE_PRIVATE).getFloat(KEY_OPACITY, OPACITY_DEFAULT)
            .coerceIn(OPACITY_MIN, 1f)

    fun saveOpacity(context: Context, opacity: Float) {
        context.getSharedPreferences(PREFS_OVER_GAME, Context.MODE_PRIVATE).edit().putFloat(KEY_OPACITY, opacity).apply()
    }
}
