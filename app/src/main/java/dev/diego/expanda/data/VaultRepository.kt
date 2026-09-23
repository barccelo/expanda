package dev.diego.expanda.data

import dev.diego.expanda.engine.TriggerMatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class VaultRepository(
    private val database: ExpandaDatabase,
    private val crypto: VaultCrypto = VaultCrypto(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableEntries = MutableStateFlow<List<VaultEntry>>(emptyList())
    val entries: StateFlow<List<VaultEntry>> = mutableEntries.asStateFlow()

    private val mutableCategories = MutableStateFlow<List<VaultCategory>>(emptyList())
    val categories: StateFlow<List<VaultCategory>> = mutableCategories.asStateFlow()

    suspend fun refresh() = withContext(io) {
        reloadEntries()
        reloadCategories()
        ensureCategoriesForEntries()
    }

    suspend fun save(entry: VaultEntry): Long = withContext(io) {
        val normalized = entry.normalized()
        require(normalized.title.isNotBlank()) { "Vault entry needs a title" }
        require(normalized.fields.isNotEmpty()) { "Vault entry needs at least one field" }
        validateEntryTriggers(normalized)

        val now = System.currentTimeMillis()
        val createdAt = if (normalized.id == 0L) now else normalized.createdAt
        val stored = normalized.copy(createdAt = createdAt, updatedAt = now)
        val id = writeEntry(stored)
        reloadEntries()

        stored.category?.let { categoryName ->
            ensureCategory(categoryName)
        }
        id
    }

    suspend fun delete(id: Long) = withContext(io) {
        database.deleteVaultEntry(id)
        reloadEntries()
    }

    suspend fun saveCategory(category: VaultCategory): Long = withContext(io) {
        val normalized = category.normalized()
        require(normalized.name.isNotBlank()) { "Vault category needs a name" }
        validateCategoryTriggers(normalized)

        val existing = mutableCategories.value.firstOrNull { it.id == normalized.id }
        val duplicateName = mutableCategories.value.firstOrNull {
            it.id != normalized.id && it.name.equals(normalized.name, ignoreCase = true)
        }
        require(duplicateName == null) { "A vault category with that name already exists" }

        val now = System.currentTimeMillis()
        val createdAt = if (normalized.id == 0L) now else normalized.createdAt
        val stored = normalized.copy(createdAt = createdAt, updatedAt = now)
        val id = database.upsertVaultCategoryRow(
            id = stored.id,
            payload = crypto.encrypt(encodeCategory(stored)),
            createdAt = createdAt,
            updatedAt = now,
        )

        if (existing != null && !existing.name.equals(stored.name, ignoreCase = false)) {
            val affected = mutableEntries.value.filter {
                it.category?.equals(existing.name, ignoreCase = true) == true
            }
            affected.forEach { entry ->
                writeEntry(
                    entry.copy(
                        category = stored.name,
                        updatedAt = now,
                    ),
                )
            }
            reloadEntries()
        }
        reloadCategories()
        id
    }

    suspend fun deleteCategory(id: Long): Boolean = withContext(io) {
        val category = mutableCategories.value.firstOrNull { it.id == id } ?: return@withContext false
        val now = System.currentTimeMillis()
        mutableEntries.value
            .filter { it.category?.equals(category.name, ignoreCase = true) == true }
            .forEach { entry ->
                writeEntry(entry.copy(category = null, updatedAt = now))
            }
        database.deleteVaultCategory(id)
        reloadEntries()
        reloadCategories()
        true
    }

    suspend fun moveEntries(
        entryIds: Set<Long>,
        categoryName: String?,
    ): Int = withContext(io) {
        if (entryIds.isEmpty()) return@withContext 0
        val normalizedCategory = categoryName?.trim()?.takeIf(String::isNotBlank)
        normalizedCategory?.let(::ensureCategory)
        val now = System.currentTimeMillis()
        val affected = mutableEntries.value.filter { it.id in entryIds }
        affected.forEach { entry ->
            writeEntry(
                entry.copy(
                    category = normalizedCategory,
                    updatedAt = now,
                ),
            )
        }
        reloadEntries()
        affected.size
    }

    suspend fun moveTrigger(
        trigger: String,
        targetCategoryId: Long? = null,
        targetEntryId: Long? = null,
    ): Boolean = withContext(io) {
        require((targetCategoryId == null) xor (targetEntryId == null)) {
            "Choose exactly one target for the trigger"
        }
        val normalizedTrigger = trigger.takeIf(String::isNotBlank) ?: return@withContext false

        val sourceEntry = mutableEntries.value.firstOrNull { normalizedTrigger in it.triggers }
        val sourceCategory = mutableCategories.value.firstOrNull { normalizedTrigger in it.triggers }

        val targetEntry = targetEntryId?.let { id ->
            mutableEntries.value.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Vault entry not found")
        }
        val targetCategory = targetCategoryId?.let { id ->
            mutableCategories.value.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Vault category not found")
        }

        if (sourceEntry?.id == targetEntry?.id || sourceCategory?.id == targetCategory?.id) {
            return@withContext true
        }

        val targetCaseSensitive = targetEntry?.caseSensitive ?: targetCategory?.caseSensitive ?: false
        requireTriggerAvailable(
            trigger = normalizedTrigger,
            caseSensitive = targetCaseSensitive,
            excludeEntryId = sourceEntry?.id ?: targetEntry?.id,
            excludeCategoryId = sourceCategory?.id ?: targetCategory?.id,
        )

        val now = System.currentTimeMillis()

        sourceEntry?.let { entry ->
            writeEntry(
                entry.copy(
                    triggers = entry.triggers.filterNot { it == normalizedTrigger },
                    updatedAt = now,
                ),
            )
        }
        sourceCategory?.let { category ->
            database.upsertVaultCategoryRow(
                id = category.id,
                payload = crypto.encrypt(
                    encodeCategory(
                        category.copy(
                            triggers = category.triggers.filterNot { it == normalizedTrigger },
                            updatedAt = now,
                        ),
                    ),
                ),
                createdAt = category.createdAt,
                updatedAt = now,
            )
        }

        targetEntry?.let { entry ->
            writeEntry(
                entry.copy(
                    triggers = (entry.triggers + normalizedTrigger).distinct(),
                    updatedAt = now,
                ),
            )
        }
        targetCategory?.let { category ->
            val updated = category.copy(
                triggers = (category.triggers + normalizedTrigger).distinct(),
                updatedAt = now,
            )
            database.upsertVaultCategoryRow(
                id = category.id,
                payload = crypto.encrypt(encodeCategory(updated)),
                createdAt = category.createdAt,
                updatedAt = now,
            )
        }

        reloadEntries()
        reloadCategories()
        true
    }

    suspend fun updateFieldFromClipboard(entryId: Long, fieldId: String, value: String): Boolean =
        withContext(io) {
            val entry = mutableEntries.value.firstOrNull { it.id == entryId } ?: return@withContext false
            val updatedFields = entry.fields.map { field ->
                if (field.id == fieldId) field.copy(value = value) else field
            }
            if (updatedFields == entry.fields) return@withContext false
            val now = System.currentTimeMillis()
            writeEntry(entry.copy(fields = updatedFields, updatedAt = now))
            reloadEntries()
            true
        }

    fun findByTrigger(trigger: String): VaultEntry? = entries.value.firstOrNull { entry ->
        entry.triggers.any { it == trigger }
    }

    fun findCategoryByTrigger(trigger: String): VaultCategory? =
        categories.value.firstOrNull { category -> category.triggers.any { it == trigger } }

    private fun validateEntryTriggers(entry: VaultEntry) {
        entry.triggers.forEach { trigger ->
            requireTriggerAvailable(
                trigger = trigger,
                caseSensitive = entry.caseSensitive,
                excludeEntryId = entry.id,
            )
        }
    }

    private fun validateCategoryTriggers(category: VaultCategory) {
        category.triggers.forEach { trigger ->
            requireTriggerAvailable(
                trigger = trigger,
                caseSensitive = category.caseSensitive,
                excludeCategoryId = category.id,
            )
        }
    }

    private fun requireTriggerAvailable(
        trigger: String,
        caseSensitive: Boolean,
        excludeEntryId: Long? = null,
        excludeCategoryId: Long? = null,
    ) {
        val entryConflict = mutableEntries.value
            .asSequence()
            .filterNot { it.id == excludeEntryId }
            .any { entry ->
                entry.triggers.any { existing ->
                    TriggerMatcher.conflicts(
                        trigger,
                        caseSensitive,
                        existing,
                        entry.caseSensitive,
                    )
                }
            }
        val categoryConflict = mutableCategories.value
            .asSequence()
            .filterNot { it.id == excludeCategoryId }
            .any { category ->
                category.triggers.any { existing ->
                    TriggerMatcher.conflicts(
                        trigger,
                        caseSensitive,
                        existing,
                        category.caseSensitive,
                    )
                }
            }
        require(!entryConflict && !categoryConflict) {
            "Trigger already used in the vault: $trigger"
        }
    }

    private fun ensureCategoriesForEntries() {
        mutableEntries.value
            .mapNotNull(VaultEntry::category)
            .filter(String::isNotBlank)
            .distinctBy(String::lowercase)
            .forEach(::ensureCategory)
    }

    private fun ensureCategory(name: String) {
        if (mutableCategories.value.any { it.name.equals(name, ignoreCase = true) }) return
        val now = System.currentTimeMillis()
        val category = VaultCategory(name = name.trim(), createdAt = now, updatedAt = now)
        database.upsertVaultCategoryRow(
            id = 0,
            payload = crypto.encrypt(encodeCategory(category)),
            createdAt = now,
            updatedAt = now,
        )
        reloadCategories()
    }

    private fun writeEntry(entry: VaultEntry): Long = database.upsertVaultRow(
        id = entry.id,
        payload = crypto.encrypt(encode(entry)),
        createdAt = entry.createdAt,
        updatedAt = entry.updatedAt,
    )

    private fun reloadEntries() {
        mutableEntries.value = database.readVaultRows().mapNotNull { row ->
            runCatching { decode(crypto.decrypt(row.payload), row.id, row.createdAt, row.updatedAt) }
                .getOrNull()
        }
    }

    private fun reloadCategories() {
        mutableCategories.value = database.readVaultCategoryRows().mapNotNull { row ->
            runCatching {
                decodeCategory(crypto.decrypt(row.payload), row.id, row.createdAt, row.updatedAt)
            }.getOrNull()
        }
    }

    private fun encode(entry: VaultEntry): String = JSONObject().apply {
        put("title", entry.title)
        put("triggers", JSONArray(entry.triggers))
        put("caseSensitive", entry.caseSensitive)
        put("category", entry.category)
        put("tags", JSONArray(entry.tags.sorted()))
        put("favorite", entry.favorite)
        put("fields", JSONArray().apply {
            entry.fields.forEach { field ->
                put(
                    JSONObject()
                        .put("id", field.id)
                        .put("label", field.label)
                        .put("value", field.value)
                        .put("sensitive", field.sensitive),
                )
            }
        })
    }.toString()

    private fun encodeCategory(category: VaultCategory): String = JSONObject().apply {
        put("name", category.name)
        put("triggers", JSONArray(category.triggers))
        put("caseSensitive", category.caseSensitive)
    }.toString()

    private fun decode(
        value: String,
        id: Long,
        createdAt: Long,
        updatedAt: Long,
    ): VaultEntry {
        val json = JSONObject(value)
        val fieldsJson = json.optJSONArray("fields") ?: JSONArray()
        val fields = buildList {
            for (index in 0 until fieldsJson.length()) {
                val item = fieldsJson.optJSONObject(index) ?: continue
                add(
                    VaultField(
                        id = item.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                        label = item.optString("label"),
                        value = item.optString("value"),
                        sensitive = item.optBoolean("sensitive"),
                    ),
                )
            }
        }
        return VaultEntry(
            id = id,
            title = json.optString("title"),
            triggers = json.optJSONArray("triggers").strings(),
            caseSensitive = json.optBoolean("caseSensitive", false),
            fields = fields,
            category = json.optString("category").takeIf(String::isNotBlank),
            tags = json.optJSONArray("tags").strings().toSet(),
            favorite = json.optBoolean("favorite"),
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun decodeCategory(
        value: String,
        id: Long,
        createdAt: Long,
        updatedAt: Long,
    ): VaultCategory {
        val json = JSONObject(value)
        return VaultCategory(
            id = id,
            name = json.optString("name"),
            triggers = json.optJSONArray("triggers").strings(),
            caseSensitive = json.optBoolean("caseSensitive", false),
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else buildList(length()) {
            for (index in 0 until length()) {
                optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
}
