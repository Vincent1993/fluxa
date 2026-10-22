package com.fluxa.app.ui.components

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** The name belongs to the control, never to its decorative vector child. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean? = null,
    stateLabel: String? = null,
    testTag: String? = null,
) {
    TooltipBox(
        modifier = modifier,
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        val control = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier).semantics {
            contentDescription = label
            selected?.let { this.selected = it }
            (stateLabel ?: selected?.let { if (it) "已选择" else "未选择" })?.let { stateDescription = it }
        }
        if (selected == true) {
            FilledIconButton(onClick = onClick, enabled = enabled, modifier = control) {
                Icon(icon, contentDescription = null)
            }
        } else {
            IconButton(onClick = onClick, enabled = enabled, modifier = control) {
                Icon(icon, contentDescription = null)
            }
        }
    }
}
