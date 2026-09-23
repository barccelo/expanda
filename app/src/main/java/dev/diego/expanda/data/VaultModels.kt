package dev.diego.expanda.data

import java.util.UUID
import java.util.Locale

data class VaultField(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val value: String,
    val sensitive: Boolean = false,
)

data class VaultCategory(
    val id: Long = 0,
    val name: String,
    val triggers: List<String> = emptyList(),
    val caseSensitive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun normalized(): VaultCategory = copy(
        name = name.trim(),
        triggers = triggers
            .filter(String::isNotBlank)
            .distinctBy { if (caseSensitive) it else it.lowercase(Locale.ROOT) },
    )
}

data class VaultEntry(
    val id: Long = 0,
    val title: String,
    val triggers: List<String> = emptyList(),
    val caseSensitive: Boolean = false,
    val fields: List<VaultField> = emptyList(),
    val category: String? = null,
    val tags: Set<String> = emptySet(),
    val favorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun normalized(): VaultEntry = copy(
        title = title.trim(),
        triggers = triggers
            .filter(String::isNotBlank)
            .distinctBy { if (caseSensitive) it else it.lowercase(Locale.ROOT) },
        fields = fields.filter { it.label.isNotBlank() || it.value.isNotEmpty() },
        category = category?.trim()?.takeIf(String::isNotBlank),
        tags = tags.map(String::trim).filter(String::isNotBlank).toSet(),
    )
}
