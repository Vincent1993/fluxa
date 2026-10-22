package com.fluxa.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** Visible labels for choices and sheet actions; compact toolbars keep IconAction. */
@Composable
fun ActionRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean? = null,
    stateLabel: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick, enabled = enabled,
        color = if (selected == true) colors.secondaryContainer else colors.surface,
        contentColor = if (!enabled) colors.onSurface.copy(alpha = .38f)
            else if (selected == true) colors.onSecondaryContainer else colors.onSurface,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp).semantics {
            contentDescription = label
            role = Role.Button
            selected?.let { this.selected = it }
            (stateLabel ?: selected?.let { if (it) "已选择" else "未选择" })?.let { stateDescription = it }
        },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, Modifier.size(22.dp))
            Text(label, Modifier.weight(1f).clearAndSetSemantics {}, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun MenuAction(label: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true) {
    DropdownMenuItem(
        text = { Text(label, Modifier.clearAndSetSemantics {}) },
        leadingIcon = { Icon(icon, null) }, onClick = onClick, enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = label },
    )
}
