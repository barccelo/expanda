package dev.diego.expanda.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material3.Checkbox
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
import dev.diego.expanda.data.VaultCategory
import dev.diego.expanda.data.VaultEntry
import dev.diego.expanda.data.VaultField

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VaultScreen(
    entries: List<VaultEntry>,
    categories: List<VaultCategory>,
    onSave: (VaultEntry) -> Unit,
    onDelete: (Long) -> Unit,
    onSaveCategory: (VaultCategory) -> Unit,
    onDeleteCategory: (Long) -> Unit,
    onMoveEntries: (Set<Long>, String?) -> Unit,
    onMoveTriggers: (Set<String>, Long?, Long?) -> Unit,
    onCopy: (String, Boolean) -> Unit,
    onUpdateFromClipboard: (Long, String, String) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    var viewing by remember { mutableStateOf<VaultEntry?>(null) }
    var editing by remember { mutableStateOf<VaultEntry?>(null) }
    var creating by remember { mutableStateOf(false) }
    var creatingInCategory by remember { mutableStateOf<String?>(null) }
    var creatingCategory by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf<VaultCategory?>(null) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showTriggerManager by remember { mutableStateOf(false) }
    var movingTriggers by remember { mutableStateOf<Set<String>>(emptySet()) }

    val visible = entries
        .filter { entry ->
            search.isBlank() ||
                entry.title.contains(search, ignoreCase = true) ||
                entry.category?.contains(search, ignoreCase = true) == true ||
                entry.tags.any { it.contains(search, ignoreCase = true) } ||
                entry.fields.any { it.label.contains(search, ignoreCase = true) }
        }
        .sortedWith(
            compareBy<VaultEntry> { it.category.isNullOrBlank() }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.category.orEmpty() }
                .thenByDescending { it.favorite }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )
    val grouped = visible.groupBy { it.category?.takeIf(String::isNotBlank) }
    val knownCategoryNames = (
        categories.map(VaultCategory::name) +
            visible.mapNotNull(VaultEntry::category)
        )
        .distinctBy { it.lowercase() }
        .filter { search.isBlank() || it.contains(search, ignoreCase = true) || grouped[it].orEmpty().isNotEmpty() }
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
    val sections = buildList<Pair<String?, List<VaultEntry>>> {
        knownCategoryNames.forEach { name ->
            add(name to visible.filter { it.category?.equals(name, ignoreCase = true) == true })
        }
        val uncategorized = visible.filter { it.category.isNullOrBlank() }
        if (uncategorized.isNotEmpty()) add(null to uncategorized)
    }

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

            if (selectedIds.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        tr(
                            "${selectedIds.size} selected",
                            "${selectedIds.size} seleccionadas",
                        ),
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold,
                    )
                    TextButton(onClick = { showMoveDialog = true }) {
                        Text(tr("Move", "Mover"))
                    }
                    TextButton(onClick = {
                        onMoveEntries(selectedIds, null)
                        selectedIds = emptySet()
                    }) {
                        Text(tr("Uncategorize", "Sin categoría"))
                    }
                    TextButton(onClick = { selectedIds = emptySet() }) {
                        Text(tr("Cancel", "Cancelar"))
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { creatingCategory = true }) {
                        Icon(Icons.Default.Add, null)
                        Text(tr("Category", "Categoría"))
                    }
                    TextButton(onClick = { showTriggerManager = true }) {
                        Text(tr("Manage triggers", "Administrar triggers"))
                    }
                }
            }

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
                    sections.forEach { (category, categoryEntries) ->
                        item(key = "category:${category ?: "__none__"}") {
                            val categoryEntity = category?.let { name ->
                                categories.firstOrNull { it.name.equals(name, ignoreCase = true) }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, start = 4.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        category ?: tr("Uncategorized", "Sin categoría"),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    if (categoryEntity != null && categoryEntity.triggers.isNotEmpty()) {
                                        Text(
                                            categoryEntity.triggers.joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (categoryEntity != null) {
                                    IconButton(
                                        onClick = {
                                            creatingInCategory = categoryEntity.name
                                            creating = true
                                        },
                                    ) {
                                        Icon(
                                            Icons.Default.Add,
                                            tr("New entry in category", "Nueva entrada en categoría"),
                                        )
                                    }
                                    IconButton(onClick = { editingCategory = categoryEntity }) {
                                        Icon(
                                            Icons.Default.Edit,
                                            tr("Edit category", "Editar categoría"),
                                        )
                                    }
                                }
                            }
                        }
                        items(categoryEntries, key = VaultEntry::id) { entry ->
                            val selected = entry.id in selectedIds
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            if (selectedIds.isNotEmpty()) {
                                                selectedIds = if (selected) {
                                                    selectedIds - entry.id
                                                } else {
                                                    selectedIds + entry.id
                                                }
                                            } else {
                                                viewing = entry
                                            }
                                        },
                                        onLongClick = {
                                            selectedIds = if (selected) {
                                                selectedIds - entry.id
                                            } else {
                                                selectedIds + entry.id
                                            }
                                        },
                                    ),
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
                                        if (selectedIds.isNotEmpty()) {
                                            Checkbox(
                                                checked = selected,
                                                onCheckedChange = {
                                                    selectedIds = if (selected) {
                                                        selectedIds - entry.id
                                                    } else {
                                                        selectedIds + entry.id
                                                    }
                                                },
                                            )
                                        } else {
                                            Icon(
                                                if (entry.favorite) Icons.Default.Favorite else Icons.Default.Lock,
                                                null,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = {
                creatingInCategory = null
                creating = true
            },
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
            presetCategory = if (editing == null) creatingInCategory else null,
            onDismiss = {
                creating = false
                creatingInCategory = null
                editing = null
            },
            onSave = { entry ->
                onSave(entry)
                creating = false
                creatingInCategory = null
                editing = null
            },
        )
    }

    if (creatingCategory) {
        VaultCategoryEditorDialog(
            initial = VaultCategory(name = ""),
            onDismiss = { creatingCategory = false },
            onSave = {
                onSaveCategory(it)
                creatingCategory = false
            },
            onDelete = {},
        )
    }

    editingCategory?.let { category ->
        VaultCategoryEditorDialog(
            initial = categories.firstOrNull { it.id == category.id } ?: category,
            onDismiss = { editingCategory = null },
            onSave = {
                onSaveCategory(it)
                editingCategory = null
            },
            onDelete = {
                onDeleteCategory(it.id)
                editingCategory = null
            },
        )
    }

    if (showMoveDialog) {
        VaultMoveEntriesDialog(
            categories = categories,
            selectedCount = selectedIds.size,
            onDismiss = { showMoveDialog = false },
            onMove = { categoryName ->
                onMoveEntries(selectedIds, categoryName)
                selectedIds = emptySet()
                showMoveDialog = false
            },
        )
    }

    if (showTriggerManager) {
        VaultTriggerManagerDialog(
            entries = entries,
            categories = categories,
            onDismiss = { showTriggerManager = false },
            onMove = { movingTriggers = it },
        )
    }

    if (movingTriggers.isNotEmpty()) {
        VaultTriggerMoveDialog(
            triggers = movingTriggers,
            entries = entries,
            categories = categories,
            onDismiss = { movingTriggers = emptySet() },
            onMoveToCategory = { categoryId ->
                onMoveTriggers(movingTriggers, categoryId, null)
                movingTriggers = emptySet()
            },
            onMoveToEntry = { entryId ->
                onMoveTriggers(movingTriggers, null, entryId)
                movingTriggers = emptySet()
            },
        )
    }
}

@Composable
private fun VaultMoveEntriesDialog(
    categories: List<VaultCategory>,
    selectedCount: Int,
    onDismiss: () -> Unit,
    onMove: (String?) -> Unit,
) {
    var newCategory by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                tr(
                    "Move $selectedCount entries",
                    "Mover $selectedCount entradas",
                ),
            )
        },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    ListItem(
                        headlineContent = { Text(tr("Uncategorized", "Sin categoría")) },
                        modifier = Modifier.clickable { onMove(null) },
                    )
                }
                items(categories.sortedBy { it.name.lowercase() }, key = VaultCategory::id) { category ->
                    ListItem(
                        headlineContent = { Text(category.name) },
                        supportingContent = {
                            if (category.triggers.isNotEmpty()) {
                                Text(category.triggers.joinToString(" · "))
                            }
                        },
                        modifier = Modifier.clickable { onMove(category.name) },
                    )
                }
                item {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    OutlinedTextField(
                        value = newCategory,
                        onValueChange = { newCategory = it },
                        label = { Text(tr("New category", "Nueva categoría")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { onMove(newCategory.trim()) },
                        enabled = newCategory.isNotBlank(),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(tr("Create and move", "Crear y mover"))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Cancel", "Cancelar")) }
        },
    )
}

private data class VaultTriggerRow(
    val trigger: String,
    val owner: String,
)

@Composable
private fun VaultTriggerManagerDialog(
    entries: List<VaultEntry>,
    categories: List<VaultCategory>,
    onDismiss: () -> Unit,
    onMove: (Set<String>) -> Unit,
) {
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val categoryPrefix = tr("Category", "Categoría")
    val entryPrefix = tr("Entry", "Entrada")
    val rows = buildList {
        categories.forEach { category ->
            category.triggers.forEach { trigger ->
                add(VaultTriggerRow(trigger, "$categoryPrefix: ${category.name}"))
            }
        }
        entries.forEach { entry ->
            entry.triggers.forEach { trigger ->
                add(VaultTriggerRow(trigger, "$entryPrefix: ${entry.title}"))
            }
        }
    }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.trigger })

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Manage triggers", "Administrar triggers")) },
        text = {
            if (rows.isEmpty()) {
                Text(tr("There are no vault triggers yet.", "Todavía no hay triggers en la bóveda."))
            } else {
                Column {
                    if (selected.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                tr(
                                    "${selected.size} selected",
                                    "${selected.size} seleccionados",
                                ),
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.SemiBold,
                            )
                            TextButton(onClick = { onMove(selected) }) {
                                Text(tr("Move selected", "Mover seleccionados"))
                            }
                            TextButton(onClick = { selected = emptySet() }) {
                                Text(tr("Clear", "Limpiar"))
                            }
                        }
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(rows, key = { "${it.owner}::${it.trigger}" }) { row ->
                            val checked = row.trigger in selected
                            ListItem(
                                headlineContent = {
                                    Text(row.trigger, fontWeight = FontWeight.SemiBold)
                                },
                                supportingContent = { Text(row.owner) },
                                leadingContent = {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = {
                                            selected = if (checked) {
                                                selected - row.trigger
                                            } else {
                                                selected + row.trigger
                                            }
                                        },
                                    )
                                },
                                trailingContent = {
                                    TextButton(onClick = { onMove(setOf(row.trigger)) }) {
                                        Text(tr("Move", "Mover"))
                                    }
                                },
                                modifier = Modifier.clickable {
                                    selected = if (checked) {
                                        selected - row.trigger
                                    } else {
                                        selected + row.trigger
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(tr("Close", "Cerrar")) }
        },
    )
}

@Composable
private fun VaultTriggerMoveDialog(
    triggers: Set<String>,
    entries: List<VaultEntry>,
    categories: List<VaultCategory>,
    onDismiss: () -> Unit,
    onMoveToCategory: (Long) -> Unit,
    onMoveToEntry: (Long) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (triggers.size == 1) {
                    val trigger = triggers.first()
                    tr("Move trigger $trigger", "Mover trigger $trigger")
                } else {
                    tr(
                        "Move ${triggers.size} triggers",
                        "Mover ${triggers.size} triggers",
                    )
                },
            )
        },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    Text(
                        tr("Categories", "Categorías"),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
                items(categories.sortedBy { it.name.lowercase() }, key = { "c:${it.id}" }) { category ->
                    ListItem(
                        headlineContent = { Text(category.name) },
                        modifier = Modifier.clickable { onMoveToCategory(category.id) },
                    )
                }
                item {
                    Text(
                        tr("Entries", "Entradas"),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
                items(entries.sortedBy { it.title.lowercase() }, key = { "e:${it.id}" }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.title) },
                        supportingContent = {
                            entry.category?.let { Text(it) }
                        },
                        modifier = Modifier.clickable { onMoveToEntry(entry.id) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Cancel", "Cancelar")) }
        },
    )
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
                    val metadata = buildList {
                        entry.category?.takeIf(String::isNotBlank)?.let(::add)
                        if (entry.triggers.isNotEmpty()) add(entry.triggers.joinToString(" · "))
                    }.joinToString(" · ")
                    if (metadata.isNotBlank()) {
                        Text(
                            metadata,
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
    presetCategory: String? = null,
    onDismiss: () -> Unit,
    onSave: (VaultEntry) -> Unit,
) {
    var title by remember(initial?.id) { mutableStateOf(initial?.title.orEmpty()) }
    var triggerText by remember(initial?.id) {
        mutableStateOf(initial?.triggers?.joinToString("\n").orEmpty())
    }
    var caseSensitive by remember(initial?.id) {
        mutableStateOf(initial?.caseSensitive ?: false)
    }
    var category by remember(initial?.id, presetCategory) {
        mutableStateOf(initial?.category ?: presetCategory.orEmpty())
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("Match letter case exactly", "Distinguir mayúsculas y minúsculas"))
                            Text(
                                tr(
                                    "Off: bb also matches BB, Bb and bB.",
                                    "Desactivado: bb también reconoce BB, Bb y bB.",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = caseSensitive,
                            onCheckedChange = { caseSensitive = it },
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = category,
                        onValueChange = { category = it },
                        label = { Text(tr("Category", "Categoría")) },
                        supportingText = {
                            Text(
                                tr(
                                    "Entries with the same category are grouped together.",
                                    "Las entradas con la misma categoría se agrupan juntas.",
                                ),
                            )
                        },
                        singleLine = true,
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
                            caseSensitive = caseSensitive,
                            fields = fields,
                            category = category,
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

@Composable
private fun VaultCategoryEditorDialog(
    initial: VaultCategory,
    onDismiss: () -> Unit,
    onSave: (VaultCategory) -> Unit,
    onDelete: (VaultCategory) -> Unit,
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var triggerText by remember(initial.id) {
        mutableStateOf(initial.triggers.joinToString("\n"))
    }
    var caseSensitive by remember(initial.id) {
        mutableStateOf(initial.caseSensitive)
    }
    val valid = name.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initial.id == 0L) tr("New category", "Nueva categoría")
                else tr("Edit category", "Editar categoría"),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(tr("Category name", "Nombre de la categoría")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = triggerText,
                    onValueChange = { triggerText = it },
                    label = {
                        Text(
                            tr(
                                "Category triggers — one per line",
                                "Triggers de categoría — uno por línea",
                            ),
                        )
                    },
                    supportingText = {
                        Text(
                            tr(
                                "A category trigger opens this category directly.",
                                "Un trigger de categoría abre directamente esta categoría.",
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Match letter case exactly", "Distinguir mayúsculas y minúsculas"))
                        Text(
                            tr(
                                "Off: the trigger ignores capitalization.",
                                "Desactivado: el trigger ignora las mayúsculas.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = caseSensitive,
                        onCheckedChange = { caseSensitive = it },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    onSave(
                        initial.copy(
                            name = name,
                            triggers = triggerText
                                .split("\n")
                                .filter(String::isNotBlank)
                                .distinct(),
                            caseSensitive = caseSensitive,
                        ),
                    )
                },
            ) {
                Text(tr("Save", "Guardar"))
            }
        },
        dismissButton = {
            Row {
                if (initial.id != 0L) {
                    TextButton(onClick = { onDelete(initial) }) {
                        Icon(Icons.Default.Delete, null)
                        Text(tr("Delete category", "Eliminar categoría"))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(tr("Cancel", "Cancelar"))
                }
            }
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
