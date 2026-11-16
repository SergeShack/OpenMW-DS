package org.openmw.phone

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The keys a key button can press, in groups as the picker shows them, and their labels.
 *
 * Android key codes: SDL turns them into the same keys a real keyboard gives, which is what the
 * game's and the mods' bindings are made of.
 */
internal object KeyNames {
    private val letters = (KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z).map { it to ('A' + (it - KeyEvent.KEYCODE_A)).toString() }

    // 1 to 9 and then 0, as on a keyboard.
    private val digits = ((KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) + KeyEvent.KEYCODE_0)
        .map { it to (it - KeyEvent.KEYCODE_0).toString() }

    private val functionKeys = (KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12)
        .map { it to "F${it - KeyEvent.KEYCODE_F1 + 1}" }

    private val editing = listOf(
        KeyEvent.KEYCODE_ESCAPE to "Esc",
        KeyEvent.KEYCODE_TAB to "Tab",
        KeyEvent.KEYCODE_SPACE to "Space",
        KeyEvent.KEYCODE_ENTER to "Enter",
        KeyEvent.KEYCODE_DEL to "Bksp",
        KeyEvent.KEYCODE_FORWARD_DEL to "Del",
        KeyEvent.KEYCODE_INSERT to "Ins",
        KeyEvent.KEYCODE_MOVE_HOME to "Home",
        KeyEvent.KEYCODE_MOVE_END to "End",
        KeyEvent.KEYCODE_PAGE_UP to "PgUp",
        KeyEvent.KEYCODE_PAGE_DOWN to "PgDn",
        KeyEvent.KEYCODE_DPAD_UP to "↑",
        KeyEvent.KEYCODE_DPAD_DOWN to "↓",
        KeyEvent.KEYCODE_DPAD_LEFT to "←",
        KeyEvent.KEYCODE_DPAD_RIGHT to "→",
    )

    private val modifiers = listOf(
        KeyEvent.KEYCODE_SHIFT_LEFT to "LShift",
        KeyEvent.KEYCODE_SHIFT_RIGHT to "RShift",
        KeyEvent.KEYCODE_CTRL_LEFT to "LCtrl",
        KeyEvent.KEYCODE_CTRL_RIGHT to "RCtrl",
        KeyEvent.KEYCODE_ALT_LEFT to "LAlt",
        KeyEvent.KEYCODE_ALT_RIGHT to "RAlt",
    )

    private val symbols = listOf(
        KeyEvent.KEYCODE_GRAVE to "`",
        KeyEvent.KEYCODE_MINUS to "-",
        KeyEvent.KEYCODE_EQUALS to "=",
        KeyEvent.KEYCODE_LEFT_BRACKET to "[",
        KeyEvent.KEYCODE_RIGHT_BRACKET to "]",
        KeyEvent.KEYCODE_BACKSLASH to "\\",
        KeyEvent.KEYCODE_SEMICOLON to ";",
        KeyEvent.KEYCODE_APOSTROPHE to "'",
        KeyEvent.KEYCODE_COMMA to ",",
        KeyEvent.KEYCODE_PERIOD to ".",
        KeyEvent.KEYCODE_SLASH to "/",
    )

    private val numpad = (KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9)
        .map { it to "Num${it - KeyEvent.KEYCODE_NUMPAD_0}" } + listOf(
        KeyEvent.KEYCODE_NUMPAD_DIVIDE to "Num/",
        KeyEvent.KEYCODE_NUMPAD_MULTIPLY to "Num*",
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT to "Num-",
        KeyEvent.KEYCODE_NUMPAD_ADD to "Num+",
        KeyEvent.KEYCODE_NUMPAD_DOT to "Num.",
        KeyEvent.KEYCODE_NUMPAD_ENTER to "NumEnter",
    )

    /** The groups in the order the picker shows them. */
    val groups: List<Pair<String, List<Pair<Int, String>>>> = listOf(
        "Letters" to letters,
        "Numbers" to digits,
        "Function keys" to functionKeys,
        "Editing and arrows" to editing,
        "Modifiers" to modifiers,
        "Symbols" to symbols,
        "Number pad" to numpad,
    )

    private val labels: Map<Int, String> = groups.flatMap { it.second }.toMap()

    fun label(keyCode: Int): String = labels[keyCode] ?: "Key $keyCode"

    /** Smaller text for the longer labels, so they fit a small button. */
    fun labelSize(keyCode: Int): TextUnit = when (label(keyCode).length) {
        in 0..2 -> 16.sp
        in 3..4 -> 14.sp
        else -> 12.sp
    }
}

private val PickerBackground = Color(0xF0201810)

/**
 * Choosing the key of a key button: every key the button can press, in groups. A tap on one picks
 * it; Cancel picks none.
 */
@Composable
internal fun KeyPicker(modifier: Modifier, current: Int?, onPick: (Int) -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = modifier
            .sizeIn(maxWidth = 520.dp)
            .fillMaxHeight(0.9f)
            .background(PickerBackground, RoundedCornerShape(12.dp))
            .border(1.dp, PadBronze, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Choose a key", color = PadBronze, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        val scroll = rememberScrollState()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for ((title, keys) in KeyNames.groups) {
                    Text(title, color = PadBronze.copy(alpha = 0.7f), fontSize = 12.sp)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for ((code, label) in keys) {
                            PickerKey(label, selected = code == current) { onPick(code) }
                        }
                    }
                }
            }
            // There are more keys below: the list fades out at the bottom, with an arrow.
            if (scroll.canScrollForward) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(32.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, PickerBackground))),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Text("▼ more keys below", color = PadBronze, fontSize = 12.sp)
                }
            }
        }
        PickerKey("Cancel", selected = false, onClick = onCancel)
    }
}

@Composable
private fun PickerKey(label: String, selected: Boolean, onClick: () -> Unit) {
    val click by rememberUpdatedState(onClick)
    Box(
        Modifier
            .sizeIn(minWidth = 40.dp, minHeight = 36.dp)
            .background(if (selected) PadPressed else PadFill, RoundedCornerShape(6.dp))
            .border(1.dp, PadBronze, RoundedCornerShape(6.dp))
            // A click, not a press: a finger that moves scrolls the list instead.
            .clickable {
                PadHaptics.play(PadHaptic.FACE)
                click()
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = PadBronze, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}
