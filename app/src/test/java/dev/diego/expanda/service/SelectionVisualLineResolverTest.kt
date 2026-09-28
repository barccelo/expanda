package dev.diego.expanda.service

import org.junit.Assert.assertEquals
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
    fun `moves to same visual column on previous line`() {
        val boxes = line(0, 4, 0f) + line(5, 4, 20f)
        assertEquals(
            2,
            SelectionVisualLineResolver.target(
                boxes = boxes,
                textLength = 9,
                cursor = 7,
                forward = false,
            ),
        )
    }

    @Test
    fun `moves to same visual column on next line`() {
        val boxes = line(0, 4, 0f) + line(5, 4, 20f)
        assertEquals(
            7,
            SelectionVisualLineResolver.target(
                boxes = boxes,
                textLength = 9,
                cursor = 2,
                forward = true,
            ),
        )
    }

    @Test
    fun `single visual line moves to text boundary`() {
        val boxes = line(0, 4, 0f)
        assertEquals(
            0,
            SelectionVisualLineResolver.target(
                boxes = boxes,
                textLength = 4,
                cursor = 2,
                forward = false,
            ),
        )
        assertEquals(
            4,
            SelectionVisualLineResolver.target(
                boxes = boxes,
                textLength = 4,
                cursor = 2,
                forward = true,
            ),
        )
    }

    @Test
    fun `short adjacent line clamps to nearest available caret`() {
        val boxes = line(0, 5, 0f) + line(6, 2, 20f)
        assertEquals(
            8,
            SelectionVisualLineResolver.target(
                boxes = boxes,
                textLength = 8,
                cursor = 4,
                forward = true,
            ),
        )
    }
}
