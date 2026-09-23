package dev.diego.expanda.engine

import dev.diego.expanda.data.TriggerActivation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerMatcherTest {
    @Test
    fun `space activation requires literal space`() {
        val spaced = TriggerMatcher.matchLiteralSuffix(
            text = "hello ",
            cursor = 6,
            triggers = listOf("hello"),
            caseSensitive = true,
            activation = TriggerActivation.SPACE,
        )
        assertEquals("hello", spaced?.trigger)
        assertEquals(" ", spaced?.trailingActivation)

        assertNull(
            TriggerMatcher.matchLiteralSuffix(
                text = "hello",
                cursor = 5,
                triggers = listOf("hello"),
                caseSensitive = true,
                activation = TriggerActivation.SPACE,
            ),
        )
        assertNull(
            TriggerMatcher.matchLiteralSuffix(
                text = "hello.",
                cursor = 6,
                triggers = listOf("hello"),
                caseSensitive = true,
                activation = TriggerActivation.SPACE,
            ),
        )
    }

    @Test
    fun `case insensitive matching accepts capitalization variants`() {
        assertTrue(
            TriggerMatcher.matchLiteralSuffix(
                text = "BB",
                cursor = 2,
                triggers = listOf("bb"),
                caseSensitive = false,
            ) != null,
        )
        assertNull(
            TriggerMatcher.matchLiteralSuffix(
                text = "BB",
                cursor = 2,
                triggers = listOf("bb"),
                caseSensitive = true,
            ),
        )
    }

    @Test
    fun `longest matching trigger wins`() {
        val match = TriggerMatcher.matchLiteralSuffix(
            text = "email",
            cursor = 5,
            triggers = listOf("mail", "email"),
            caseSensitive = true,
        )
        assertEquals("email", match?.trigger)
    }

    @Test
    fun `conflict becomes case insensitive when either owner is insensitive`() {
        assertTrue(TriggerMatcher.conflicts("bb", false, "BB", true))
        assertTrue(TriggerMatcher.conflicts("bb", true, "BB", false))
        assertTrue(!TriggerMatcher.conflicts("bb", true, "BB", true))
    }
}
