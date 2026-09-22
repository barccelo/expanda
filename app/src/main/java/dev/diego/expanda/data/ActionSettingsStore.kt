package dev.diego.expanda.data

import android.content.Context
import android.content.SharedPreferences
import dev.diego.expanda.engine.ActionEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * Keeps action toggles and user-defined shortcuts separate from general preferences.
 * Actions are opt-in: a fresh install starts with every typing action disabled.
 */
class ActionSettingsStore(context: Context) : SharedPreferences.OnSharedPreferenceChangeListener {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val mutableEnabledIds = MutableStateFlow(readEnabledIds())
    val enabledIds: StateFlow<Set<String>> = mutableEnabledIds.asStateFlow()
    private val mutableTriggerOverrides = MutableStateFlow(readTriggerOverrides())
    val triggerOverrides: StateFlow<Map<String, List<String>>> = mutableTriggerOverrides.asStateFlow()
    private val mutableShortcutOverrides = MutableStateFlow(readShortcutOverrides())
    val shortcutOverrides: StateFlow<Map<String, String>> = mutableShortcutOverrides.asStateFlow()

    init { preferences.registerOnSharedPreferenceChangeListener(this) }

    fun isEnabled(id: String): Boolean = id in enabledIds.value

    fun setEnabled(id: String, enabled: Boolean) {
        if (ActionEngine.definitions.none { it.id == id }) return
        val enabledIds = readEnabledIds().toMutableSet().also {
            if (enabled) it.add(id) else it.remove(id)
        }
        val disabled = ActionEngine.definitions.mapTo(linkedSetOf()) { it.id }.apply { removeAll(enabledIds) }
        preferences.edit().putStringSet(KEY_DISABLED_IDS, disabled).apply()
    }

    fun setAllEnabled(enabled: Boolean) {
        val disabled = if (enabled) emptySet() else ActionEngine.definitions.mapTo(linkedSetOf()) { it.id }
        preferences.edit().putStringSet(KEY_DISABLED_IDS, disabled).apply()
    }

    fun setTriggers(id: String, triggers: List<String>) {
        val definition = ActionEngine.definitions.firstOrNull { it.id == id } ?: return
        val normalized = triggers
            .filter(String::isNotBlank)
            .distinct()
        if (normalized.isEmpty()) return
        preferences.edit().apply {
            if (normalized == definition.triggers) {
                remove(KEY_TRIGGERS_PREFIX + id)
            } else {
                putString(KEY_TRIGGERS_PREFIX + id, JSONArray(normalized).toString())
            }
            remove(KEY_SHORTCUT_PREFIX + id)
        }.apply()
    }

    fun setShortcut(id: String, shortcut: String) {
        if (shortcut.isBlank()) return
        setTriggers(id, listOf(shortcut))
    }

    fun resetTriggers(id: String) {
        preferences.edit()
            .remove(KEY_TRIGGERS_PREFIX + id)
            .remove(KEY_SHORTCUT_PREFIX + id)
            .apply()
    }

    fun resetShortcut(id: String) = resetTriggers(id)

    fun restore(snapshot: BackupCodec.ActionSnapshot) {
        val knownIds = ActionEngine.definitions.mapTo(linkedSetOf()) { it.id }
        val enabled = snapshot.enabledIds.intersect(knownIds)
        preferences.edit().apply {
            clear()
            putStringSet(KEY_DISABLED_IDS, knownIds - enabled)
            val restoredTriggers = snapshot.shortcutOverrides
                .mapValues { (_, shortcut) -> listOf(shortcut) }
                .toMutableMap()
                .apply { putAll(snapshot.triggerOverrides) }
            restoredTriggers.forEach { (id, triggers) ->
                val normalized = triggers.filter(String::isNotBlank).distinct()
                if (id in knownIds && normalized.isNotEmpty()) {
                    putString(KEY_TRIGGERS_PREFIX + id, JSONArray(normalized).toString())
                }
            }
        }.apply()
        mutableEnabledIds.value = readEnabledIds()
        mutableTriggerOverrides.value = readTriggerOverrides()
        mutableShortcutOverrides.value = readShortcutOverrides()
    }

    fun reset() {
        preferences.edit().clear().apply()
        mutableEnabledIds.value = readEnabledIds()
        mutableTriggerOverrides.value = readTriggerOverrides()
        mutableShortcutOverrides.value = readShortcutOverrides()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == KEY_DISABLED_IDS) mutableEnabledIds.value = readEnabledIds()
        if (
            key?.startsWith(KEY_SHORTCUT_PREFIX) == true ||
            key?.startsWith(KEY_TRIGGERS_PREFIX) == true
        ) {
            mutableTriggerOverrides.value = readTriggerOverrides()
            mutableShortcutOverrides.value = readShortcutOverrides()
        }
    }

    private fun readEnabledIds(): Set<String> {
        if (!preferences.contains(KEY_DISABLED_IDS)) return emptySet()
        val disabled = preferences.getStringSet(KEY_DISABLED_IDS, emptySet()).orEmpty()
        return ActionEngine.definitions
            .asSequence()
            .filter { it.id !in disabled }
            .mapTo(linkedSetOf()) { it.id }
    }

    private fun readTriggerOverrides(): Map<String, List<String>> = buildMap {
        ActionEngine.definitions.forEach { definition ->
            val stored = preferences.getString(KEY_TRIGGERS_PREFIX + definition.id, null)
                ?.let { raw ->
                    runCatching {
                        val array = JSONArray(raw)
                        buildList {
                            for (index in 0 until array.length()) {
                                array.optString(index)
                                    .takeIf(String::isNotBlank)
                                    ?.let(::add)
                            }
                        }.distinct()
                    }.getOrNull()
                }
                ?.takeIf(List<String>::isNotEmpty)

            val legacy = preferences.getString(KEY_SHORTCUT_PREFIX + definition.id, null)
                ?.takeIf(String::isNotBlank)
                ?.let(::listOf)

            val triggers = stored ?: legacy
            if (triggers != null && triggers != definition.triggers) {
                put(definition.id, triggers)
            }
        }
    }

    private fun readShortcutOverrides(): Map<String, String> =
        readTriggerOverrides().mapNotNull { (id, triggers) ->
            triggers.firstOrNull()?.let { id to it }
        }.toMap()

    companion object {
        private const val FILE_NAME = "action_settings"
        private const val KEY_DISABLED_IDS = "disabled_action_ids"
        private const val KEY_SHORTCUT_PREFIX = "action_shortcut_"
        private const val KEY_TRIGGERS_PREFIX = "action_triggers_"
    }
}
