package dev.diego.expanda.data

import java.util.UUID

data class VaultField(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val value: String,
    val sensitive: Boolean = false,
)

data class VaultEntry(
    val id: Long = 0,
    val title: String,
    val triggers: List<String> = emptyList(),
    val fields: List<VaultField> = emptyList(),
    val tags: Set<String> = emptySet(),
    val favorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun normalized(): VaultEntry = copy(
        title = title.trim(),
        triggers = triggers.filter(String::isNotBlank).distinct(),
        fields = fields.filter { it.label.isNotBlank() || it.value.isNotEmpty() },
        tags = tags.map(String::trim).filter(String::isNotBlank).toSet(),
    )
}
