package dev.diego.expanda.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectionGestureMovementTest {
    @Test
    fun `up on a single line selects toward the beginning instead of stalling`() {
        assertEquals(0, SelectionGestureMovement.lineTarget("Hola", 4, forward = false))
    }

    @Test
    fun `down on a single line selects toward the end instead of stalling`() {
        assertEquals(4, SelectionGestureMovement.lineTarget("Hola", 0, forward = true))
    }

    @Test
    fun `up moves to same column on previous logical line`() {
        assertEquals(4, SelectionGestureMovement.lineTarget("Hola\nHola", 9, forward = false))
    }

    @Test
    fun `repeated up clamps at beginning without bouncing`() {
        assertEquals(0, SelectionGestureMovement.lineTarget("Hola\nHola", 4, forward = false))
        assertNull(SelectionGestureMovement.lineTarget("Hola\nHola", 0, forward = false))
    }

    @Test
    fun `down moves to same column on next logical line`() {
        assertEquals(5, SelectionGestureMovement.lineTarget("Hola\nHola", 0, forward = true))
    }

    @Test
    fun `shorter adjacent lines clamp to their edge`() {
        assertEquals(2, SelectionGestureMovement.lineTarget("Hi\nHello", 8, forward = false))
        assertEquals(8, SelectionGestureMovement.lineTarget("Hello\nHi", 5, forward = true))
    }
}
