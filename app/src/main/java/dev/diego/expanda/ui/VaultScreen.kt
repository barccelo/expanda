package dev.diego.expanda.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.diego.expanda.data.VaultEntry
import dev.diego.expanda.data.VaultField

@Composable
fun VaultScreen(
    entries: List<VaultEntry>,
    onSave: (VaultEntry) -> Unit,
    onDelete: (Long) -> Unit,
    onCopy: (String, Boolean) -> Unit,
    onUpdateFromClipboard: (Long, String, String) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    var viewing by remember { mutableStateOf<VaultEntry?>(null) }
    var editing by remember { mutableStateOf<VaultEntry?>(null) }
    var creating by remember { mutableStateOf(false) }

    val visible = entries
        .filter { entry ->
            search.isBlank() ||
                entry.title.contains(search, ignoreCase = true) ||
                entry.tags.any { it.contains(search, ignoreCase = true) } ||
                entry.fields.any { it.label.contains(search, ignoreCase = true) }
        }
        .sortedWith(
            compareByDescending<VaultEntry> { it.favorite }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text(tr("Search vault", "Buscar en la bóveda")) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )

            if (visible.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.Lock, null)
                    Text(
                        if (entries.isEmpty()) {
                            tr("Your vault is empty", "Tu bóveda está vacía")
                        } else {
                            tr("No matching entries", "No hay coincidencias")
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (entries.isEmpty()) {
                        Text(
                            tr(
                                "Store notes, usernames, passwords and other text you need to retrieve quickly.",
                                "Guarda notas, usuarios, contraseñas y otros textos que necesites consultar rápidamente.",
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visible, key = VaultEntry::id) { entry ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewing = entry },
                        ) {
                            ListItem(
                                headlineContent = {
                                    Text(entry.title, fontWeight = FontWeight.SemiBold)
                                },
                                supportingContent = {
                                    val triggerText = entry.triggers.joinToString(" · ")
                                    Text(
                                        buildString {
                                            append(
                                                tr(
                                                    "${entry.fields.size} fields",
                                                    "${entry.fields.size} campos",
                                                ),
                                            )
                                            if (triggerText.isNotBlank()) append(" · $triggerText")
                                        },
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        if (entry.favorite) Icons.Default.Favorite else Icons.Default.Lock,
                                        null,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { creating = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
        ) {
            Icon(Icons.Default.Add, tr("New vault entry", "Nueva entrada"))
        }
    }

    viewing?.let { entry ->
        VaultEntryDialog(
            entry = entries.firstOrNull { it.id == entry.id } ?: entry,
            onDismiss = { viewing = null },
            onEdit = {
                editing = it
                viewing = null
            },
            onDelete = {
                onDelete(it.id)
                viewing = null
            },
            onCopy = onCopy,
            onUpdateFromClipboard = onUpdateFromClipboard,
        )
    }

    if (creating || editing != null) {
        VaultEditorDialog(
            initial = editing,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { entry ->
                onSave(entry)
                creating = false
                editing = null
            },
        )
    }
}

@Composable
private fun VaultEntryDialog(
    entry: VaultEntry,
    onDismiss: () -> Unit,
    onEdit: (VaultEntry) -> Unit,
    onDelete: (VaultEntry) -> Unit,
    onCopy: (String, Boolean) -> Unit,
    onUpdateFromClipboard: (Long, String, String) -> Unit,
) {
    val context = LocalContext.current
    var visibleSensitive by remember(entry.id) { mutableStateOf<Set<String>>(emptySet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, null)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.title)
                    if (entry.triggers.isNotEmpty()) {
                        Text(
                            entry.triggers.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = { onEdit(entry) }) {
                    Icon(Icons.Default.Edit, tr("Edit", "Editar"))
                }
            }
        },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entry.fields, key = VaultField::id) { field ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(field.label, fontWeight = FontWeight.SemiBold)
                            val revealed = !field.sensitive || field.id in visibleSensitive
                            Text(
                                if (revealed) field.value else "••••••••",
                                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (field.sensitive) {
                                    TextButton(
                                        onClick = {
                                            visibleSensitive = if (revealed) {
                                                visibleSensitive - field.id
                                            } else {
                                                visibleSensitive + field.id
                                            }
                                        },
                                    ) {
                                        Text(
                                            if (revealed) tr("Hide", "Ocultar")
                                            else tr("Show", "Mostrar"),
                                        )
                                    }
                                }
                                TextButton(onClick = { onCopy(field.value, field.sensitive) }) {
                                    Text(tr("Copy", "Copiar"))
                                }
                                TextButton(
                                    onClick = {
                                        currentClipboardText(context)?.let { clipboard ->
                                            onUpdateFromClipboard(entry.id, field.id, clipboard)
                                        }
                                    },
                                ) {
                                    Text(tr("Use clipboard", "Usar portapapeles"))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(tr("Close", "Cerrar")) }
        },
        dismissButton = {
            TextButton(onClick = { onDelete(entry) }) {
                Icon(Icons.Default.Delete, null)
                Text(tr("Delete", "Eliminar"))
            }
        },
    )
}

@Composable
private fun VaultEditorDialog(
    initial: VaultEntry?,
    onDismiss: () -> Unit,
    onSave: (VaultEntry) -> Unit,
) {
    var title by remember(initial?.id) { mutableStateOf(initial?.title.orEmpty()) }
    var triggerText by remember(initial?.id) {
        mutableStateOf(initial?.triggers?.joinToString("\n").orEmpty())
    }
    var tagsText by remember(initial?.id) {
        mutableStateOf(initial?.tags?.joinToString(", ").orEmpty())
    }
    var favorite by remember(initial?.id) { mutableStateOf(initial?.favorite ?: false) }
    val defaultValueLabel = tr("Value", "Valor")
    var fields by remember(initial?.id, defaultValueLabel) {
        mutableStateOf(
            initial?.fields?.takeIf { it.isNotEmpty() }
                ?: listOf(VaultField(label = defaultValueLabel, value = "")),
        )
    }

    val valid = title.isNotBlank() && fields.any { it.label.isNotBlank() && it.value.isNotEmpty() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initial == null) tr("New vault entry", "Nueva entrada")
                else tr("Edit vault entry", "Editar entrada"),
            )
        },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text(tr("Name", "Nombre")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = triggerText,
                        onValueChange = { triggerText = it },
                        label = { Text(tr("Triggers — one per line", "Triggers — uno por línea")) },
                        supportingText = {
                            Text(
                                tr(
                                    "Leading spaces are preserved.",
                                    "Los espacios iniciales se conservan.",
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = tagsText,
                        onValueChange = { tagsText = it },
                        label = { Text(tr("Tags", "Etiquetas")) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            null,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(tr("Favorite", "Favorito"), modifier = Modifier.weight(1f))
                        Switch(checked = favorite, onCheckedChange = { favorite = it })
                    }
                }
                item { HorizontalDivider() }
                items(fields, key = VaultField::id) { field ->
                    val index = fields.indexOfFirst { it.id == field.id }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            OutlinedTextField(
                                value = field.label,
                                onValueChange = { value ->
                                    fields = fields.toMutableList().also {
                                        it[index] = field.copy(label = value)
                                    }
                                },
                                label = { Text(tr("Field name", "Nombre del campo")) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = field.value,
                                onValueChange = { value ->
                                    fields = fields.toMutableList().also {
                                        it[index] = field.copy(value = value)
                                    }
                                },
                                label = { Text(tr("Value", "Valor")) },
                                visualTransformation = if (field.sensitive) {
                                    PasswordVisualTransformation()
                                } else {
                                    androidx.compose.ui.text.input.VisualTransformation.None
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(tr("Sensitive", "Sensible"), modifier = Modifier.weight(1f))
                                Switch(
                                    checked = field.sensitive,
                                    onCheckedChange = { checked ->
                                        fields = fields.toMutableList().also {
                                            it[index] = field.copy(sensitive = checked)
                                        }
                                    },
                                )
                                IconButton(
                                    onClick = {
                                        if (fields.size > 1) {
                                            fields = fields.filterNot { it.id == field.id }
                                        }
                                    },
                                    enabled = fields.size > 1,
                                ) {
                                    Icon(Icons.Default.Delete, tr("Delete field", "Eliminar campo"))
                                }
                            }
                        }
                    }
                }
                item {
                    TextButton(
                        onClick = {
                            fields = fields + VaultField(
                                label = defaultValueLabel,
                                value = "",
                            )
                        },
                    ) {
                        Icon(Icons.Default.Add, null)
                        Text(tr("Add field", "Agregar campo"))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    val base = initial ?: VaultEntry(title = title)
                    onSave(
                        base.copy(
                            title = title,
                            triggers = triggerText
                                .split("\n")
                                .filter(String::isNotBlank)
                                .distinct(),
                            fields = fields,
                            tags = tagsText
                                .split(",")
                                .map(String::trim)
                                .filter(String::isNotBlank)
                                .toSet(),
                            favorite = favorite,
                        ),
                    )
                },
            ) {
                Text(tr("Save", "Guardar"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Cancel", "Cancelar")) }
        },
    )
}

private fun currentClipboardText(context: Context): String? {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return runCatching {
        manager.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?.takeIf(String::isNotBlank)
    }.getOrNull()
}
