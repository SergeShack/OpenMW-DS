package org.openmw.phone

import android.content.Context
import org.json.JSONObject

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

    /**
     * Over the game while a menu is open: narrower, so the game's windows
     * fit between its sides ([GameScreenPad]). Its look pad is a touchpad for the game's cursor.
     */
    OVER_GAME_MENU("over_game_menu", true),

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
    private const val PREFS_OVER_GAME_MENU = "phone_pad_layout_menu"
    private const val PREFS_BESIDE_COMPANION = "phone_pad_layout_beside_companion"
    private const val KEY_OPACITY = "opacity"

    /** The smallest a part can be made, in dp. */
    const val MIN_SIZE_DP = 32f

    const val OPACITY_DEFAULT = 1f
    const val OPACITY_MIN = 0.2f

    // The columns the defaults follow on a phone: over the game as wide as before the editor, with
    // the same padding, rows and proportions; beside the companion as wide as the sides.
    private const val SIDE = 150f
    private const val PAD = 8f
    private const val GAP = 8f
    private const val ROW = 44f
    private const val ROW_GAP = 6f
    private const val ATTACK = 60f
    private const val TOOL = 34f

    // The menu layout's sides: the right one is wider, for the touchpad, and the face buttons keep
    // the size they have on the left side's width.
    private const val MENU_LEFT = 104f
    private const val MENU_RIGHT = 144f
    private const val MENU_FACE = 88f

    // On a tall screen (a tablet) the parts are made larger by [unitFor], but not stretched over the
    // screen's whole height: they keep the sizes of the menu layout, the same in both layouts, and
    // sit at the bottom of the screen within reach of the thumbs. A phone held sideways is less tall
    // than TALL_SCREEN and keeps the layouts it had.
    private const val TALL_SCREEN = 480f
    private const val MAX_SCALE = 1.3f
    private const val TALL_EXTRA = 1.1f

    /** The touchpad and the look pad on a tall screen, before [unitFor]: no higher than the thumb needs. */
    private const val TALL_PAD = 130f

    /**
     * Room left free under the left column on a tall screen, in dp, for the game's health, magicka
     * and fatigue bars in the bottom left corner. On a phone the column goes down to the bottom.
     */
    private const val HUD_CLEAR = 64f

    /** How much larger the parts are on a screen [height] dp high; 1 on a phone. */
    private fun unitFor(height: Float): Float =
        if (height > TALL_SCREEN) (height / TALL_SCREEN).coerceAtMost(MAX_SCALE) * TALL_EXTRA else 1f

    /** Room for the two tool buttons side by side: two buttons and the gap between them. */
    fun toolsWidth(h: Float) = h * 2 + ROW_GAP

    private fun prefsName(surface: PadSurface) = when (surface) {
        PadSurface.OVER_GAME -> PREFS_OVER_GAME
        PadSurface.OVER_GAME_MENU -> PREFS_OVER_GAME_MENU
        else -> PREFS_BESIDE_COMPANION
    }

    /**
     * Where every part goes before the player moves it. Over the game [areas] is the whole screen;
     * beside the companion it is the left side and the right side.
     */
    fun defaults(surface: PadSurface, areas: List<PadArea>): Map<PadPart, PartPlace> {
        val places = if (surface == PadSurface.OVER_GAME_MENU) {
            menuColumns(areas.first())
        } else if (surface == PadSurface.OVER_GAME) {
            val screen = areas.first()
            val unit = unitFor(screen.h)
            // As wide as the menu layout's right side on a tall screen, so the parts have its sizes.
            val side = if (unit > 1f) MENU_RIGHT * unit else SIDE
            val corner = PAD + OVER_GAME_TOP.value
            columns(
                left = PadArea(screen.x, screen.y, side, screen.h),
                right = PadArea(screen.right - side, screen.y, side, screen.h),
                top = corner,
                unit = unit,
                // The top right corner: the keyboard and quick slot buttons are in the top left one.
                tools = PartPlace(screen.right - PAD - toolsWidth(TOOL), screen.y + (corner - TOOL) / 2, toolsWidth(TOOL), TOOL),
            )
        } else {
            // A row for the tool buttons at the top of the right side, and both columns below it.
            val right = areas.last()
            columns(
                left = areas.first(),
                right = right,
                top = PAD + TOOL + GAP,
                unit = unitFor(right.h),
                tools = PartPlace(right.right - PAD - toolsWidth(TOOL), right.y + PAD, toolsWidth(TOOL), TOOL),
            )
        }
        return places.mapValues { it.value.within(areas) }
    }

    /**
     * The two columns of the controller in [left] and [right], from [top] down. On a phone ([unit]
     * 1) they fill the height: the d-pad and the stick share the left one as 1 to 1.3, the face
     * buttons and the look pad the right one as 1 to 1.1. On a tall screen the parts have the menu
     * layout's sizes ([menuColumns]) and the columns sit at the bottom, the left one above the game's
     * bars.
     */
    private fun columns(left: PadArea, right: PadArea, top: Float, unit: Float, tools: PartPlace): Map<PadPart, PartPlace> {
        val tall = unit > 1f
        val row = ROW * unit
        val attack = ATTACK * unit
        val l = left.x + PAD
        val leftInner = left.w - 2 * PAD
        val leftButton = (leftInner - 2 * ROW_GAP) / 3
        val r = right.x + PAD
        val rightInner = right.w - 2 * PAD
        val rightButton = (rightInner - 2 * ROW_GAP) / 3

        // Left: a row of three buttons, the d-pad and the stick.
        val leftFrom = left.y + top + row + GAP
        val leftBottom = left.bottom - PAD - if (tall) HUD_CLEAR else 0f
        val leftRest = leftBottom - leftFrom - GAP
        val dpad: Float
        val stick: Float
        if (tall) {
            dpad = MENU_LEFT * unit - 2 * PAD
            stick = minOf(dpad * 1.3f, leftRest - dpad)
        } else {
            dpad = leftRest / 2.3f
            stick = leftRest - dpad
        }
        val lt = if (tall) maxOf(left.y + top, leftBottom - (row + GAP + dpad + GAP + stick)) else left.y + top
        val lb = lt + row + GAP

        // Right: a row of three buttons, the face buttons, the attack button and the look pad.
        val rightRest = (right.bottom - PAD) - (right.y + top + row + GAP) - attack - 2 * GAP
        val face: Float
        val look: Float
        if (tall) {
            face = MENU_FACE * unit
            look = minOf(TALL_PAD * unit, rightRest - face)
        } else {
            face = rightRest / 2.1f
            look = rightRest - face
        }
        val rt = if (tall) maxOf(right.y + top, right.bottom - PAD - (row + GAP + face + GAP + attack + GAP + look)) else right.y + top
        val rb = rt + row + GAP

        return mapOf(
            PadPart.LT to PartPlace(l, lt, leftButton, row),
            PadPart.LB to PartPlace(l + leftButton + ROW_GAP, lt, leftButton, row),
            PadPart.BACK to PartPlace(l + 2 * (leftButton + ROW_GAP), lt, leftButton, row),
            PadPart.DPAD to PartPlace(l, lb, leftInner, dpad),
            PadPart.LEFT_STICK to PartPlace(l, lb + dpad + GAP, leftInner, stick),
            PadPart.START to PartPlace(r, rt, rightButton, row),
            PadPart.RB to PartPlace(r + rightButton + ROW_GAP, rt, rightButton, row),
            PadPart.RT to PartPlace(r + 2 * (rightButton + ROW_GAP), rt, rightButton, row),
            PadPart.FACE to PartPlace(r, rb, rightInner, face),
            PadPart.ATTACK to PartPlace(r, rb + face + GAP, rightInner, attack),
            PadPart.LOOK to PartPlace(r, rb + face + GAP + attack + GAP, rightInner, look),
            PadPart.TOOLS to tools,
        )
    }

    /**
     * The menu layout on [screen], narrower than the one for playing and with the same parts: on the
     * left Back and Start, the triggers and shoulder, the d-pad and the stick; on the right the
     * shoulder and trigger, the face buttons, the attack button (which also skips the intro movies)
     * and the touchpad for the game's cursor.
     */
    private fun menuColumns(screen: PadArea): Map<PadPart, PartPlace> {
        val unit = unitFor(screen.h)
        val tall = unit > 1f
        val corner = PAD + OVER_GAME_TOP.value
        val row = ROW * unit
        val leftW = MENU_LEFT * unit
        val rightW = MENU_RIGHT * unit
        val face = MENU_FACE * unit
        val leftInner = leftW - 2 * PAD
        val rightInner = rightW - 2 * PAD
        val l = screen.x + PAD
        val r = screen.right - rightW + PAD
        val leftButton = (leftInner - ROW_GAP) / 2
        val rightButton = (rightInner - ROW_GAP) / 2

        // Left: the d-pad is square and the stick takes the rest down to the bottom. On a tall screen
        // the stick is 1.3 times the d-pad's height and the column ends above the game's bars.
        val leftAbove = 2 * (row + GAP) + leftInner + GAP
        val tallStick = leftInner * 1.3f
        val leftTop = if (tall) maxOf(corner, screen.bottom - PAD - HUD_CLEAR - leftAbove - tallStick) else corner
        val stick = if (tall) tallStick else (screen.bottom - PAD) - (leftTop + leftAbove)

        // Right: the attack button is a row high, and the touchpad takes the rest down to the bottom,
        // on a tall screen [TALL_PAD], with the column low on the screen.
        val rightAbove = row + GAP + face + GAP + row + GAP
        val rest = (screen.bottom - PAD) - (corner + rightAbove)
        val look = if (tall) minOf(rest, TALL_PAD * unit) else rest
        val rightTop = if (tall) screen.bottom - PAD - look - rightAbove else corner

        return mapOf(
            // Back and Start, used less, above the triggers and shoulder, which are nearer the d-pad.
            PadPart.BACK to PartPlace(l, leftTop, leftButton, row),
            PadPart.START to PartPlace(l + leftButton + ROW_GAP, leftTop, leftButton, row),
            PadPart.LT to PartPlace(l, leftTop + row + GAP, leftButton, row),
            PadPart.LB to PartPlace(l + leftButton + ROW_GAP, leftTop + row + GAP, leftButton, row),
            PadPart.DPAD to PartPlace(l, leftTop + 2 * (row + GAP), leftInner, leftInner),
            PadPart.LEFT_STICK to PartPlace(l, leftTop + leftAbove, leftInner, stick),
            PadPart.RB to PartPlace(r, rightTop, rightButton, row),
            PadPart.RT to PartPlace(r + rightButton + ROW_GAP, rightTop, rightButton, row),
            PadPart.FACE to PartPlace(r, rightTop + row + GAP, rightInner, face),
            PadPart.ATTACK to PartPlace(r, rightTop + row + GAP + face + GAP, rightInner, row),
            PadPart.LOOK to PartPlace(r, rightTop + rightAbove, rightInner, look),
            // The top right corner, as in the layout for playing.
            PadPart.TOOLS to PartPlace(screen.right - PAD - toolsWidth(TOOL), screen.y + (corner - TOOL) / 2, toolsWidth(TOOL), TOOL),
        )
    }

    /**
     * The shares of [screen]'s width left of the game's windows and right of them while a menu is
     * open: as far as the menu layout's parts and key buttons reach in from each side, with a gap.
     * Both 0 while the controller is hidden.
     */
    fun menuSides(parts: Map<PadPart, PartPlace>, keys: List<KeyButton>, screen: PadArea): Pair<Float, Float> {
        if (screen.w <= 0f) return 0f to 0f
        val shown = parts.filter { (part, place) -> part != PadPart.TOOLS && !place.hidden }.values + keys.map { it.place }
        val middle = screen.x + screen.w / 2
        val left = shown.filter { it.centerX < middle }.maxOfOrNull { it.x + it.w }?.let { it - screen.x + MENU_WINDOW_GAP } ?: 0f
        val right = shown.filter { it.centerX >= middle }.minOfOrNull { it.x }?.let { screen.right - it + MENU_WINDOW_GAP } ?: 0f
        return (left / screen.w).coerceIn(0f, 0.45f) to (right / screen.w).coerceIn(0f, 0.45f)
    }

    /** Room kept between the menu layout and the game's windows, in dp. */
    private const val MENU_WINDOW_GAP = 4f

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

    /** The stored layout of [surface] as it is kept, for a saved layout ([PadPresets]). */
    fun toJson(context: Context, surface: PadSurface): JSONObject {
        val prefs = context.getSharedPreferences(prefsName(surface), Context.MODE_PRIVATE)
        val json = JSONObject()
        for (part in PadPart.entries) {
            val k = part.key
            val o = JSONObject().put("hidden", prefs.getBoolean("$k.hidden", false))
            if (prefs.contains("$k.cx")) {
                o.put("area", prefs.getInt("$k.area", 0))
                for (field in listOf("cx", "cy", "w", "h")) o.put(field, prefs.getFloat("$k.$field", 0f).toDouble())
            }
            json.put(k, o)
        }
        return json
    }

    /** Stores [json] (from [toJson]) as the layout of [surface]; without it every part is where the defaults put it. */
    fun fromJson(context: Context, surface: PadSurface, json: JSONObject?) {
        val edit = context.getSharedPreferences(prefsName(surface), Context.MODE_PRIVATE).edit()
        for (part in PadPart.entries) {
            val k = part.key
            edit.remove("$k.hidden").remove("$k.area").remove("$k.cx").remove("$k.cy").remove("$k.w").remove("$k.h")
            val o = json?.optJSONObject(k) ?: continue
            edit.putBoolean("$k.hidden", o.optBoolean("hidden", false))
            if (!o.has("cx")) continue
            edit.putInt("$k.area", o.optInt("area", 0))
            for (field in listOf("cx", "cy", "w", "h")) edit.putFloat("$k.$field", o.optDouble(field, 0.0).toFloat())
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
