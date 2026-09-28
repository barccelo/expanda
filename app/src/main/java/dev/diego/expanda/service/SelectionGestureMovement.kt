package dev.diego.expanda.service

/**
 * Deterministic cursor targets for the gesture selector.
 *
 * Vertical movement uses logical text lines rather than Android's accessibility
 * line granularity. Some editors report unstable visual-line positions (or no
 * position at all for a one-line field), which made selection overshoot and
 * snap back.
 */
internal object SelectionGestureMovement {
    fun lineTarget(text: String, cursor: Int, forward: Boolean): Int? {
        if (cursor !in 0..text.length) return null

        val currentStart = when {
            cursor <= 0 -> 0
            cursor <= text.length && text.getOrNull(cursor - 1) == '\n' -> cursor
            else -> text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0))
                .let { if (it < 0) 0 else it + 1 }
        }
        val currentEnd = text.indexOf('\n', cursor.coerceAtMost(text.length))
            .let { if (it < 0) text.length else it }
        val column = (cursor - currentStart).coerceAtLeast(0)

        val target = if (forward) {
            if (currentEnd < text.length) {
                val nextStart = currentEnd + 1
                val nextEnd = text.indexOf('\n', nextStart)
                    .let { if (it < 0) text.length else it }
                (nextStart + column).coerceAtMost(nextEnd)
            } else {
                currentEnd
            }
        } else {
            if (currentStart > 0) {
                val previousEnd = currentStart - 1
                val previousStart = text.lastIndexOf(
                    '\n',
                    (previousEnd - 1).coerceAtLeast(0),
                ).let { if (it < 0) 0 else it + 1 }
                (previousStart + column).coerceAtMost(previousEnd)
            } else {
                currentStart
            }
        }

        return target.takeIf { it != cursor }
    }
}
