package dev.diego.expanda.data

import org.junit.Assert.assertEquals
import org.junit.Test

class VaultModelsTest {
    @Test
    fun `case insensitive vault entry collapses capitalization variants`() {
        val entry = VaultEntry(
            title = "Test",
            triggers = listOf("bb", "BB", "Bb"),
            fields = listOf(VaultField(label = "Value", value = "x")),
            caseSensitive = false,
        ).normalized()

        assertEquals(listOf("bb"), entry.triggers)
    }

    @Test
    fun `case sensitive vault entry keeps capitalization variants`() {
        val entry = VaultEntry(
            title = "Test",
            triggers = listOf("bb", "BB"),
            fields = listOf(VaultField(label = "Value", value = "x")),
            caseSensitive = true,
        ).normalized()

        assertEquals(listOf("bb", "BB"), entry.triggers)
    }

    @Test
    fun `vault copy selection keeps only existing fields`() {
        val first = VaultField(id = "one", label = "User", value = "david")
        val second = VaultField(id = "two", label = "Password", value = "secret")
        val entry = VaultEntry(
            title = "Test",
            fields = listOf(first, second),
            preferredCopyFieldIds = listOf("two", "missing", "two"),
        ).normalized()

        assertEquals(listOf("two"), entry.preferredCopyFieldIds)
    }

    @Test
    fun `empty normalized copy selection falls back to default all behavior`() {
        val entry = VaultEntry(
            title = "Test",
            fields = listOf(VaultField(id = "one", label = "User", value = "david")),
            preferredCopyFieldIds = listOf("missing"),
        ).normalized()

        assertEquals(null, entry.preferredCopyFieldIds)
    }

    @Test
    fun `case insensitive category collapses capitalization variants`() {
        val category = VaultCategory(
            name = "VOL",
            triggers = listOf(";vol", ";VOL"),
            caseSensitive = false,
        ).normalized()

        assertEquals(listOf(";vol"), category.triggers)
    }
}
