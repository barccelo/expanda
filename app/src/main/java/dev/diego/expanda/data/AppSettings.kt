package dev.diego.expanda.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }
enum class ColorSchemeMode { WALLPAPER, DEFAULT, CUSTOM }
enum class DisplayLanguage { SYSTEM, ENGLISH, SPANISH }

data class SelectionActionGroupConfig(
    val label: String,
    val actionOrder: List<String>,
    val enabledActionIds: Set<String>,
    val actionLabels: Map<String, String>,
)

data class AppSettings(
    val expansionEnabled: Boolean = true,
    val consentAccepted: Boolean = false,
    /** User dismissed sideload restricted-settings guidance after trying Accessibility. */
    val restrictedSettingsHintDismissed: Boolean = false,
    val backgroundSetupAcknowledged: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val colorSchemeMode: ColorSchemeMode = ColorSchemeMode.DEFAULT,
    val customColor: Int = 0xFF6750A4.toInt(),
    val textScale: Float = SettingsRepository.DEFAULT_TEXT_SCALE,
    val snippetSortMode: SnippetSortMode = SnippetSortMode.RECENTLY_EDITED,
    val pausedUntil: Long = 0L,
    val globallyExcludedPackages: Set<String> = emptySet(),
    val globalVariables: List<TemplateVariable> = emptyList(),
    val clipboardHistoryEnabled: Boolean = true,
    val statisticsEnabled: Boolean = true,
    val hapticFeedback: Boolean = false,
    val pasteFallbackEnabled: Boolean = false,
    /** One-shot lowercase correction after snippets that place $|$ inside surrounding punctuation. */
    val smartCursorCaseEnabled: Boolean = true,
    val suggestionEnabled: Boolean = true,
    /** Show Expanda's compact toolbar when editable text is selected in another app. */
    val selectionToolbarEnabled: Boolean = true,
    /** Last dragged selection-toolbar position in physical pixels. -1 means automatic placement. */
    val selectionToolbarPositionX: Int = -1,
    val selectionToolbarPositionY: Int = -1,
    /** Selection toolbar size. Width is a display fraction; height is density-independent pixels. */
    val selectionToolbarWidthFraction: Float = SettingsRepository.DEFAULT_SELECTION_TOOLBAR_WIDTH,
    val selectionToolbarHeightDp: Int = SettingsRepository.DEFAULT_SELECTION_TOOLBAR_HEIGHT_DP,
    /** Ordered quick actions shown directly between Undo and the two menu buttons. */
    val selectionToolbarQuickActionIds: List<String> = SettingsRepository.DEFAULT_SELECTION_TOOLBAR_QUICK_ACTIONS,
    /** Remembers whether the trailing toolbar button last showed Close or All tools. */
    val selectionToolbarMoreButtonCloseMode: Boolean = false,
    /** Long-press hotspot over the IME for horizontal text selection. */
    val selectionGestureHotspotEnabled: Boolean = true,
    /** Hotspot geometry as fractions of the current input-method window. */
    val selectionGestureHotspotXFraction: Float = SettingsRepository.DEFAULT_SELECTION_GESTURE_X,
    val selectionGestureHotspotYFraction: Float = SettingsRepository.DEFAULT_SELECTION_GESTURE_Y,
    val selectionGestureHotspotWidthFraction: Float = SettingsRepository.DEFAULT_SELECTION_GESTURE_WIDTH,
    val selectionGestureHotspotHeightFraction: Float = SettingsRepository.DEFAULT_SELECTION_GESTURE_HEIGHT,
    /** Configurable action groups used by compact toolbar buttons such as Case. */
    val selectionActionGroupConfigs: Map<String, SelectionActionGroupConfig> =
        SettingsRepository.DEFAULT_SELECTION_ACTION_GROUP_CONFIGS,
    /** Display language for Expanda Personal surfaces that support localization. */
    val displayLanguage: DisplayLanguage = DisplayLanguage.SYSTEM,
    val suggestionShowActions: Boolean = true,
    /** Include vault categories and entries in floating suggestions. */
    val suggestionShowVault: Boolean = true,
    val matchFromBeginning: Boolean = true,
    /** Keep the suggestion list visually dense when enabled. */
    val suggestionCompactList: Boolean = true,
    /** Maximum height of the scrollable suggestion list, in dp. */
    val suggestionMaxHeightDp: Int = SettingsRepository.DEFAULT_SUGGESTION_HEIGHT_DP,
    /** Fraction of the usable display width occupied by the suggestion popup. */
    val suggestionWidthFraction: Float = SettingsRepository.DEFAULT_SUGGESTION_WIDTH,
    /** Show a bottom-left handle that resizes the popup horizontally and vertically. */
    val suggestionResizeHandleEnabled: Boolean = true,
    /** Number of characters in the current token required before suggestions appear. */
    val suggestionMinChars: Int = 2,
    /** Last drag position of the suggestion window, in physical pixels. -1 means default. */
    val suggestionPositionX: Int = -1,
    val suggestionPositionY: Int = -1,
    /** Bottom edge of the suggestion window, in physical pixels. Keeps the drag handle anchored when its height changes. */
    val suggestionPositionBottom: Int = -1,
    /** Persisted SAF tree grant. Device-local and intentionally excluded from backups. */
    val espansoFolderUri: String? = null,
) {
    val isPaused: Boolean get() = pausedUntil > System.currentTimeMillis()
}

private val Context.settingsDataStore by preferencesDataStore("settings")

class SettingsRepository(context: Context, scope: CoroutineScope) {
    private val store = context.settingsDataStore
    private val loaded = MutableStateFlow(false)

    init {
        scope.launch {
            store.edit { values ->
                if (values[Keys.SELECTION_WRAP_GROUP_MIGRATED] != true) {
                    val current = values[Keys.SELECTION_TOOLBAR_QUICK_ACTIONS]
                        ?.split(SEPARATOR)
                        ?.let(::normalizeToolbarQuickActions)
                        ?: DEFAULT_SELECTION_TOOLBAR_QUICK_ACTIONS
                    val migrated = if (
                        SELECTION_WRAP_GROUP_ID !in current &&
                        current.size < MAX_SELECTION_TOOLBAR_QUICK_ACTIONS
                    ) {
                        current + SELECTION_WRAP_GROUP_ID
                    } else {
                        current
                    }
                    values[Keys.SELECTION_TOOLBAR_QUICK_ACTIONS] =
                        normalizeToolbarQuickActions(migrated).joinToString(SEPARATOR)
                    values[Keys.SELECTION_WRAP_GROUP_MIGRATED] = true
                }
                if (values[Keys.SELECTION_CLIPBOARD_GROUP_MIGRATED] != true) {
                    val current = values[Keys.SELECTION_TOOLBAR_QUICK_ACTIONS]
                        ?.split(SEPARATOR)
                        ?.let(::normalizeToolbarQuickActions)
                        ?: DEFAULT_SELECTION_TOOLBAR_QUICK_ACTIONS
                    val migrated = if (
                        SELECTION_CLIPBOARD_GROUP_ID !in current &&
                        current.size < MAX_SELECTION_TOOLBAR_QUICK_ACTIONS
                    ) {
                        current + SELECTION_CLIPBOARD_GROUP_ID
                    } else {
                        current
                    }
                    values[Keys.SELECTION_TOOLBAR_QUICK_ACTIONS] =
                        normalizeToolbarQuickActions(migrated).joinToString(SEPARATOR)
                    values[Keys.SELECTION_CLIPBOARD_GROUP_MIGRATED] = true
                }
            }
        }
    }

    val settings: StateFlow<AppSettings> = store.data.map { values ->
        AppSettings(
            expansionEnabled = values[Keys.ENABLED] ?: true,
            consentAccepted = values[Keys.CONSENT] ?: false,
            restrictedSettingsHintDismissed = values[Keys.RESTRICTED_SETTINGS_HINT_DISMISSED] ?: false,
            backgroundSetupAcknowledged = values[Keys.BACKGROUND_SETUP_ACKNOWLEDGED] ?: false,
            themeMode = values[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            colorSchemeMode = values[Keys.COLOR_SCHEME]?.let { runCatching { ColorSchemeMode.valueOf(it) }.getOrNull() }
                ?: ColorSchemeMode.DEFAULT,
            customColor = values[Keys.CUSTOM_COLOR] ?: 0xFF6750A4.toInt(),
            textScale = (values[Keys.TEXT_SCALE] ?: legacyTextScale(values[Keys.TEXT_SIZE]))
                .coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE),
            snippetSortMode = values[Keys.SNIPPET_SORT]?.let {
                runCatching { SnippetSortMode.valueOf(it) }.getOrNull()
            } ?: SnippetSortMode.RECENTLY_EDITED,
            pausedUntil = values[Keys.PAUSED_UNTIL] ?: 0L,
            globallyExcludedPackages = values[Keys.EXCLUDED]
                ?.split(SEPARATOR)?.filter(String::isNotBlank)?.toSet().orEmpty(),
            globalVariables = TemplateVariableJsonCodec.decode(values[Keys.GLOBAL_VARIABLES]),
            clipboardHistoryEnabled = values[Keys.CLIPBOARD_HISTORY] ?: true,
            statisticsEnabled = values[Keys.STATISTICS] ?: true,
            hapticFeedback = values[Keys.HAPTIC] ?: false,
            pasteFallbackEnabled = values[Keys.PASTE_FALLBACK] ?: false,
            smartCursorCaseEnabled = values[Keys.SMART_CURSOR_CASE] ?: true,
            suggestionEnabled = values[Keys.SUGGESTIONS] ?: true,
            selectionToolbarEnabled = values[Keys.SELECTION_TOOLBAR] ?: true,
            selectionToolbarPositionX = values[Keys.SELECTION_TOOLBAR_POSITION_X] ?: -1,
            selectionToolbarPositionY = values[Keys.SELECTION_TOOLBAR_POSITION_Y] ?: -1,
            selectionToolbarWidthFraction = (
                values[Keys.SELECTION_TOOLBAR_WIDTH] ?: DEFAULT_SELECTION_TOOLBAR_WIDTH
            ).coerceIn(MIN_SELECTION_TOOLBAR_WIDTH, MAX_SELECTION_TOOLBAR_WIDTH),
            selectionToolbarHeightDp = (
                values[Keys.SELECTION_TOOLBAR_HEIGHT_DP] ?: DEFAULT_SELECTION_TOOLBAR_HEIGHT_DP
            ).coerceIn(MIN_SELECTION_TOOLBAR_HEIGHT_DP, MAX_SELECTION_TOOLBAR_HEIGHT_DP),
            selectionToolbarQuickActionIds = decodeToolbarQuickActions(
                values[Keys.SELECTION_TOOLBAR_QUICK_ACTIONS],
            ),
            selectionToolbarMoreButtonCloseMode =
                values[Keys.SELECTION_TOOLBAR_MORE_CLOSE_MODE] ?: false,
            selectionGestureHotspotEnabled =
                values[Keys.SELECTION_GESTURE_HOTSPOT_ENABLED] ?: true,
            selectionGestureHotspotXFraction = (
                values[Keys.SELECTION_GESTURE_HOTSPOT_X] ?: DEFAULT_SELECTION_GESTURE_X
            ).coerceIn(0f, 1f),
            selectionGestureHotspotYFraction = (
                values[Keys.SELECTION_GESTURE_HOTSPOT_Y] ?: DEFAULT_SELECTION_GESTURE_Y
            ).coerceIn(0f, 1f),
            selectionGestureHotspotWidthFraction = (
                values[Keys.SELECTION_GESTURE_HOTSPOT_WIDTH] ?: DEFAULT_SELECTION_GESTURE_WIDTH
            ).coerceIn(MIN_SELECTION_GESTURE_SIZE, MAX_SELECTION_GESTURE_SIZE),
            selectionGestureHotspotHeightFraction = (
                values[Keys.SELECTION_GESTURE_HOTSPOT_HEIGHT] ?: DEFAULT_SELECTION_GESTURE_HEIGHT
            ).coerceIn(MIN_SELECTION_GESTURE_SIZE, MAX_SELECTION_GESTURE_SIZE),
            selectionActionGroupConfigs = decodeSelectionActionGroupConfigs(
                values[Keys.SELECTION_ACTION_GROUP_CONFIGS],
            ),
            displayLanguage = values[Keys.DISPLAY_LANGUAGE]?.let {
                runCatching { DisplayLanguage.valueOf(it) }.getOrNull()
            } ?: DisplayLanguage.SYSTEM,
            suggestionShowActions = values[Keys.SUGGESTION_SHOW_ACTIONS] ?: true,
            suggestionShowVault = values[Keys.SUGGESTION_SHOW_VAULT] ?: true,
            matchFromBeginning = values[Keys.MATCH_BEGINNING] ?: true,
            suggestionCompactList = values[Keys.SUGGESTION_COMPACT] ?: true,
            suggestionMaxHeightDp = (
                values[Keys.SUGGESTION_MAX_HEIGHT_DP] ?: DEFAULT_SUGGESTION_HEIGHT_DP
            ).coerceIn(MIN_SUGGESTION_HEIGHT_DP, MAX_SUGGESTION_HEIGHT_DP),
            suggestionWidthFraction = (values[Keys.SUGGESTION_WIDTH] ?: DEFAULT_SUGGESTION_WIDTH)
                .coerceIn(MIN_SUGGESTION_WIDTH, MAX_SUGGESTION_WIDTH),
            suggestionResizeHandleEnabled = values[Keys.SUGGESTION_RESIZE_HANDLE] ?: true,
            suggestionMinChars = (values[Keys.SUGGESTION_MIN_CHARS] ?: 2).coerceIn(1, 32),
            suggestionPositionX = values[Keys.SUGGESTION_POSITION_X] ?: -1,
            suggestionPositionY = values[Keys.SUGGESTION_POSITION_Y] ?: -1,
            suggestionPositionBottom = values[Keys.SUGGESTION_POSITION_BOTTOM] ?: -1,
            espansoFolderUri = values[Keys.ESPANSO_FOLDER_URI]?.takeIf(String::isNotBlank),
        )
    }.onEach { loaded.value = true }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun awaitLoaded() {
        loaded.filter { it }.first()
    }

    suspend fun setEnabled(enabled: Boolean) = store.edit { it[Keys.ENABLED] = enabled }
    suspend fun acceptConsent() = store.edit { it[Keys.CONSENT] = true }
    suspend fun dismissRestrictedSettingsHint() = store.edit {
        it[Keys.RESTRICTED_SETTINGS_HINT_DISMISSED] = true
    }
    suspend fun acknowledgeBackgroundSetup() = store.edit { it[Keys.BACKGROUND_SETUP_ACKNOWLEDGED] = true }
    suspend fun setTheme(mode: ThemeMode) = store.edit { it[Keys.THEME] = mode.name }
    suspend fun setColorScheme(mode: ColorSchemeMode) = store.edit { it[Keys.COLOR_SCHEME] = mode.name }
    suspend fun setCustomColor(color: Int) = store.edit { it[Keys.CUSTOM_COLOR] = color }
    suspend fun setTextScale(scale: Float) = store.edit {
        it[Keys.TEXT_SCALE] = scale.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE)
        it.remove(Keys.TEXT_SIZE)
    }
    suspend fun setSnippetSort(mode: SnippetSortMode) = store.edit { it[Keys.SNIPPET_SORT] = mode.name }
    suspend fun pauseFor(durationMillis: Long) = store.edit {
        it[Keys.PAUSED_UNTIL] = System.currentTimeMillis() + durationMillis
    }
    suspend fun resume() = store.edit { it[Keys.PAUSED_UNTIL] = 0L }
    suspend fun setExcludedPackages(packages: Set<String>) = store.edit {
        it[Keys.EXCLUDED] = packages.sorted().joinToString(SEPARATOR)
    }
    suspend fun setGlobalVariables(variables: List<TemplateVariable>) = store.edit {
        it[Keys.GLOBAL_VARIABLES] = TemplateVariableJsonCodec.encode(variables)
    }
    suspend fun setClipboardHistoryEnabled(enabled: Boolean) = store.edit { it[Keys.CLIPBOARD_HISTORY] = enabled }
    suspend fun setStatisticsEnabled(enabled: Boolean) = store.edit { it[Keys.STATISTICS] = enabled }
    suspend fun setHapticFeedback(enabled: Boolean) = store.edit { it[Keys.HAPTIC] = enabled }
    suspend fun setPasteFallbackEnabled(enabled: Boolean) = store.edit { it[Keys.PASTE_FALLBACK] = enabled }
    suspend fun setSmartCursorCaseEnabled(enabled: Boolean) = store.edit { it[Keys.SMART_CURSOR_CASE] = enabled }
    suspend fun setSuggestionEnabled(enabled: Boolean) = store.edit { it[Keys.SUGGESTIONS] = enabled }
    suspend fun setSelectionToolbarEnabled(enabled: Boolean) = store.edit { it[Keys.SELECTION_TOOLBAR] = enabled }
    suspend fun setSelectionToolbarPosition(x: Int, y: Int) = store.edit {
        it[Keys.SELECTION_TOOLBAR_POSITION_X] = x.coerceAtLeast(0)
        it[Keys.SELECTION_TOOLBAR_POSITION_Y] = y.coerceAtLeast(0)
    }
    suspend fun setSelectionToolbarSize(widthFraction: Float, heightDp: Int) = store.edit {
        it[Keys.SELECTION_TOOLBAR_WIDTH] =
            widthFraction.coerceIn(MIN_SELECTION_TOOLBAR_WIDTH, MAX_SELECTION_TOOLBAR_WIDTH)
        it[Keys.SELECTION_TOOLBAR_HEIGHT_DP] =
            heightDp.coerceIn(MIN_SELECTION_TOOLBAR_HEIGHT_DP, MAX_SELECTION_TOOLBAR_HEIGHT_DP)
    }
    suspend fun setSelectionToolbarWidthFraction(widthFraction: Float) = store.edit {
        it[Keys.SELECTION_TOOLBAR_WIDTH] =
            widthFraction.coerceIn(MIN_SELECTION_TOOLBAR_WIDTH, MAX_SELECTION_TOOLBAR_WIDTH)
    }
    suspend fun setSelectionToolbarHeightDp(heightDp: Int) = store.edit {
        it[Keys.SELECTION_TOOLBAR_HEIGHT_DP] =
            heightDp.coerceIn(MIN_SELECTION_TOOLBAR_HEIGHT_DP, MAX_SELECTION_TOOLBAR_HEIGHT_DP)
    }
    suspend fun resetSelectionToolbarLayout() = store.edit {
        it.remove(Keys.SELECTION_TOOLBAR_POSITION_X)
        it.remove(Keys.SELECTION_TOOLBAR_POSITION_Y)
        it.remove(Keys.SELECTION_TOOLBAR_WIDTH)
        it.remove(Keys.SELECTION_TOOLBAR_HEIGHT_DP)
    }
    suspend fun setSelectionToolbarQuickActionIds(ids: List<String>) = store.edit {
        val normalized = normalizeToolbarQuickActions(ids)
        it[Keys.SELECTION_TOOLBAR_QUICK_ACTIONS] = normalized.joinToString(SEPARATOR)
    }
    suspend fun setSelectionToolbarMoreButtonCloseMode(closeMode: Boolean) = store.edit {
        it[Keys.SELECTION_TOOLBAR_MORE_CLOSE_MODE] = closeMode
    }
    suspend fun setSelectionGestureHotspotEnabled(enabled: Boolean) = store.edit {
        it[Keys.SELECTION_GESTURE_HOTSPOT_ENABLED] = enabled
    }
    suspend fun setSelectionGestureHotspotLayout(
        xFraction: Float,
        yFraction: Float,
        widthFraction: Float,
        heightFraction: Float,
    ) = store.edit {
        it[Keys.SELECTION_GESTURE_HOTSPOT_X] = xFraction.coerceIn(0f, 1f)
        it[Keys.SELECTION_GESTURE_HOTSPOT_Y] = yFraction.coerceIn(0f, 1f)
        it[Keys.SELECTION_GESTURE_HOTSPOT_WIDTH] =
            widthFraction.coerceIn(MIN_SELECTION_GESTURE_SIZE, MAX_SELECTION_GESTURE_SIZE)
        it[Keys.SELECTION_GESTURE_HOTSPOT_HEIGHT] =
            heightFraction.coerceIn(MIN_SELECTION_GESTURE_SIZE, MAX_SELECTION_GESTURE_SIZE)
    }
    suspend fun setSelectionActionGroupConfig(
        groupId: String,
        config: SelectionActionGroupConfig,
    ) = store.edit { values ->
        val normalized = normalizeSelectionActionGroupConfig(groupId, config) ?: return@edit
        val groups = decodeSelectionActionGroupConfigs(values[Keys.SELECTION_ACTION_GROUP_CONFIGS])
            .toMutableMap()
        groups[groupId] = normalized
        values[Keys.SELECTION_ACTION_GROUP_CONFIGS] = encodeSelectionActionGroupConfigs(groups)
    }
    suspend fun resetSelectionActionGroupConfig(groupId: String) = store.edit { values ->
        val defaultConfig = DEFAULT_SELECTION_ACTION_GROUP_CONFIGS[groupId] ?: return@edit
        val groups = decodeSelectionActionGroupConfigs(values[Keys.SELECTION_ACTION_GROUP_CONFIGS])
            .toMutableMap()
        groups[groupId] = defaultConfig
        values[Keys.SELECTION_ACTION_GROUP_CONFIGS] = encodeSelectionActionGroupConfigs(groups)
    }
    suspend fun setDisplayLanguage(language: DisplayLanguage) = store.edit {
        it[Keys.DISPLAY_LANGUAGE] = language.name
    }
    suspend fun setSuggestionShowActions(enabled: Boolean) = store.edit { it[Keys.SUGGESTION_SHOW_ACTIONS] = enabled }
    suspend fun setSuggestionShowVault(enabled: Boolean) = store.edit { it[Keys.SUGGESTION_SHOW_VAULT] = enabled }
    suspend fun setMatchFromBeginning(enabled: Boolean) = store.edit { it[Keys.MATCH_BEGINNING] = enabled }
    suspend fun setSuggestionCompactList(enabled: Boolean) = store.edit {
        it[Keys.SUGGESTION_COMPACT] = enabled
    }
    suspend fun setSuggestionMaxHeightDp(heightDp: Int) = store.edit {
        it[Keys.SUGGESTION_MAX_HEIGHT_DP] = heightDp.coerceIn(
            MIN_SUGGESTION_HEIGHT_DP,
            MAX_SUGGESTION_HEIGHT_DP,
        )
    }
    suspend fun setSuggestionWidthFraction(fraction: Float) = store.edit {
        it[Keys.SUGGESTION_WIDTH] = fraction.coerceIn(MIN_SUGGESTION_WIDTH, MAX_SUGGESTION_WIDTH)
    }
    suspend fun setSuggestionResizeHandleEnabled(enabled: Boolean) = store.edit {
        it[Keys.SUGGESTION_RESIZE_HANDLE] = enabled
    }
    suspend fun setSuggestionMinChars(minChars: Int) = store.edit {
        it[Keys.SUGGESTION_MIN_CHARS] = minChars.coerceIn(1, 32)
    }
    suspend fun setEspansoFolderUri(uri: String?) = store.edit {
        if (uri.isNullOrBlank()) it.remove(Keys.ESPANSO_FOLDER_URI)
        else it[Keys.ESPANSO_FOLDER_URI] = uri
    }
    suspend fun setSuggestionPosition(x: Int, y: Int, height: Int) = store.edit {
        it[Keys.SUGGESTION_POSITION_X] = x.coerceAtLeast(0)
        it[Keys.SUGGESTION_POSITION_Y] = y.coerceAtLeast(0)
        it[Keys.SUGGESTION_POSITION_BOTTOM] = (y + height).coerceAtLeast(0)
    }
    suspend fun setSuggestionLayout(
        x: Int,
        y: Int,
        height: Int,
        widthFraction: Float,
        maxHeightDp: Int,
    ) = store.edit {
        it[Keys.SUGGESTION_POSITION_X] = x.coerceAtLeast(0)
        it[Keys.SUGGESTION_POSITION_Y] = y.coerceAtLeast(0)
        it[Keys.SUGGESTION_POSITION_BOTTOM] = (y + height).coerceAtLeast(0)
        it[Keys.SUGGESTION_WIDTH] = widthFraction.coerceIn(MIN_SUGGESTION_WIDTH, MAX_SUGGESTION_WIDTH)
        it[Keys.SUGGESTION_MAX_HEIGHT_DP] = maxHeightDp.coerceIn(
            MIN_SUGGESTION_HEIGHT_DP,
            MAX_SUGGESTION_HEIGHT_DP,
        )
    }

    private object Keys {
        val ENABLED = booleanPreferencesKey("expansion_enabled")
        val CONSENT = booleanPreferencesKey("accessibility_consent")
        val RESTRICTED_SETTINGS_HINT_DISMISSED = booleanPreferencesKey("restricted_settings_hint_dismissed")
        val BACKGROUND_SETUP_ACKNOWLEDGED = booleanPreferencesKey("background_setup_acknowledged")
        val THEME = stringPreferencesKey("theme")
        val COLOR_SCHEME = stringPreferencesKey("color_scheme")
        val CUSTOM_COLOR = intPreferencesKey("custom_color")
        val TEXT_SIZE = stringPreferencesKey("text_size")
        val TEXT_SCALE = floatPreferencesKey("text_scale")
        val SNIPPET_SORT = stringPreferencesKey("snippet_sort")
        val PAUSED_UNTIL = longPreferencesKey("paused_until")
        val EXCLUDED = stringPreferencesKey("globally_excluded_packages")
        val GLOBAL_VARIABLES = stringPreferencesKey("global_variables")
        val CLIPBOARD_HISTORY = booleanPreferencesKey("clipboard_history")
        val STATISTICS = booleanPreferencesKey("statistics")
        val HAPTIC = booleanPreferencesKey("haptic_feedback")
        val PASTE_FALLBACK = booleanPreferencesKey("paste_fallback")
        val SMART_CURSOR_CASE = booleanPreferencesKey("smart_cursor_case")
        val SUGGESTIONS = booleanPreferencesKey("suggestions")
        val SELECTION_TOOLBAR = booleanPreferencesKey("selection_toolbar")
        val SELECTION_TOOLBAR_POSITION_X = intPreferencesKey("selection_toolbar_position_x")
        val SELECTION_TOOLBAR_POSITION_Y = intPreferencesKey("selection_toolbar_position_y")
        val SELECTION_TOOLBAR_WIDTH = floatPreferencesKey("selection_toolbar_width_fraction")
        val SELECTION_TOOLBAR_HEIGHT_DP = intPreferencesKey("selection_toolbar_height_dp")
        val SELECTION_TOOLBAR_QUICK_ACTIONS = stringPreferencesKey("selection_toolbar_quick_actions")
        val SELECTION_TOOLBAR_MORE_CLOSE_MODE =
            booleanPreferencesKey("selection_toolbar_more_close_mode")
        val SELECTION_GESTURE_HOTSPOT_ENABLED = booleanPreferencesKey("selection_gesture_hotspot_enabled")
        val SELECTION_GESTURE_HOTSPOT_X = floatPreferencesKey("selection_gesture_hotspot_x")
        val SELECTION_GESTURE_HOTSPOT_Y = floatPreferencesKey("selection_gesture_hotspot_y")
        val SELECTION_GESTURE_HOTSPOT_WIDTH = floatPreferencesKey("selection_gesture_hotspot_width")
        val SELECTION_GESTURE_HOTSPOT_HEIGHT = floatPreferencesKey("selection_gesture_hotspot_height")
        val SELECTION_ACTION_GROUP_CONFIGS = stringPreferencesKey("selection_action_group_configs")
        val SELECTION_WRAP_GROUP_MIGRATED = booleanPreferencesKey("selection_wrap_group_migrated")
        val SELECTION_CLIPBOARD_GROUP_MIGRATED =
            booleanPreferencesKey("selection_clipboard_group_migrated")
        val DISPLAY_LANGUAGE = stringPreferencesKey("display_language")
        val SUGGESTION_SHOW_ACTIONS = booleanPreferencesKey("suggestion_show_actions")
        val SUGGESTION_SHOW_VAULT = booleanPreferencesKey("suggestion_show_vault")
        val MATCH_BEGINNING = booleanPreferencesKey("match_beginning")
        val SUGGESTION_COMPACT = booleanPreferencesKey("suggestion_compact_list")
        val SUGGESTION_MAX_HEIGHT_DP = intPreferencesKey("suggestion_max_height_dp")
        val SUGGESTION_WIDTH = floatPreferencesKey("suggestion_width_fraction")
        val SUGGESTION_RESIZE_HANDLE = booleanPreferencesKey("suggestion_resize_handle")
        val SUGGESTION_MIN_CHARS = intPreferencesKey("suggestion_min_chars")
        val SUGGESTION_POSITION_X = intPreferencesKey("suggestion_position_x")
        val SUGGESTION_POSITION_Y = intPreferencesKey("suggestion_position_y")
        val SUGGESTION_POSITION_BOTTOM = intPreferencesKey("suggestion_position_bottom")
        val ESPANSO_FOLDER_URI = stringPreferencesKey("espanso_folder_uri")
    }

    suspend fun restore(snapshot: AppSettings) = store.edit { values ->
        values[Keys.ENABLED] = snapshot.expansionEnabled
        values[Keys.THEME] = snapshot.themeMode.name
        values[Keys.COLOR_SCHEME] = snapshot.colorSchemeMode.name
        values[Keys.CUSTOM_COLOR] = snapshot.customColor
        values[Keys.TEXT_SCALE] = snapshot.textScale.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE)
        values.remove(Keys.TEXT_SIZE)
        values[Keys.SNIPPET_SORT] = snapshot.snippetSortMode.name
        values[Keys.EXCLUDED] = snapshot.globallyExcludedPackages.sorted().joinToString(SEPARATOR)
        values[Keys.GLOBAL_VARIABLES] = TemplateVariableJsonCodec.encode(snapshot.globalVariables)
        values[Keys.CLIPBOARD_HISTORY] = snapshot.clipboardHistoryEnabled
        values[Keys.STATISTICS] = snapshot.statisticsEnabled
        values[Keys.HAPTIC] = snapshot.hapticFeedback
        values[Keys.PASTE_FALLBACK] = snapshot.pasteFallbackEnabled
        values[Keys.SMART_CURSOR_CASE] = snapshot.smartCursorCaseEnabled
        values[Keys.SUGGESTIONS] = snapshot.suggestionEnabled
        values[Keys.SELECTION_TOOLBAR] = snapshot.selectionToolbarEnabled
        values[Keys.SELECTION_TOOLBAR_WIDTH] = snapshot.selectionToolbarWidthFraction
            .coerceIn(MIN_SELECTION_TOOLBAR_WIDTH, MAX_SELECTION_TOOLBAR_WIDTH)
        values[Keys.SELECTION_TOOLBAR_HEIGHT_DP] = snapshot.selectionToolbarHeightDp
            .coerceIn(MIN_SELECTION_TOOLBAR_HEIGHT_DP, MAX_SELECTION_TOOLBAR_HEIGHT_DP)
        values[Keys.SELECTION_TOOLBAR_QUICK_ACTIONS] =
            normalizeToolbarQuickActions(snapshot.selectionToolbarQuickActionIds)
                .joinToString(SEPARATOR)
        values[Keys.SELECTION_TOOLBAR_MORE_CLOSE_MODE] =
            snapshot.selectionToolbarMoreButtonCloseMode
        values[Keys.SELECTION_GESTURE_HOTSPOT_ENABLED] = snapshot.selectionGestureHotspotEnabled
        values[Keys.SELECTION_ACTION_GROUP_CONFIGS] =
            encodeSelectionActionGroupConfigs(snapshot.selectionActionGroupConfigs)
        values[Keys.DISPLAY_LANGUAGE] = snapshot.displayLanguage.name
        values[Keys.SUGGESTION_SHOW_ACTIONS] = snapshot.suggestionShowActions
        values[Keys.SUGGESTION_SHOW_VAULT] = snapshot.suggestionShowVault
        values[Keys.MATCH_BEGINNING] = snapshot.matchFromBeginning
        values[Keys.SUGGESTION_COMPACT] = snapshot.suggestionCompactList
        values[Keys.SUGGESTION_MAX_HEIGHT_DP] = snapshot.suggestionMaxHeightDp.coerceIn(
            MIN_SUGGESTION_HEIGHT_DP,
            MAX_SUGGESTION_HEIGHT_DP,
        )
        values[Keys.SUGGESTION_MIN_CHARS] = snapshot.suggestionMinChars.coerceIn(1, 32)
        values[Keys.SUGGESTION_WIDTH] = snapshot.suggestionWidthFraction
            .coerceIn(MIN_SUGGESTION_WIDTH, MAX_SUGGESTION_WIDTH)
        values[Keys.SUGGESTION_RESIZE_HANDLE] = snapshot.suggestionResizeHandleEnabled
        // Consent, pause state, battery setup and physical popup coordinates belong to this device.
    }

    suspend fun reset() = store.edit { it.clear() }

    companion object {
        private const val SEPARATOR = "\u001F"
        const val MIN_TEXT_SCALE = 0.75f
        const val MAX_TEXT_SCALE = 1.50f
        const val DEFAULT_TEXT_SCALE = 1f
        const val MIN_SELECTION_TOOLBAR_WIDTH = 0.42f
        const val MAX_SELECTION_TOOLBAR_WIDTH = 0.98f
        const val DEFAULT_SELECTION_TOOLBAR_WIDTH = 0.60f
        const val MIN_SELECTION_TOOLBAR_HEIGHT_DP = 48
        const val MAX_SELECTION_TOOLBAR_HEIGHT_DP = 88
        const val DEFAULT_SELECTION_TOOLBAR_HEIGHT_DP = 56
        const val MAX_SELECTION_TOOLBAR_QUICK_ACTIONS = 6
        const val DEFAULT_SELECTION_GESTURE_X = 0.015f
        const val DEFAULT_SELECTION_GESTURE_Y = 0.63f
        const val DEFAULT_SELECTION_GESTURE_WIDTH = 0.16f
        const val DEFAULT_SELECTION_GESTURE_HEIGHT = 0.19f
        const val MIN_SELECTION_GESTURE_SIZE = 0.08f
        const val MAX_SELECTION_GESTURE_SIZE = 0.30f
        const val MAX_SELECTION_GROUP_LABEL_LENGTH = 12
        const val SELECTION_CASE_GROUP_ID = "case_group"
        const val SELECTION_WRAP_GROUP_ID = "wrap_group"
        const val SELECTION_CLIPBOARD_GROUP_ID = "clipboard_group"
        val DEFAULT_SELECTION_CASE_GROUP_CONFIG = SelectionActionGroupConfig(
            label = "AaA",
            actionOrder = listOf(
                "lowercase",
                "sentence_case",
                "uppercase",
                "title_case",
            ),
            enabledActionIds = setOf(
                "lowercase",
                "sentence_case",
                "uppercase",
                "title_case",
            ),
            actionLabels = mapOf(
                "lowercase" to "abc",
                "sentence_case" to "Abc.",
                "uppercase" to "ABC",
                "title_case" to "AaA",
            ),
        )
        val DEFAULT_SELECTION_WRAP_GROUP_CONFIG = SelectionActionGroupConfig(
            label = "«»",
            actionOrder = listOf(
                "wrap_guillemets",
                "wrap_parentheses",
                "wrap_question",
                "wrap_exclamation",
                "wrap_brackets",
                "wrap_double_asterisk",
                "wrap_double_underscore",
            ),
            enabledActionIds = setOf(
                "wrap_guillemets",
                "wrap_parentheses",
                "wrap_question",
                "wrap_exclamation",
                "wrap_brackets",
                "wrap_double_asterisk",
                "wrap_double_underscore",
            ),
            actionLabels = mapOf(
                "wrap_guillemets" to "« »",
                "wrap_parentheses" to "( )",
                "wrap_question" to "¿ ?",
                "wrap_exclamation" to "¡ !",
                "wrap_brackets" to "[ ]",
                "wrap_double_asterisk" to "* *",
                "wrap_double_underscore" to "__ __",
            ),
        )
        val DEFAULT_SELECTION_CLIPBOARD_GROUP_CONFIG = SelectionActionGroupConfig(
            label = "⧉",
            actionOrder = listOf(
                "clipboard_cut",
                "clipboard_copy",
                "clipboard_paste",
            ),
            enabledActionIds = setOf(
                "clipboard_cut",
                "clipboard_copy",
                "clipboard_paste",
            ),
            actionLabels = mapOf(
                "clipboard_cut" to "✂",
                "clipboard_copy" to "⧉",
                "clipboard_paste" to "▣",
            ),
        )
        val DEFAULT_SELECTION_ACTION_GROUP_CONFIGS = mapOf(
            SELECTION_CASE_GROUP_ID to DEFAULT_SELECTION_CASE_GROUP_CONFIG,
            SELECTION_WRAP_GROUP_ID to DEFAULT_SELECTION_WRAP_GROUP_CONFIG,
            SELECTION_CLIPBOARD_GROUP_ID to DEFAULT_SELECTION_CLIPBOARD_GROUP_CONFIG,
        )
        val AVAILABLE_SELECTION_ACTION_GROUP_ACTIONS = mapOf(
            SELECTION_CASE_GROUP_ID to DEFAULT_SELECTION_CASE_GROUP_CONFIG.actionOrder,
            SELECTION_WRAP_GROUP_ID to DEFAULT_SELECTION_WRAP_GROUP_CONFIG.actionOrder,
            SELECTION_CLIPBOARD_GROUP_ID to DEFAULT_SELECTION_CLIPBOARD_GROUP_CONFIG.actionOrder,
        )
        private val LEGACY_SELECTION_CASE_ACTION_IDS = setOf(
            "uppercase",
            "lowercase",
            "sentence_case",
            "title_case",
        )
        val DEFAULT_SELECTION_TOOLBAR_QUICK_ACTIONS =
            listOf(
                SELECTION_CASE_GROUP_ID,
                SELECTION_WRAP_GROUP_ID,
                SELECTION_CLIPBOARD_GROUP_ID,
            )
        val AVAILABLE_SELECTION_TOOLBAR_QUICK_ACTIONS = listOf(
            SELECTION_CASE_GROUP_ID,
            SELECTION_WRAP_GROUP_ID,
            SELECTION_CLIPBOARD_GROUP_ID,
            "find_replace",
            "sort_lines",
            "text_counter",
            "repeat_text",
            "trim_spaces",
            "remove_all_spaces",
            "delete_blank_lines",
            "remove_duplicate_lines",
            "remove_duplicate_words",
            "remove_line_breaks",
            "prefix_suffix",
            "number_lines",
            "reverse_text",
            "reverse_lines",
            "reverse_words",
            "remove_diacritics",
            "space_underscore",
            "space_dash",
            "underscore_space",
            "dash_space",
            "math_replace",
            "math_append",
            "number_space",
            "number_period",
            "number_comma",
        )
        const val MIN_SUGGESTION_WIDTH = 0.50f
        const val MAX_SUGGESTION_WIDTH = 0.98f
        const val DEFAULT_SUGGESTION_WIDTH = 0.92f
        const val MIN_SUGGESTION_HEIGHT_DP = 120
        const val MAX_SUGGESTION_HEIGHT_DP = 720
        const val DEFAULT_SUGGESTION_HEIGHT_DP = 280

        private fun decodeToolbarQuickActions(raw: String?): List<String> {
            if (raw == null) return DEFAULT_SELECTION_TOOLBAR_QUICK_ACTIONS
            return normalizeToolbarQuickActions(raw.split(SEPARATOR))
        }

        private fun encodeSelectionActionGroupConfigs(
            configs: Map<String, SelectionActionGroupConfig>,
        ): String = JSONObject().apply {
            DEFAULT_SELECTION_ACTION_GROUP_CONFIGS.keys.forEach { groupId ->
                val config = normalizeSelectionActionGroupConfig(
                    groupId,
                    configs[groupId] ?: DEFAULT_SELECTION_ACTION_GROUP_CONFIGS.getValue(groupId),
                ) ?: return@forEach
                put(groupId, JSONObject().apply {
                    put("label", config.label)
                    put("actionOrder", JSONArray(config.actionOrder))
                    put("enabledActionIds", JSONArray(config.enabledActionIds.toList()))
                    put("actionLabels", JSONObject().apply {
                        config.actionLabels.forEach { (actionId, label) -> put(actionId, label) }
                    })
                })
            }
        }.toString()

        private fun decodeSelectionActionGroupConfigs(
            raw: String?,
        ): Map<String, SelectionActionGroupConfig> {
            val root = raw?.takeIf(String::isNotBlank)
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            return DEFAULT_SELECTION_ACTION_GROUP_CONFIGS.mapValues { (groupId, defaultConfig) ->
                val json = root?.optJSONObject(groupId) ?: return@mapValues defaultConfig
                val orderArray = json.optJSONArray("actionOrder")
                val enabledArray = json.optJSONArray("enabledActionIds")
                val labelsJson = json.optJSONObject("actionLabels")
                val order = if (orderArray == null) {
                    defaultConfig.actionOrder
                } else {
                    buildList {
                        for (index in 0 until orderArray.length()) {
                            orderArray.optString(index).takeIf(String::isNotBlank)?.let(::add)
                        }
                    }
                }
                val enabled = if (enabledArray == null) {
                    defaultConfig.enabledActionIds
                } else {
                    buildSet {
                        for (index in 0 until enabledArray.length()) {
                            enabledArray.optString(index).takeIf(String::isNotBlank)?.let(::add)
                        }
                    }
                }
                val labels = buildMap {
                    defaultConfig.actionLabels.forEach(::put)
                    labelsJson?.keys()?.forEach { actionId ->
                        labelsJson.optString(actionId).takeIf(String::isNotBlank)?.let { put(actionId, it) }
                    }
                }
                normalizeSelectionActionGroupConfig(
                    groupId,
                    SelectionActionGroupConfig(
                        label = json.optString("label", defaultConfig.label),
                        actionOrder = order,
                        enabledActionIds = enabled,
                        actionLabels = labels,
                    ),
                ) ?: defaultConfig
            }
        }

        fun normalizeSelectionActionGroupConfig(
            groupId: String,
            config: SelectionActionGroupConfig,
        ): SelectionActionGroupConfig? {
            val available = AVAILABLE_SELECTION_ACTION_GROUP_ACTIONS[groupId] ?: return null
            val defaultConfig = DEFAULT_SELECTION_ACTION_GROUP_CONFIGS[groupId] ?: return null
            val order = buildList {
                config.actionOrder.forEach { id ->
                    if (id in available && id !in this) add(id)
                }
                available.forEach { id -> if (id !in this) add(id) }
            }
            val enabled = config.enabledActionIds.filterTo(linkedSetOf()) { it in available }
            val labels = available.associateWith { actionId ->
                config.actionLabels[actionId]
                    ?.take(MAX_SELECTION_GROUP_LABEL_LENGTH)
                    ?.takeIf(String::isNotBlank)
                    ?: defaultConfig.actionLabels.getValue(actionId)
            }
            val label = config.label
                .take(MAX_SELECTION_GROUP_LABEL_LENGTH)
                .takeIf(String::isNotBlank)
                ?: defaultConfig.label
            return SelectionActionGroupConfig(
                label = label,
                actionOrder = order,
                enabledActionIds = enabled,
                actionLabels = labels,
            )
        }

        private fun normalizeToolbarQuickActions(ids: List<String>): List<String> {
            val normalized = mutableListOf<String>()
            ids.forEach { rawId ->
                val id = if (rawId in LEGACY_SELECTION_CASE_ACTION_IDS) {
                    SELECTION_CASE_GROUP_ID
                } else {
                    rawId
                }
                if (id in AVAILABLE_SELECTION_TOOLBAR_QUICK_ACTIONS && id !in normalized) {
                    normalized += id
                }
            }
            return normalized.take(MAX_SELECTION_TOOLBAR_QUICK_ACTIONS)
        }

        private fun legacyTextScale(mode: String?): Float = when (mode) {
            "SMALL" -> 0.90f
            "LARGE" -> 1.12f
            else -> DEFAULT_TEXT_SCALE
        }
    }
}
