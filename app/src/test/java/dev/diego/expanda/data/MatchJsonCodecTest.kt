package dev.diego.expanda.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MatchJsonCodecTest {
    @Test
    fun `suggestion visibility round trips through canonical json`() {
        val match = TextMatch(
            triggers = listOf(MatchTrigger("quiet")),
            replacements = listOf("Hidden from suggestions"),
            suggestionEnabled = false,
        )

        val decoded = MatchJsonCodec.decode(MatchJsonCodec.encode(match))

        assertEquals(false, decoded.suggestionEnabled)
    }

    @Test
    fun `space activation round trips through canonical json`() {
        val match = TextMatch(
            triggers = listOf(MatchTrigger("sig")),
            replacements = listOf("Signature"),
            options = MatchOptions(
                activation = TriggerActivation.SPACE,
                delimiters = " \n\t.,!?;:",
            ),
        )

        val decoded = MatchJsonCodec.decode(MatchJsonCodec.encode(match))

        assertEquals(TriggerActivation.SPACE, decoded.options.activation)
        assertEquals(match.options.delimiters, decoded.options.delimiters)
    }
}
