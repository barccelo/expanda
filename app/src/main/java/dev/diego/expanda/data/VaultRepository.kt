package dev.diego.expanda.data

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
        val otherEntryTriggers = mutableEntries.value
            .filterNot { it.id == entry.id }
            .flatMap(VaultEntry::triggers)
            .toSet()
        val categoryTriggers = mutableCategories.value
            .flatMap(VaultCategory::triggers)
            .toSet()
        val duplicate = entry.triggers.firstOrNull { it in otherEntryTriggers || it in categoryTriggers }
        require(duplicate == null) { "Trigger already used in the vault: $duplicate" }
    }

    private fun validateCategoryTriggers(category: VaultCategory) {
        val entryTriggers = mutableEntries.value.flatMap(VaultEntry::triggers).toSet()
        val otherCategoryTriggers = mutableCategories.value
            .filterNot { it.id == category.id }
            .flatMap(VaultCategory::triggers)
            .toSet()
        val duplicate = category.triggers.firstOrNull { it in entryTriggers || it in otherCategoryTriggers }
        require(duplicate == null) { "Trigger already used in the vault: $duplicate" }
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
