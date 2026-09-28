package dev.diego.expanda.service

import kotlin.math.abs
import kotlin.math.max

/**
 * Resolves the caret position on the visual line immediately above/below without
 * changing the editor selection. Character bounds come from AccessibilityNodeInfo
 * extra text-location data.
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
        val usable = boxes
            .filter { it.index in 0 until textLength && it.bottom > it.top }
            .sortedWith(compareBy<Box> { it.centerY }.thenBy { it.left })
        if (usable.isEmpty()) return null

        val exact = usable.firstOrNull { it.index == cursor }
        val previous = usable.firstOrNull { it.index == cursor - 1 }
        val caretBox = exact ?: previous ?: usable.minByOrNull { abs(it.index - cursor) } ?: return null
        val caretX = if (exact != null) exact.left else caretBox.right
        val caretY = caretBox.centerY

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

        val currentLineIndex = lines.indices.minByOrNull { abs(lines[it].centerY - caretY) }
            ?: return null
        val targetLineIndex = currentLineIndex + if (forward) 1 else -1
        if (targetLineIndex !in lines.indices) {
            val boundary = if (forward) textLength else 0
            return boundary.takeIf { it != cursor }
        }

        val candidates = buildList {
            lines[targetLineIndex].boxes.forEach { box ->
                add(box.index to box.left)
                add((box.index + 1).coerceAtMost(textLength) to box.right)
            }
        }
            .filter { (index, _) ->
                if (forward) index > cursor else index < cursor
            }
            .distinctBy { it.first }

        return candidates.minWithOrNull(
            compareBy<Pair<Int, Float>> { (_, x) -> abs(x - caretX) }
                .thenBy { (index, _) -> abs(index - cursor) },
        )?.first
    }
}
