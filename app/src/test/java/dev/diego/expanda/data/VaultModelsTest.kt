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
    fun `case insensitive category collapses capitalization variants`() {
        val category = VaultCategory(
            name = "VOL",
            triggers = listOf(";vol", ";VOL"),
            caseSensitive = false,
        ).normalized()

        assertEquals(listOf(";vol"), category.triggers)
    }
}
