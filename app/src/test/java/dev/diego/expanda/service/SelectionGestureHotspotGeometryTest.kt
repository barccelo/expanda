package dev.diego.expanda.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionGestureHotspotGeometryTest {
    @Test
    fun `legacy geometry migrates to the same physical bottom gap`() {
        val migrated = SelectionGestureHotspotGeometry.migrateLegacy(
            imeHeightPx = 600,
            yFraction = 0.63f,
            heightFraction = 0.19f,
            minimumHeightPx = 44,
        )

        assertEquals(114, migrated.heightPx)
        assertEquals(108, migrated.bottomOffsetPx)
        assertEquals(
            778,
            SelectionGestureHotspotGeometry.topFromBottom(
                imeTopPx = 200,
                imeBottomPx = 1000,
                heightPx = migrated.heightPx,
                bottomOffsetPx = migrated.bottomOffsetPx,
            ),
        )
    }

    @Test
    fun `expanding IME upward does not move bottom anchored hotspot`() {
        val normalTop = SelectionGestureHotspotGeometry.topFromBottom(
            imeTopPx = 400,
            imeBottomPx = 1000,
            heightPx = 110,
            bottomOffsetPx = 90,
        )
        val expandedTop = SelectionGestureHotspotGeometry.topFromBottom(
            imeTopPx = 100,
            imeBottomPx = 1000,
            heightPx = 110,
            bottomOffsetPx = 90,
        )

        assertEquals(800, normalTop)
        assertEquals(normalTop, expandedTop)
    }

    @Test
    fun `legacy migration waits for normal keyboard aspect`() {
        assertTrue(
            SelectionGestureHotspotGeometry.canMigrateLegacy(
                imeWidthPx = 720,
                imeHeightPx = 600,
                maxAspectRatio = 0.98f,
            ),
        )
        assertFalse(
            SelectionGestureHotspotGeometry.canMigrateLegacy(
                imeWidthPx = 720,
                imeHeightPx = 900,
                maxAspectRatio = 0.98f,
            ),
        )
    }
}
