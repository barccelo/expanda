package dev.diego.expanda.engine

import dev.diego.expanda.data.TriggerActivation

data class LiteralTriggerMatch(
    val trigger: String,
    val start: Int,
    val end: Int,
    val trailingActivation: String = "",
)

object TriggerMatcher {
    fun activationSuffix(
        text: String,
        cursor: Int,
        activation: TriggerActivation,
        delimiters: String,
    ): String = when (activation) {
        TriggerActivation.IMMEDIATE -> ""
        TriggerActivation.SPACE ->
            if (cursor > 0 && text[cursor - 1] == ' ') " " else ""
        TriggerActivation.DELIMITER ->
            if (cursor > 0 && text[cursor - 1] in delimiters) {
                text[cursor - 1].toString()
            } else {
                ""
            }
    }

    fun activationSatisfied(
        text: String,
        cursor: Int,
        activation: TriggerActivation,
        delimiters: String,
    ): Boolean = when (activation) {
        TriggerActivation.IMMEDIATE -> true
        TriggerActivation.SPACE -> cursor > 0 && text[cursor - 1] == ' '
        TriggerActivation.DELIMITER -> cursor > 0 && text[cursor - 1] in delimiters
    }

    fun matchLiteralSuffix(
        text: String,
        cursor: Int,
        triggers: Iterable<String>,
        caseSensitive: Boolean,
        activation: TriggerActivation = TriggerActivation.IMMEDIATE,
        delimiters: String = " \n\t.,!?;:",
    ): LiteralTriggerMatch? {
        if (cursor !in 0..text.length) return null
        if (!activationSatisfied(text, cursor, activation, delimiters)) return null
        val suffix = activationSuffix(text, cursor, activation, delimiters)
        val candidateEnd = cursor - suffix.length
        if (candidateEnd < 0) return null

        return triggers
            .asSequence()
            .filter(String::isNotBlank)
            .filter { it.length <= candidateEnd }
            .sortedByDescending(String::length)
            .firstNotNullOfOrNull { trigger ->
                val start = candidateEnd - trigger.length
                if (
                    text.regionMatches(
                        thisOffset = start,
                        other = trigger,
                        otherOffset = 0,
                        length = trigger.length,
                        ignoreCase = !caseSensitive,
                    )
                ) {
                    LiteralTriggerMatch(
                        trigger = trigger,
                        start = start,
                        end = candidateEnd,
                        trailingActivation = suffix,
                    )
                } else {
                    null
                }
            }
    }

    fun conflicts(
        left: String,
        leftCaseSensitive: Boolean,
        right: String,
        rightCaseSensitive: Boolean,
    ): Boolean {
        if (leftCaseSensitive && rightCaseSensitive) return left == right
        return left.equals(right, ignoreCase = true)
    }
}
