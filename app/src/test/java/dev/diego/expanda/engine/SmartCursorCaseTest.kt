package dev.diego.expanda.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartCursorCaseTest {
    @Test
    fun `empty context is sentence start`() {
        assertEquals(
            SmartCursorCase.Context.SENTENCE_START,
            SmartCursorCase.classify(""),
        )
    }

    @Test
    fun `comma context is continuation`() {
        assertEquals(
            SmartCursorCase.Context.CONTINUATION,
            SmartCursorCase.classify("Hola, "),
        )
    }

    @Test
    fun `period context is sentence start`() {
        assertEquals(
            SmartCursorCase.Context.SENTENCE_START,
            SmartCursorCase.classify("Terminó. "),
        )
    }

    @Test
    fun `three dots context is continuation`() {
        assertEquals(
            SmartCursorCase.Context.CONTINUATION,
            SmartCursorCase.classify("Bueno... "),
        )
    }

    @Test
    fun `ellipsis character context is continuation`() {
        assertEquals(
            SmartCursorCase.Context.CONTINUATION,
            SmartCursorCase.classify("Bueno… "),
        )
    }

    @Test
    fun `ellipsis before closing quote is continuation`() {
        assertEquals(
            SmartCursorCase.Context.CONTINUATION,
            SmartCursorCase.classify("Dijo «bueno...» "),
        )
        assertEquals(
            SmartCursorCase.Context.CONTINUATION,
            SmartCursorCase.classify("Dijo «bueno…» "),
        )
    }

    @Test
    fun `question and exclamation contexts remain sentence starts`() {
        assertEquals(
            SmartCursorCase.Context.SENTENCE_START,
            SmartCursorCase.classify("¿Vienes? "),
        )
        assertEquals(
            SmartCursorCase.Context.SENTENCE_START,
            SmartCursorCase.classify("¡Vamos! "),
        )
    }

    @Test
    fun `newline context is sentence start`() {
        assertEquals(
            SmartCursorCase.Context.SENTENCE_START,
            SmartCursorCase.classify("Hola,\n"),
        )
    }

    @Test
    fun `closing quote does not hide sentence ending`() {
        assertEquals(
            SmartCursorCase.Context.SENTENCE_START,
            SmartCursorCase.classify("Dijo «hola.» "),
        )
    }

    @Test
    fun `upper first inserted letter becomes lowercase inside wrapper`() {
        assertEquals(
            SmartCursorCase.Correction("Hola, «m»", 8),
            SmartCursorCase.lowercaseFirstInsertedLetter(
                baselineText = "Hola, «»",
                cursor = 7,
                currentText = "Hola, «M»",
            ),
        )
    }

    @Test
    fun `already lowercase first letter is left unchanged`() {
        assertEquals(
            SmartCursorCase.Correction("Hola, «m»", 8),
            SmartCursorCase.lowercaseFirstInsertedLetter(
                baselineText = "Hola, «»",
                cursor = 7,
                currentText = "Hola, «m»",
            ),
        )
    }

    @Test
    fun `unrelated edit is ignored`() {
        assertNull(
            SmartCursorCase.lowercaseFirstInsertedLetter(
                baselineText = "Hola, «»",
                cursor = 7,
                currentText = "Adiós, «M»",
            ),
        )
    }
}
