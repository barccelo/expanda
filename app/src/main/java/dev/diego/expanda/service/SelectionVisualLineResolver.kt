package dev.diego.expanda.service

import kotlin.math.abs
import kotlin.math.max

/**
 * Resolves whole visual-line boundaries without changing the editor selection.
 * Character bounds come from AccessibilityNodeInfo extra text-location data.
 */
internal object SelectionVisualLineResolver {
    data class Box(
        val index: Int,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val centerY: Float get() = (top + bottom) / 2f
        val height: Float get() = bottom - top
    }

    private data class Line(
        val boxes: MutableList<Box>,
        var centerY: Float,
        var averageHeight: Float,
    )

    fun target(
        boxes: List<Box>,
        textLength: Int,
        cursor: Int,
        forward: Boolean,
    ): Int? {
        if (cursor !in 0..textLength) return null
        if ((!forward && cursor == 0) || (forward && cursor == textLength)) return null

        val usable = boxes
            .filter { it.index in 0 until textLength && it.bottom > it.top }
            .sortedWith(compareBy<Box> { it.centerY }.thenBy { it.left })
        if (usable.isEmpty()) return null

        val lines = mutableListOf<Line>()
        usable.forEach { box ->
            val current = lines.lastOrNull()
            val tolerance = if (current == null) {
                0f
            } else {
                max(2f, max(current.averageHeight, box.height) * 0.45f)
            }
            if (current == null || abs(box.centerY - current.centerY) > tolerance) {
                lines += Line(
                    boxes = mutableListOf(box),
                    centerY = box.centerY,
                    averageHeight = box.height,
                )
            } else {
                val size = current.boxes.size.toFloat()
                current.centerY = (current.centerY * size + box.centerY) / (size + 1f)
                current.averageHeight =
                    (current.averageHeight * size + box.height) / (size + 1f)
                current.boxes += box
            }
        }
        if (lines.isEmpty()) return null

        val starts = lines.map { line -> line.boxes.minOf { it.index } }
        val ends = lines.indices.map { index ->
            if (index < lines.lastIndex) {
                // The next visual line's first character is also the trailing
                // caret for this line. This naturally includes hard line breaks.
                starts[index + 1]
            } else {
                textLength
            }
        }

        return if (forward) {
            var lineIndex = lines.indices.firstOrNull { index ->
                cursor >= starts[index] && cursor <= ends[index]
            } ?: lines.indices.minByOrNull { index ->
                minOf(
                    kotlin.math.abs(cursor - starts[index]),
                    kotlin.math.abs(cursor - ends[index]),
                )
            } ?: return null

            var target = ends[lineIndex]
            if (target <= cursor) {
                lineIndex += 1
                if (lineIndex !in lines.indices) {
                    return textLength.takeIf { it > cursor }
                }
                target = ends[lineIndex]
            }
            target.takeIf { it > cursor }
        } else {
            var lineIndex = lines.indices.lastOrNull { index ->
                cursor >= starts[index] && cursor <= ends[index]
            } ?: lines.indices.minByOrNull { index ->
                minOf(
                    kotlin.math.abs(cursor - starts[index]),
                    kotlin.math.abs(cursor - ends[index]),
                )
            } ?: return null

            var target = starts[lineIndex]
            if (target >= cursor) {
                lineIndex -= 1
                if (lineIndex !in lines.indices) {
                    return 0.takeIf { it < cursor }
                }
                target = starts[lineIndex]
            }
            target.takeIf { it < cursor }
        }
    }

    /**
     * Safe fallback for editors that do not expose character geometry.
     * It uses logical line boundaries and never mutates the live selection.
     */
    fun logicalTarget(
        text: String,
        cursor: Int,
        forward: Boolean,
    ): Int? {
        if (cursor !in 0..text.length) return null
        if ((!forward && cursor == 0) || (forward && cursor == text.length)) return null

        return if (forward) {
            val newline = text.indexOf('\n', cursor)
            val target = if (newline >= 0) newline + 1 else text.length
            target.takeIf { it > cursor }
        } else {
            val currentStart = text.lastIndexOf(
                '\n',
                (cursor - 1).coerceAtLeast(0),
            ).let { if (it < 0) 0 else it + 1 }

            if (currentStart < cursor) {
                currentStart
            } else {
                if (currentStart == 0) return null
                text.lastIndexOf(
                    '\n',
                    (currentStart - 2).coerceAtLeast(0),
                ).let { if (it < 0) 0 else it + 1 }
                    .takeIf { it < cursor }
            }
        }
    }
}
