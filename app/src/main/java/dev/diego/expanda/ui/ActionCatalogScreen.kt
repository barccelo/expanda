package dev.diego.expanda.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardTab
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.diego.expanda.engine.ActionCategory
import dev.diego.expanda.engine.ActionDefinition
import dev.diego.expanda.engine.ActionEngine

@Composable
fun ActionCatalogScreen(
    enabledIds: Set<String>,
    triggerOverrides: Map<String, List<String>>,
    suggestionEnabledIds: Set<String>,
    onSetEnabled: (String, Boolean) -> Unit,
    onSetAllEnabled: (Boolean) -> Unit,
    onSetSuggestionEnabled: (String, Boolean) -> Unit,
    onSetTriggers: (String, List<String>) -> Unit,
    onResetTriggers: (String) -> Unit,
) {
    val groups = ActionEngine.definitions.groupBy(ActionDefinition::category)
    var expandedCategories by remember { mutableStateOf(setOf(ActionCategory.NUMBER)) }
    var editingAction by remember { mutableStateOf<ActionDefinition?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { onSetAllEnabled(true) }, label = { Text(tr("Enable all")) })
                    AssistChip(onClick = { onSetAllEnabled(false) }, label = { Text(tr("Disable all")) })
                }
            }
        }
        groups.forEach { (category, definitions) ->
            item(key = "category_${category.name}") {
                val expanded = category in expandedCategories
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        ListItem(
                            headlineContent = { Text(category.displayName()) },
                            supportingContent = {
                                Text(tr("${definitions.count { it.id in enabledIds }} of ${definitions.size} enabled", "${definitions.count { it.id in enabledIds }} de ${definitions.size} activadas"))
                            },
                            leadingContent = { Icon(category.icon(), null) },
                            trailingContent = {
                                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                            },
                            modifier = Modifier.clickable {
                                expandedCategories = if (expanded) expandedCategories - category
                                else expandedCategories + category
                            },
                        )
                        if (expanded) definitions.forEachIndexed { index, definition ->
                            if (index > 0) HorizontalDivider()
                            ActionRow(
                                definition = definition,
                                triggers = triggerOverrides[definition.id] ?: definition.triggers,
                                enabled = definition.id in enabledIds,
                                suggestionEnabled = definition.id in suggestionEnabledIds,
                                onSetEnabled = { onSetEnabled(definition.id, it) },
                                onSetSuggestionEnabled = {
                                    onSetSuggestionEnabled(definition.id, it)
                                },
                                onEdit = { editingAction = definition },
                            )
                        }
                    }
                }
            }
        }
    }

    editingAction?.let { definition ->
        TriggerEditorDialog(
            definition = definition,
            currentTriggers = triggerOverrides[definition.id] ?: definition.triggers,
            allTriggers = ActionEngine.definitions.associate { candidate ->
                candidate.id to (triggerOverrides[candidate.id] ?: candidate.triggers)
            },
            onDismiss = { editingAction = null },
            onSave = {
                onSetTriggers(definition.id, it)
                editingAction = null
            },
            onReset = {
                onResetTriggers(definition.id)
                editingAction = null
            },
        )
    }
}

@Composable
private fun ActionRow(
    definition: ActionDefinition,
    triggers: List<String>,
    enabled: Boolean,
    suggestionEnabled: Boolean,
    onSetEnabled: (Boolean) -> Unit,
    onSetSuggestionEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    triggers.joinToString("  ·  "),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    tr(definition.title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    tr(definition.description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, tr("Edit shortcut"))
            }
            Switch(
                checked = enabled,
                onCheckedChange = onSetEnabled,
            )
        }

        if (definition.supportsSelectedText) {
            Text(
                tr(
                    "Also available from Android's selected-text menu",
                    "También disponible desde el menú de texto seleccionado de Android",
                ),
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(top = 10.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSetSuggestionEnabled(!suggestionEnabled) }
                .padding(top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Lightbulb,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                tr("Show in suggestions", "Mostrar en sugerencias"),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = suggestionEnabled,
                onCheckedChange = onSetSuggestionEnabled,
            )
        }
    }
}

@Composable
private fun TriggerEditorDialog(
    definition: ActionDefinition,
    currentTriggers: List<String>,
    allTriggers: Map<String, List<String>>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
    onReset: () -> Unit,
) {
    var values by remember(definition.id, currentTriggers) {
        mutableStateOf(currentTriggers.ifEmpty { definition.triggers })
    }
    val nonBlank = values.filter(String::isNotBlank)
    val duplicateInside = nonBlank.size != nonBlank.distinct().size
    val usedElsewhere = allTriggers
        .filterKeys { it != definition.id }
        .values
        .flatten()
        .toSet()
    val duplicateElsewhere = nonBlank.firstOrNull { it in usedElsewhere }
    val error = when {
        values.isEmpty() || values.any(String::isBlank) -> tr(
            "Each action needs at least one non-empty trigger",
            "Cada acción necesita al menos un trigger no vacío",
        )
        duplicateInside -> tr(
            "The same trigger appears more than once",
            "El mismo trigger aparece más de una vez",
        )
        duplicateElsewhere != null -> tr(
            "Another action already uses “$duplicateElsewhere”",
            "Otra acción ya usa “$duplicateElsewhere”",
        )
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Edit action triggers", "Editar triggers de la acción")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr(definition.title))
                values.forEachIndexed { index, value ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { updated ->
                                values = values.toMutableList().also { it[index] = updated }
                            },
                            label = {
                                Text(
                                    if (index == 0) tr("Primary trigger", "Trigger principal")
                                    else tr("Alias ${index + 1}", "Alias ${index + 1}"),
                                )
                            },
                            isError = error != null,
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        if (values.size > 1) {
                            TextButton(
                                onClick = {
                                    values = values.toMutableList().also { it.removeAt(index) }
                                },
                            ) {
                                Text("×")
                            }
                        }
                    }
                }
                TextButton(onClick = { values = values + "" }) {
                    Text(tr("+ Add trigger", "+ Agregar trigger"))
                }
                if (error != null) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        tr(
                            "Spaces at the beginning or end are preserved.",
                            "Los espacios al inicio o al final se conservan.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = onReset,
                    enabled = currentTriggers != definition.triggers,
                ) {
                    Icon(Icons.Outlined.RestartAlt, null)
                    Text(tr("Restore defaults", "Restablecer predeterminados"))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(values.filter(String::isNotBlank).distinct()) },
                enabled = error == null,
            ) {
                Text(tr("Save"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}
@Composable
private fun ActionCategory.displayName(): String = when (this) {
    ActionCategory.NUMBER -> tr("Numbers and calculations")
    ActionCategory.TEXT -> tr("Text")
    ActionCategory.SELECTION -> tr("Selection")
    ActionCategory.DELETION -> tr("Deletion")
    ActionCategory.CURSOR -> tr("Cursor")
    ActionCategory.CLIPBOARD -> tr("Clipboard")
    ActionCategory.ANDROID -> "Android"
    ActionCategory.EXPANDA -> "Expanda"
}

private fun ActionCategory.icon(): ImageVector = when (this) {
    ActionCategory.NUMBER -> Icons.Outlined.Calculate
    ActionCategory.TEXT -> Icons.Outlined.TextFields
    ActionCategory.SELECTION -> Icons.Outlined.SelectAll
    ActionCategory.DELETION -> Icons.Outlined.DeleteSweep
    ActionCategory.CURSOR -> Icons.AutoMirrored.Outlined.KeyboardTab
    ActionCategory.CLIPBOARD -> Icons.Outlined.ContentPaste
    ActionCategory.ANDROID -> Icons.Outlined.Android
    ActionCategory.EXPANDA -> Icons.Outlined.Bolt
}
