package dev.diego.expanda.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class ActionEngineTest {
    private val engine = ActionEngine()

    @Test fun `math replace consumes trigger and replaces expression`() {
        val text = "The result is 2 + 3*4=="
        val result = engine.execute(ActionContext(text, text.length))
        assertEquals("The result is 14", result?.text)
    }

    @Test fun `longer append math trigger wins`() {
        val text = "2+3,=="
        val result = engine.execute(ActionContext(text, text.length))
        assertEquals("2+3 = 5", result?.text)
        assertEquals("math_append", result?.definition?.id)
    }

    @Test fun `text actions transform and remove command`() {
        val text = "á hola mundo,uu"
        assertEquals("Á HOLA MUNDO", engine.execute(ActionContext(text, text.length))?.text)
        val remove = "áéíóú ñ,rd"
        assertEquals("aeiou n", engine.execute(ActionContext(remove, remove.length))?.text)
    }

    @Test fun `number actions format common styles`() {
        val text = "Importe 12345.67,nfp"
        assertEquals("Importe 12.345,67", engine.execute(ActionContext(text, text.length))?.text)
    }

    @Test fun `selection and clipboard requests are structured`() {
        val selected = engine.execute(ActionContext("hello,aa", 8))
        assertEquals(0, selected?.selectionStart)
        assertEquals(5, selected?.selectionEnd)
        val copied = engine.execute(ActionContext("hello,cc", 8))
        assertEquals(ActionRequest.Copy("hello"), copied?.request)
    }

    @Test fun `paste numbers inserts filtered clipboard`() {
        val text = "Phone: ,pn"
        val result = engine.execute(ActionContext(text, text.length, clipboard = "+34 600-12-34"))
        assertEquals("Phone: 346001234", result?.text)
    }

    @Test fun `disabled or incomplete actions do not run`() {
        val text = "hello,uu"
        assertNull(engine.execute(ActionContext(text, text.length), emptySet()))
        assertNull(engine.execute(ActionContext("hello,u", 7)))
        assertNotNull(engine.execute(ActionContext(text, text.length)))
    }

    @Test fun `custom shortcut replaces the default trigger`() {
        val enabled = setOf("uppercase")
        val overrides = mapOf("uppercase" to ";up")
        assertNull(engine.execute(ActionContext("hello,uu", 8), enabled, overrides))
        assertEquals("HELLO", engine.execute(ActionContext("hello;up", 8), enabled, overrides)?.text)
    }

    @Test fun `actions accept multiple trigger aliases and prefer the longest match`() {
        val enabled = setOf("uppercase")
        val triggers = mapOf("uppercase" to listOf(",uu", ";up", " up"))

        assertEquals(
            "HELLO",
            engine.execute(
                ActionContext("hello;up", "hello;up".length),
                enabledActionIds = enabled,
                triggerOverrides = triggers,
            )?.text,
        )
        assertEquals(
            "HELLO",
            engine.execute(
                ActionContext("hello up", "hello up".length),
                enabledActionIds = enabled,
                triggerOverrides = triggers,
            )?.text,
        )

        val overlapping = mapOf("uppercase" to listOf("up", ";up"))
        val result = engine.execute(
            ActionContext("hello;up", "hello;up".length),
            enabledActionIds = enabled,
            triggerOverrides = overlapping,
        )
        assertEquals("HELLO", result?.text)
        assertEquals(";up", result?.matchedTrigger)
    }

    @Test fun `previous word case triggers consume themselves and transform only the previous word`() {
        val upper = engine.execute(
            ActionContext("uno dos my", "uno dos my".length),
            enabledActionIds = setOf("uppercase_previous_word"),
        )
        assertEquals("uno DOS", upper?.text)
        assertEquals(" my", upper?.matchedTrigger)
        assertEquals("uppercase_previous_word", upper?.definition?.id)

        val lower = engine.execute(
            ActionContext("UNO DOS mn", "UNO DOS mn".length),
            enabledActionIds = setOf("lowercase_previous_word"),
        )
        assertEquals("UNO dos", lower?.text)
        assertEquals(" mn", lower?.matchedTrigger)
    }

    @Test fun `previous word scope skips punctuation but preserves it`() {
        assertEquals(
            "HOLA,",
            engine.execute(
                ActionContext("Hola, my", "Hola, my".length),
                enabledActionIds = setOf("uppercase_previous_word"),
            )?.text,
        )
        assertEquals(
            "Ya café...",
            engine.execute(
                ActionContext("Ya CAFÉ... mn", "Ya CAFÉ... mn".length),
                enabledActionIds = setOf("lowercase_previous_word"),
            )?.text,
        )
    }

    @Test fun `renaming a previous word trigger keeps its scope`() {
        val result = engine.execute(
            ActionContext("uno dos upw", "uno dos upw".length),
            enabledActionIds = setOf("uppercase_previous_word"),
            triggerOverrides = mapOf("uppercase_previous_word" to listOf(" upw")),
        )
        assertEquals("uno DOS", result?.text)
        assertEquals(" upw", result?.matchedTrigger)
    }

    @Test fun `regular case actions still transform the whole field`() {
        assertEquals(
            "UNO DOS",
            engine.execute(
                ActionContext("Uno dos,uu", "Uno dos,uu".length),
                enabledActionIds = setOf("uppercase"),
            )?.text,
        )
        assertEquals(
            "uno dos",
            engine.execute(
                ActionContext("UNO DOS,ll", "UNO DOS,ll".length),
                enabledActionIds = setOf("lowercase"),
            )?.text,
        )
    }

    @Test fun `open vault action consumes trigger and requests vault overlay`() {
        val result = engine.execute(
            ActionContext("dato vv", "dato vv".length),
            enabledActionIds = setOf("open_vault"),
        )
        assertEquals("dato", result?.text)
        assertEquals(ActionRequest.OpenVault, result?.request)
        assertEquals(" vv", result?.matchedTrigger)
    }

    @Test fun `only open vault is enabled by default`() {
        assertEquals(
            setOf("open_vault"),
            ActionEngine.definitions.filter { it.enabledByDefault }.mapTo(linkedSetOf()) { it.id },
        )
    }

    @Test fun `selected text actions reuse the canonical transformations`() {
        assertEquals("AE N", engine.processSelectedText("uppercase", "áé ñ")?.let {
            engine.processSelectedText("remove_diacritics", it)
        })
        assertEquals("12.345,67", engine.processSelectedText("number_period", "12345.67"))
        assertEquals("2+3 = 5", engine.processSelectedText("math_append", "2+3"))
        assertNull(engine.processSelectedText("cursor_start", "hello"))
    }

    @Test fun `sentence case lowercases non initial letters and capitalizes sentence starts`() {
        assertEquals(
            "Hello hello. This is a test! Another one? Yes\nNew line",
            engine.processSelectedText(
                "sentence_case",
                "HELLO Hello. THIS IS A TEST! ANOTHER ONE? YES\nNEW LINE",
            ),
        )
    }

    @Test fun `sentence case works when selected text ends with punctuation`() {
        assertEquals("Hello world.", engine.processSelectedText("sentence_case", "HELLO WORLD."))
        assertEquals("Hello world!", engine.processSelectedText("sentence_case", "hELLO WORLD!"))
        assertEquals("Hello world?", engine.processSelectedText("sentence_case", "HELLO WORLD?"))
    }

    @Test fun `title case normalizes fully uppercase selections`() {
        assertEquals(
            "Texto En Mayúsculas",
            engine.processSelectedText("title_case", "TEXTO EN MAYÚSCULAS"),
        )
        assertEquals(
            "Texto Ya Mezclado",
            engine.processSelectedText("title_case", "tEXTO yA mEZCLADO"),
        )
    }

    @Test fun `wrap actions surround selected text with configured pairs`() {
        assertEquals("«texto»", engine.processSelectedText("wrap_guillemets", "texto"))
        assertEquals("(texto)", engine.processSelectedText("wrap_parentheses", "texto"))
        assertEquals("¿texto?", engine.processSelectedText("wrap_question", "texto"))
        assertEquals("¡texto!", engine.processSelectedText("wrap_exclamation", "texto"))
        assertEquals("[texto]", engine.processSelectedText("wrap_brackets", "texto"))
        assertEquals("**texto**", engine.processSelectedText("wrap_double_asterisk", "texto"))
        assertEquals("__texto__", engine.processSelectedText("wrap_double_underscore", "texto"))
    }

    @Test fun `selected range transformation preserves surrounding text and selection`() {
        val result = engine.processSelectedRange(
            actionId = "lowercase",
            text = "Prefix HELLO WORLD suffix",
            selectionStart = 7,
            selectionEnd = 18,
        )
        assertEquals("Prefix hello world suffix", result?.text)
        assertEquals(7, result?.selectionStart)
        assertEquals(18, result?.selectionEnd)
        assertEquals("hello world", result?.replacement)
    }

    @Test fun `selected range accepts reversed selection bounds`() {
        val result = engine.processSelectedRange(
            actionId = "uppercase",
            text = "hello world",
            selectionStart = 5,
            selectionEnd = 0,
        )
        assertEquals("HELLO world", result?.text)
        assertEquals(0, result?.selectionStart)
        assertEquals(5, result?.selectionEnd)
    }

    @Test fun `tooly style selected text transforms are deterministic`() {
        assertEquals("abc", engine.processSelectedText("remove_all_spaces", " a b c "))
        assertEquals("a\nb\nc", engine.processSelectedText("sort_lines", "c\na\nb"))
        assertEquals("a\nb", engine.processSelectedText("remove_duplicate_lines", "a\nb\na"))
        assertEquals("one two", engine.processSelectedText("remove_duplicate_words", "one two one"))
        assertEquals("one two", engine.processSelectedText("remove_line_breaks", "one\ntwo"))
        assertEquals("1. one\n2. two", engine.processSelectedText("number_lines", "one\ntwo"))
        assertEquals("cba", engine.processSelectedText("reverse_text", "abc"))
        assertEquals("three\ntwo\none", engine.processSelectedText("reverse_lines", "one\ntwo\nthree"))
        assertEquals("three two one", engine.processSelectedText("reverse_words", "one two three"))
    }

    @Test fun `only context free actions are exposed to Android selected text`() {
        val exposed = ActionEngine.definitions.filter { it.supportsSelectedText }.map { it.id }.toSet()
        assertEquals(true, "uppercase" in exposed)
        assertEquals(true, "delete_blank_lines" in exposed)
        assertEquals(false, "copy_before" in exposed)
        assertEquals(false, "new_snippet" in exposed)
    }

    @Test fun `uuid works from typing and selected text actions`() {
        val selected = engine.processSelectedText("uuid", "replace me")
        assertEquals(selected, UUID.fromString(selected).toString())

        val typed = engine.execute(ActionContext("replace me,uuid", "replace me,uuid".length))?.text
        assertEquals(typed, UUID.fromString(typed).toString())
    }
}
