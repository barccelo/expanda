package dev.diego.expanda

import android.content.Intent
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.Button
import dev.diego.expanda.service.SideloadAccess
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.diego.expanda.data.EspansoSourceFile
import dev.diego.expanda.service.AccessibilityStatus
import dev.diego.expanda.ui.ExpandaApp
import dev.diego.expanda.ui.AccessibilitySetupHelpDialog
import dev.diego.expanda.ui.ImportKind
import dev.diego.expanda.ui.LocalDisplayLanguage
import dev.diego.expanda.ui.tr
import dev.diego.expanda.ui.uiText
import dev.diego.expanda.ui.MainViewModel
import dev.diego.expanda.ui.PreparedImport
import dev.diego.expanda.ui.theme.ExpandaTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private enum class ExportKind { BACKUP, CSV, ESPANSO }

    private val viewModel: MainViewModel by viewModels()
    private val openNewSnippetRequest = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the app window anchored when the IME opens. Without an explicit
        // resize policy Android may pan the whole activity to reveal the focused
        // field, which also drags Scaffold bottom bars far above the keyboard.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        openNewSnippetRequest.value = intent.getBooleanExtra(dev.diego.expanda.service.ExpansionAccessibilityService.EXTRA_OPEN_NEW_SNIPPET, false)
        setContent { ExpandaRoot(viewModel) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(dev.diego.expanda.service.ExpansionAccessibilityService.EXTRA_OPEN_NEW_SNIPPET, false)) {
            openNewSnippetRequest.value = true
        }
    }

    @Composable
    private fun ExpandaRoot(viewModel: MainViewModel) {
        val state by viewModel.uiState.collectAsState()
        val espansoSourceFiles by viewModel.sourceFiles.collectAsState()
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        var serviceEnabled = remember { androidx.compose.runtime.mutableStateOf(AccessibilityStatus.isEnabled(this)) }
        var backgroundAllowed by remember { mutableStateOf(isBackgroundOperationAllowed()) }
        val needsRestrictedSettings = SideloadAccess.needsRestrictedSettingsGuidance(
            this,
            serviceEnabled.value,
            state.settings.restrictedSettingsHintDismissed,
        )
        var accessibilitySetupPending by remember { mutableStateOf(false) }
        var showAccessibilitySetupHelp by remember { mutableStateOf(false) }
        var exportKind by remember { mutableStateOf(ExportKind.BACKUP) }
        var exactEspansoExport by remember { mutableStateOf<EspansoSourceFile?>(null) }
        var chooseEspansoExport by remember { mutableStateOf(false) }
        var pendingImport by remember { mutableStateOf<PreparedImport?>(null) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            serviceEnabled.value = AccessibilityStatus.isEnabled(this)
            backgroundAllowed = isBackgroundOperationAllowed()
            if (accessibilitySetupPending && !serviceEnabled.value && needsRestrictedSettings) {
                showAccessibilitySetupHelp = true
            }
            accessibilitySetupPending = false
            viewModel.refreshEspansoFolderSilently()
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val app = application as ExpandaApplication
            app.clipboardMonitor.capture(clipboard)
            if (state.settings.clipboardHistoryEnabled) {
                clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)
                    ?.toString()?.takeIf(String::isNotBlank)?.let(viewModel::captureClipboard)
            }
        }

        val exportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri ->
            if (uri != null) scope.launch {
                var exportIssues = 0
                runCatching {
                    val output = when (exportKind) {
                        ExportKind.BACKUP -> viewModel.exportBackup()
                        ExportKind.CSV -> viewModel.exportCsv()
                        ExportKind.ESPANSO -> exactEspansoExport?.content ?: viewModel.exportEspanso().also {
                            exportIssues = it.issues.size
                        }.yaml
                    }
                    contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter(Charsets.UTF_8).use {
                        it.write(output)
                    }
                }.onSuccess {
                    snackbar.showSnackbar(
                        if (exportIssues == 0) "Exported successfully"
                        else "Exported with $exportIssues compatibility warnings",
                    )
                }
                    .onFailure { snackbar.showSnackbar(uiText("Export failed: ${it.message}", "Error de exportación: ${it.message}")) }
            }
        }
        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) scope.launch {
                val sourceName = contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: "import.yml"
                runCatching {
                    contentResolver.openInputStream(uri)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
                    .onSuccess { data ->
                        viewModel.prepareImport(data, sourceName)
                            .onSuccess { pendingImport = it }
                            .onFailure { scope.launch { snackbar.showSnackbar(uiText("Import failed: ${it.message}", "Error de importación: ${it.message}")) } }
                    }.onFailure { snackbar.showSnackbar(uiText("Import failed: ${it.message}", "Error de importación: ${it.message}")) }
            }
        }
        val folderLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) scope.launch {
                val permission = runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                if (permission.isFailure) {
                    snackbar.showSnackbar(uiText("Expanda needs lasting read and write access to this folder.", "Expanda necesita acceso permanente de lectura y escritura a esta carpeta."))
                    return@launch
                }
                viewModel.prepareEspansoFolder(uri)
                    .onSuccess { pendingImport = it }
                    .onFailure { snackbar.showSnackbar(uiText("Folder scan failed: ${it.message}", "Error al revisar la carpeta: ${it.message}")) }
            }
        }

        ExpandaTheme(state.settings) {
            CompositionLocalProvider(LocalDisplayLanguage provides state.settings.displayLanguage) {
                ExpandaApp(
                state = state,
                serviceEnabled = serviceEnabled.value,
                backgroundAllowed = backgroundAllowed,
                viewModel = viewModel,
                snackbarHostState = snackbar,
                openNewSnippetRequest = openNewSnippetRequest.value,
                onNewSnippetRequestConsumed = { openNewSnippetRequest.value = false },
                onOpenAccessibilitySettings = {
                    accessibilitySetupPending = true
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                needsRestrictedSettings = needsRestrictedSettings,
                onOpenBackgroundSettings = ::openBackgroundSettings,
                onExportJson = { exportKind = ExportKind.BACKUP; exportLauncher.launch("expanda-backup.json") },
                onExportCsv = { exportKind = ExportKind.CSV; exportLauncher.launch("expanda-matches.csv") },
                onExportEspanso = {
                    exportKind = ExportKind.ESPANSO
                    when (espansoSourceFiles.size) {
                        0 -> {
                            exactEspansoExport = null
                            exportLauncher.launch("expanda.yml")
                        }
                        1 -> {
                            exactEspansoExport = espansoSourceFiles.single()
                            exportLauncher.launch(espansoSourceFiles.single().relativePath.substringAfterLast('/'))
                        }
                        else -> chooseEspansoExport = true
                    }
                },
                // Storage providers disagree on YAML MIME types (and some expose .yml as
                // application/octet-stream). Let the user select the file, then rely on
                // MainViewModel's content-based JSON/YAML/CSV validation.
                onImport = { importLauncher.launch(arrayOf("*/*")) },
                onChooseEspansoFolder = { folderLauncher.launch(state.settings.espansoFolderUri?.let(Uri::parse)) },
                )
                if (showAccessibilitySetupHelp) {
                    AccessibilitySetupHelpDialog(
                        onOpenAppInfo = {
                            showAccessibilitySetupHelp = false
                            openAppDetails()
                        },
                        onOpenAccessibility = {
                            showAccessibilitySetupHelp = false
                            accessibilitySetupPending = true
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onCantEnable = {
                            showAccessibilitySetupHelp = false
                            viewModel.dismissRestrictedSettingsHint()
                        },
                        onDismiss = { showAccessibilitySetupHelp = false },
                    )
                }
                if (chooseEspansoExport) {
                    AlertDialog(
                        onDismissRequest = { chooseEspansoExport = false },
                        title = { Text(tr("Export Espanso source", "Exportar fuente de Espanso")) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(tr("Choose an exact source file. Combined export is portable but cannot preserve comments from several files.", "Elige un archivo fuente exacto. La exportación combinada es portátil, pero no puede conservar comentarios de varios archivos."))
                                espansoSourceFiles.forEach { file ->
                                    TextButton(
                                        onClick = {
                                            chooseEspansoExport = false
                                            exactEspansoExport = file
                                            exportLauncher.launch(file.relativePath.substringAfterLast('/'))
                                        },
                                    ) { Text(file.relativePath) }
                                }
                                TextButton(
                                    onClick = {
                                        chooseEspansoExport = false
                                        exactEspansoExport = null
                                        exportLauncher.launch("expanda-combined.yml")
                                    },
                                ) { Text(tr("Combined file", "Archivo combinado")) }
                            }
                        },
                        confirmButton = {},
                        dismissButton = {
                            TextButton(onClick = { chooseEspansoExport = false }) { Text(tr("Cancel")) }
                        },
                    )
                }
                pendingImport?.let { prepared ->
                    ImportPreviewDialog(
                        prepared = prepared,
                        onDismiss = { pendingImport = null },
                        onImportSnippetsOnly = if (prepared.replacesExistingData) {
                            {
                                pendingImport = null
                                val snippetsOnly = prepared.copy(
                                    kind = ImportKind.SNIPPET_BACKUP,
                                    settings = null,
                                    actions = null,
                                )
                                viewModel.applyImport(snippetsOnly) { result ->
                                    scope.launch {
                                        snackbar.showSnackbar(result.fold(
                                            onSuccess = { uiText("Imported ${it.imported} matches", "Se importaron ${it.imported} coincidencias") },
                                            onFailure = { uiText("Import failed: ${it.message}", "Error de importación: ${it.message}") },
                                        ))
                                    }
                                }
                            }
                        } else null,
                        onConfirm = {
                            pendingImport = null
                            viewModel.applyImport(prepared) { result ->
                                scope.launch {
                                    snackbar.showSnackbar(result.fold(
                                        onSuccess = {
                                            val verb = if (prepared.replacesExistingData) uiText("Restored", "Restauradas") else uiText("Imported", "Importadas")
                                            "$verb ${it.imported} " + uiText("matches", "coincidencias") +
                                                if (it.issues.isEmpty()) "" else
                                                    " " + uiText("with ${it.issues.size} warnings: ${it.issues.first().message}", "con ${it.issues.size} advertencias: ${it.issues.first().message}")
                                        },
                                        onFailure = { uiText("Import failed: ${it.message}", "Error de importación: ${it.message}") },
                                    ))
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun ImportPreviewDialog(
        prepared: PreparedImport,
        onDismiss: () -> Unit,
        onImportSnippetsOnly: (() -> Unit)?,
        onConfirm: () -> Unit,
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    when (prepared.kind) {
                        ImportKind.FULL_BACKUP -> tr("Expanda full backup", "Copia de seguridad completa de Expanda")
                        ImportKind.SNIPPET_BACKUP -> tr("Expanda snippet backup", "Copia de fragmentos de Expanda")
                        ImportKind.ESPANSO -> "Espanso YAML"
                        ImportKind.ESPANSO_FOLDER -> tr("Espanso match folder", "Carpeta de coincidencias de Espanso")
                        ImportKind.CSV -> "CSV"
                    },
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("${prepared.matches.size} matches found", "${prepared.matches.size} coincidencias encontradas"))
                    if (prepared.globalVariables.isNotEmpty()) {
                        Text(tr("${prepared.globalVariables.size} global variables", "${prepared.globalVariables.size} variables globales"))
                    }
                    if (prepared.kind == ImportKind.ESPANSO_FOLDER && prepared.sourceFiles.isEmpty()) {
                        Text(tr("No YAML files were found. This folder will become the source; existing Expanda YAML will be copied into it.", "No se encontraron archivos YAML. Esta carpeta se convertirá en la fuente; el YAML existente de Expanda se copiará allí."))
                    } else if (prepared.kind == ImportKind.ESPANSO_FOLDER) {
                        Text(tr("${prepared.sourceFiles.size} source files found. Expanda will read and edit them in this folder.", "${prepared.sourceFiles.size} archivos fuente encontrados. Expanda los leerá y editará en esta carpeta."))
                    } else if (prepared.replacesExistingData) {
                        Text(tr("This restores snippets, settings, exclusions and actions. Current restorable data will be replaced.", "Esto restaura fragmentos, ajustes, exclusiones y acciones. Los datos restaurables actuales serán reemplazados."))
                    } else {
                        Text(tr("Matches with the same primary trigger will be updated. Other matches stay unchanged.", "Las coincidencias con el mismo disparador principal se actualizarán. Las demás permanecerán sin cambios."))
                    }
                    if (prepared.issues.isNotEmpty()) {
                        Text(tr("${prepared.issues.size} compatibility warnings", "${prepared.issues.size} advertencias de compatibilidad"))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(
                        when {
                            prepared.replacesExistingData -> tr("Restore")
                            prepared.kind == ImportKind.ESPANSO_FOLDER -> tr("Link folder")
                            else -> tr("Import")
                        },
                    )
                }
            },
            dismissButton = {
                Row {
                    if (onImportSnippetsOnly != null) {
                        TextButton(onClick = onImportSnippetsOnly) { Text(tr("Snippets only", "Sólo fragmentos")) }
                    }
                    TextButton(onClick = onDismiss) { Text(tr("Cancel")) }
                }
            },
        )
    }

    private fun openAppDetails() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun isBackgroundOperationAllowed(): Boolean {
        val powerManager = getSystemService(PowerManager::class.java)
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openBackgroundSettings() {
        val packageUri = Uri.parse("package:$packageName")
        val directRequest = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        runCatching { startActivity(directRequest) }
            .recoverCatching { startActivity(fallback) }
            .onFailure { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)) }
    }
}
