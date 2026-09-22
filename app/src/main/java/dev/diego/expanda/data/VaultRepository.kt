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

    suspend fun refresh() = withContext(io) {
        mutableEntries.value = database.readVaultRows().mapNotNull { row ->
            runCatching { decode(crypto.decrypt(row.payload), row.id, row.createdAt, row.updatedAt) }
                .getOrNull()
        }
    }

    suspend fun save(entry: VaultEntry): Long = withContext(io) {
        val normalized = entry.normalized()
        require(normalized.title.isNotBlank()) { "Vault entry needs a title" }
        require(normalized.fields.isNotEmpty()) { "Vault entry needs at least one field" }
        val now = System.currentTimeMillis()
        val createdAt = if (normalized.id == 0L) now else normalized.createdAt
        val stored = normalized.copy(createdAt = createdAt, updatedAt = now)
        val id = database.upsertVaultRow(
            id = stored.id,
            payload = crypto.encrypt(encode(stored)),
            createdAt = createdAt,
            updatedAt = now,
        )
        mutableEntries.value = database.readVaultRows().mapNotNull { row ->
            runCatching { decode(crypto.decrypt(row.payload), row.id, row.createdAt, row.updatedAt) }
                .getOrNull()
        }
        id
    }

    suspend fun delete(id: Long) = withContext(io) {
        database.deleteVaultEntry(id)
        mutableEntries.value = mutableEntries.value.filterNot { it.id == id }
    }

    suspend fun updateFieldFromClipboard(entryId: Long, fieldId: String, value: String): Boolean =
        withContext(io) {
            val entry = mutableEntries.value.firstOrNull { it.id == entryId } ?: return@withContext false
            val updatedFields = entry.fields.map { field ->
                if (field.id == fieldId) field.copy(value = value) else field
            }
            if (updatedFields == entry.fields) return@withContext false
            save(entry.copy(fields = updatedFields))
            true
        }

    fun findByTrigger(trigger: String): VaultEntry? = entries.value.firstOrNull { entry ->
        entry.triggers.any { it == trigger }
    }

    private fun encode(entry: VaultEntry): String = JSONObject().apply {
        put("title", entry.title)
        put("triggers", JSONArray(entry.triggers))
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
            tags = json.optJSONArray("tags").strings().toSet(),
            favorite = json.optBoolean("favorite"),
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
