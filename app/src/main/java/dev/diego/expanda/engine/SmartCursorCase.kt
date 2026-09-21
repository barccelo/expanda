package dev.diego.expanda.engine

object SmartCursorCase {
    enum class Context { SENTENCE_START, CONTINUATION }

    data class Correction(
        val text: String,
        val cursor: Int,
    )

    fun classify(contextBeforeTrigger: String): Context {
        if (contextBeforeTrigger.isEmpty()) return Context.SENTENCE_START

        var index = contextBeforeTrigger.lastIndex
        var sawLineBreak = false
        while (index >= 0 && contextBeforeTrigger[index].isWhitespace()) {
            if (contextBeforeTrigger[index] == '\n' || contextBeforeTrigger[index] == '\r') {
                sawLineBreak = true
            }
            index--
        }
        if (sawLineBreak || index < 0) return Context.SENTENCE_START

        while (index >= 0 && contextBeforeTrigger[index] in CLOSING_PUNCTUATION) {
            index--
            while (index >= 0 && contextBeforeTrigger[index].isWhitespace()) index--
        }
        if (index < 0) return Context.SENTENCE_START

        return when (contextBeforeTrigger[index]) {
            '.', '!', '?', '…' -> Context.SENTENCE_START
            else -> Context.CONTINUATION
        }
    }

    /**
     * Corrects only the first inserted letter after an internal cursor marker.
     * This intentionally only lowercases continuation text; sentence-start casing
     * remains under keyboard/user control.
     */
    fun lowercaseFirstInsertedLetter(
        baselineText: String,
        cursor: Int,
        currentText: String,
    ): Correction? {
        if (cursor !in 0..baselineText.length || currentText.length < baselineText.length) return null

        val prefix = baselineText.substring(0, cursor)
        val suffix = baselineText.substring(cursor)
        if (!currentText.startsWith(prefix) || !currentText.endsWith(suffix)) return null

        val insertionEnd = currentText.length - suffix.length
        if (insertionEnd <= cursor) return null
        val inserted = currentText.substring(cursor, insertionEnd)
        val letterIndex = inserted.indexOfFirst(Char::isLetter)
        if (letterIndex < 0) return null

        val letter = inserted[letterIndex]
        if (!letter.isUpperCase()) return Correction(currentText, cursor + inserted.length)

        val correctedInserted = inserted.replaceRange(
            letterIndex,
            letterIndex + 1,
            letter.lowercaseChar().toString(),
        )
        return Correction(
            text = prefix + correctedInserted + suffix,
            cursor = cursor + correctedInserted.length,
        )
    }

    private val CLOSING_PUNCTUATION = setOf('»', '”', '’', '"', '\'', ')', ']', '}')
}
