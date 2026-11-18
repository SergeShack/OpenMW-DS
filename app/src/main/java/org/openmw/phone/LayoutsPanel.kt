package org.openmw.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The saved layouts ([PadPresets]), in the editor: the layout in use can be saved over each one, or
 * under a new name, and each can be loaded, renamed or deleted. [status] is a line about the last
 * thing done.
 */
@Composable
internal fun LayoutsPanel(
    modifier: Modifier,
    names: List<String>,
    status: String?,
    onSaveAs: () -> Unit,
    onSave: (String) -> Unit,
    onLoad: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = modifier
            .sizeIn(maxWidth = 520.dp)
            .fillMaxHeight(0.9f)
            .background(Color(0xF0201810), RoundedCornerShape(12.dp))
            .border(1.dp, PadBronze, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Layouts", color = PadBronze, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            "A layout keeps the whole touch controller, over the game and beside the companion. " +
                "They are files in ${PadPresets.FOLDER_LABEL}: copy one there to add it.",
            color = PadBronze.copy(alpha = 0.7f),
            fontSize = 12.sp,
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (names.isEmpty()) {
                Text("No saved layouts yet.", color = PadBronze, fontSize = 14.sp)
            }
            for (name in names) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        name,
                        color = PadBronze,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    BarButton("Save") { onSave(name) }
                    BarButton("Load") { onLoad(name) }
                    BarButton("Rename") { onRename(name) }
                    BarButton("Delete") { onDelete(name) }
                }
            }
        }
        if (status != null) Text(status, color = Color(0xFFFFE08A), fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BarButton("Save current as…", onClick = onSaveAs)
            BarButton("Close", onClick = onClose)
        }
    }
}
