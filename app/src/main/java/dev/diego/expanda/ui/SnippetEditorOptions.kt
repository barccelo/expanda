package dev.diego.expanda.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AlignHorizontalLeft
import androidx.compose.material.icons.automirrored.filled.AlignHorizontalRight
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Filter1
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.diego.expanda.data.TemplateSelectionMode
import dev.diego.expanda.data.TriggerActivation
import dev.diego.expanda.data.UppercaseStyle

internal fun hasMultipleConfiguredReplacements(replacements: List<String>): Boolean =
    replacements.count(String::isNotBlank) > 1

@Composable
internal fun SnippetMatchingOptionsCard(
    activation: TriggerActivation,
    onActivationChanged: (TriggerActivation) -> Unit,
    delimiters: String,
    onDelimitersChanged: (String) -> Unit,
    alternativeTriggers: String,
    onAlternativeTriggersChanged: (String) -> Unit,
    caseSensitive: Boolean,
    onCaseSensitiveChanged: (Boolean) -> Unit,
    leftWord: Boolean,
    onLeftWordChanged: (Boolean) -> Unit,
    rightWord: Boolean,
    onRightWordChanged: (Boolean) -> Unit,
    propagateCase: Boolean,
    onPropagateCaseChanged: (Boolean) -> Unit,
    uppercaseStyle: UppercaseStyle,
    onUppercaseStyleChanged: (UppercaseStyle) -> Unit,
    searchTerms: String,
    onSearchTermsChanged: (String) -> Unit,
    excludedAppCount: Int,
    onOpenExcludedApps: () -> Unit,
) {
    var editingAlternativeTriggers by remember { mutableStateOf(false) }
    val alternativeCount = alternativeTriggers.lineSequence().count { it.isNotBlank() }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Matching options",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )

            OptionSection(tr("Activation", "Activación")) {
                val options = listOf(
                    ActivationOption(
                        TriggerActivation.IMMEDIATE,
                        tr("Immediately", "Inmediatamente"),
                        Icons.Default.Bolt,
                    ),
                    ActivationOption(
                        TriggerActivation.SPACE,
                        tr("After space", "Después de espacio"),
                        Icons.Default.SpaceBar,
                    ),
                    ActivationOption(
                        TriggerActivation.DELIMITER,
                        tr("Delimiter", "Delimitador"),
                        Icons.Default.TouchApp,
                    ),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    options.forEachIndexed { index, option ->
                        val selected = activation == option.activation
                        SegmentedButton(
                            selected = selected,
                            onClick = { onActivationChanged(option.activation) },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                            icon = {
                                SegmentedButtonDefaults.Icon(active = selected) {
                                    Icon(option.icon, null, Modifier.size(18.dp))
                                }
                            },
                            label = { Text(option.label, maxLines = 2) },
                        )
                    }
                }
                when (activation) {
                    TriggerActivation.IMMEDIATE -> Text(
                        tr(
                            "Expands as soon as the trigger is complete.",
                            "Expande en cuanto se completa el trigger.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TriggerActivation.SPACE -> Text(
                        tr(
                            "Expands only when you press Space after the trigger.",
                            "Expande únicamente al presionar Espacio después del trigger.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TriggerActivation.DELIMITER -> {
                        Text(
                            tr(
                                "Choose which characters activate the trigger.",
                                "Elige qué caracteres activan el trigger.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(DELIMITER_OPTIONS, key = DelimiterOption::value) { option ->
                                val selected = option.value.any { it in delimiters }
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        val next = if (selected) {
                                            delimiters.filterNot { character ->
                                                character in option.value
                                            }
                                        } else {
                                            delimiters + option.value
                                        }
                                        onDelimitersChanged(next.distinct().joinToString(""))
                                    },
                                    label = { Text(option.label) },
                                )
                            }
                        }
                        val knownCharacters = DELIMITER_OPTIONS
                            .flatMap { it.value.toList() }
                            .toSet()
                        val custom = delimiters.filterNot { it in knownCharacters }
                        OutlinedTextField(
                            value = custom,
                            onValueChange = { updatedCustom ->
                                val selectedKnown = delimiters.filter { it in knownCharacters }
                                onDelimitersChanged(
                                    (selectedKnown + updatedCustom)
                                        .distinct()
                                        .joinToString(""),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = {
                                Text(
                                    tr(
                                        "Other delimiter characters",
                                        "Otros caracteres delimitadores",
                                    ),
                                )
                            },
                            supportingText = {
                                Text(
                                    tr(
                                        "Space, Enter and Tab are controlled by the chips above.",
                                        "Espacio, Enter y Tab se controlan con las opciones superiores.",
                                    ),
                                )
                            },
                            singleLine = true,
                        )
                    }
                }
            }

            HorizontalDivider()

            OptionSection("Case matching") {
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(
                            value = caseSensitive,
                            role = Role.Switch,
                            onValueChange = onCaseSensitiveChanged,
                        )
                        .padding(horizontal = 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Default.FormatSize,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(tr("Match letter case exactly"), Modifier.weight(1f))
                    Switch(
                        checked = caseSensitive,
                        onCheckedChange = null,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(
                            value = propagateCase,
                            enabled = !caseSensitive,
                            role = Role.Switch,
                            onValueChange = onPropagateCaseChanged,
                        )
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Default.FormatSize,
                        null,
                        tint = if (caseSensitive) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        "Preserve typed capitalization",
                        Modifier.weight(1f),
                        color = if (caseSensitive) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Switch(
                        checked = propagateCase,
                        onCheckedChange = null,
                        enabled = !caseSensitive,
                    )
                }
                if (propagateCase) {
                    LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(UppercaseStyle.entries) { style ->
                            val label = when (style) {
                                UppercaseStyle.CAPITALIZE -> uiText("First letter", "Primera letra")
                                UppercaseStyle.CAPITALIZE_WORDS -> uiText("Every word", "Cada palabra")
                                UppercaseStyle.UPPERCASE -> uiText("ALL CAPS", "TODO MAYÚSCULAS")
                            }
                            FilterChip(
                                selected = uppercaseStyle == style,
                                onClick = { onUppercaseStyleChanged(style) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            OptionSection("Word boundaries") {
                MultiChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    BoundaryOption.entries.forEachIndexed { index, option ->
                        val selected = when (option) {
                            BoundaryOption.LEFT -> leftWord
                            BoundaryOption.RIGHT -> rightWord
                        }
                        SegmentedButton(
                            checked = selected,
                            onCheckedChange = {
                                when (option) {
                                    BoundaryOption.LEFT -> onLeftWordChanged(it)
                                    BoundaryOption.RIGHT -> onRightWordChanged(it)
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, BoundaryOption.entries.size),
                            icon = {
                                SegmentedButtonDefaults.Icon(active = selected) {
                                    Icon(option.icon, null, Modifier.size(18.dp))
                                }
                            },
                            label = { Text(option.label) },
                        )
                    }
                }
            }

            HorizontalDivider()

            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (editingAlternativeTriggers) {
                    OutlinedTextField(
                        value = alternativeTriggers,
                        onValueChange = onAlternativeTriggersChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(tr("Alternative triggers")) },
                        supportingText = { Text(tr("One per line; use regex: before a regular expression")) },
                        minLines = 2,
                    )
                    TextButton(
                        onClick = { editingAlternativeTriggers = false },
                        modifier = Modifier.align(Alignment.End),
                    ) { Text(tr("Done")) }
                } else {
                    TextButton(onClick = { editingAlternativeTriggers = true }) {
                        Icon(
                            if (alternativeCount == 0) Icons.Default.AddLink else Icons.Default.Edit,
                            null,
                        )
                        Text(
                            if (alternativeCount == 0) "Add alternative trigger"
                            else "Edit alternative triggers ($alternativeCount)",
                        )
                    }
                }
            }

            OutlinedTextField(
                value = searchTerms,
                onValueChange = onSearchTermsChanged,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                label = { Text(tr("Search terms")) },
                supportingText = { Text(tr("Separate terms with commas")) },
                singleLine = true,
            )

            ListItem(
                headlineContent = { Text(tr("Excluded apps")) },
                supportingContent = {
                    Text(
                        if (excludedAppCount == 0) "Available in every app"
                        else "$excludedAppCount ${if (excludedAppCount == 1) "app" else "apps"} excluded",
                    )
                },
                leadingContent = { Icon(Icons.Default.Apps, null) },
                modifier = Modifier.padding(horizontal = 4.dp).clickable(onClick = onOpenExcludedApps),
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Choose excluded apps")
                },
            )
        }
    }
}

@Composable
internal fun ReplacementSelectionCard(
    selectionMode: TemplateSelectionMode,
    onSelectionModeChanged: (TemplateSelectionMode) -> Unit,
) {
    val options = TemplateSelectionMode.entries.map { mode ->
        when (mode) {
            TemplateSelectionMode.FIRST -> SelectionOption(mode, "First", "Always use the first", Icons.Default.Filter1)
            TemplateSelectionMode.RANDOM -> SelectionOption(mode, "Random", "Pick one at random", Icons.Default.Shuffle)
            TemplateSelectionMode.SEQUENTIAL -> SelectionOption(mode, "Sequential", "Android only", Icons.Default.Repeat)
            TemplateSelectionMode.MANUAL -> SelectionOption(mode, "Choose", "Ask each time", Icons.Default.TouchApp)
        }
    }
    val selectedDescription = options.first { it.mode == selectionMode }.description

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    "Replacement selection",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    selectedDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(options) { option ->
                    FilterChip(
                        selected = selectionMode == option.mode,
                        onClick = { onSelectionModeChanged(option.mode) },
                        label = { Text(option.label) },
                        leadingIcon = { Icon(option.icon, null, Modifier.size(18.dp)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        content()
    }
}

private data class ActivationOption(
    val activation: TriggerActivation,
    val label: String,
    val icon: ImageVector,
)

private data class DelimiterOption(
    val value: String,
    val label: String,
)

private val DELIMITER_OPTIONS = listOf(
    DelimiterOption(" ", "Espacio"),
    DelimiterOption("\n", "Enter"),
    DelimiterOption("\t", "Tab"),
    DelimiterOption(".", "."),
    DelimiterOption(",", ","),
    DelimiterOption("!", "!"),
    DelimiterOption("?", "?"),
    DelimiterOption(";", ";"),
    DelimiterOption(":", ":"),
)

private enum class BoundaryOption(val label: String, val icon: ImageVector) {
    LEFT("Left edge", Icons.AutoMirrored.Filled.AlignHorizontalLeft),
    RIGHT("Right edge", Icons.AutoMirrored.Filled.AlignHorizontalRight),
}

private data class SelectionOption(
    val mode: TemplateSelectionMode,
    val label: String,
    val description: String,
    val icon: ImageVector,
)
