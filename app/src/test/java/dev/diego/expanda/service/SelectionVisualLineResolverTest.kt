package dev.diego.expanda.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectionVisualLineResolverTest {
    private fun line(
        startIndex: Int,
        count: Int,
        top: Float,
    ): List<SelectionVisualLineResolver.Box> =
        (0 until count).map { offset ->
            val left = offset * 10f
            SelectionVisualLineResolver.Box(
                index = startIndex + offset,
                left = left,
                top = top,
                right = left + 10f,
                bottom = top + 16f,
            )
        }

    @Test
    fun `up moves to visual line boundary instead of preserving an approximate column`() {
        val boxes = line(0, 4, 0f) + line(5, 4, 20f)
        assertEquals(
            5,
            SelectionVisualLineResolver.target(
                boxes = boxes,
                textLength = 9,
                cursor = 7,
                forward = false,
            ),
        )
    }

    @Test
    fun `down moves to visual line boundary instead of preserving an approximate column`() {
        val boxes = line(0, 4, 0f) + line(5, 4, 20f)
        assertEquals(
            5,
            SelectionVisualLineResolver.target(
                boxes = boxes,
                textLength = 9,
                cursor = 2,
                forward = true,
            ),
        )
    }

    @Test
    fun `repeated movement advances one complete wrapped line without skipping`() {
        val boxes = line(0, 4, 0f) + line(4, 4, 20f) + line(8, 4, 40f)
        assertEquals(
            4,
            SelectionVisualLineResolver.target(boxes, 12, 2, forward = true),
        )
        assertEquals(
            8,
            SelectionVisualLineResolver.target(boxes, 12, 4, forward = true),
        )
        assertEquals(
            4,
            SelectionVisualLineResolver.target(boxes, 12, 8, forward = false),
        )
        assertEquals(
            0,
            SelectionVisualLineResolver.target(boxes, 12, 4, forward = false),
        )
    }

    @Test
    fun `vertical movement saturates at absolute text boundaries`() {
        val boxes = line(0, 4, 0f) + line(4, 4, 20f)
        assertNull(
            SelectionVisualLineResolver.target(boxes, 8, 0, forward = false),
        )
        assertNull(
            SelectionVisualLineResolver.target(boxes, 8, 8, forward = true),
        )
    }

    @Test
    fun `logical fallback uses line boundaries without editor probing`() {
        assertEquals(
            5,
            SelectionVisualLineResolver.logicalTarget("Hola\nMundo", 2, forward = true),
        )
        assertEquals(
            5,
            SelectionVisualLineResolver.logicalTarget("Hola\nMundo", 8, forward = false),
        )
        assertEquals(
            0,
            SelectionVisualLineResolver.logicalTarget("Hola\nMundo", 5, forward = false),
        )
        assertNull(
            SelectionVisualLineResolver.logicalTarget("Hola", 0, forward = false),
        )
        assertNull(
            SelectionVisualLineResolver.logicalTarget("Hola", 4, forward = true),
        )
    }
}
