package dev.diego.expanda.service

import kotlin.math.roundToInt

/**
 * Keeps the gesture hotspot attached to the keyboard's bottom edge even when
 * an IME expands its window upward for stickers, GIFs, search, clipboard, etc.
 */
internal object SelectionGestureHotspotGeometry {
    data class Anchored(
        val bottomOffsetPx: Int,
        val heightPx: Int,
    )

    fun canMigrateLegacy(
        imeWidthPx: Int,
        imeHeightPx: Int,
        maxAspectRatio: Float,
    ): Boolean {
        if (imeWidthPx <= 0 || imeHeightPx <= 0) return false
        return imeHeightPx.toFloat() / imeWidthPx.toFloat() <= maxAspectRatio
    }

    fun migrateLegacy(
        imeHeightPx: Int,
        yFraction: Float,
        heightFraction: Float,
        minimumHeightPx: Int,
    ): Anchored {
        if (imeHeightPx <= 0) return Anchored(0, minimumHeightPx.coerceAtLeast(1))

        val height = (imeHeightPx * heightFraction)
            .roundToInt()
            .coerceAtLeast(minimumHeightPx)
            .coerceAtMost(imeHeightPx)
        val top = (imeHeightPx * yFraction)
            .roundToInt()
            .coerceIn(0, (imeHeightPx - height).coerceAtLeast(0))
        val bottomOffset = (imeHeightPx - (top + height)).coerceAtLeast(0)
        return Anchored(
            bottomOffsetPx = bottomOffset,
            heightPx = height,
        )
    }

    fun topFromBottom(
        imeTopPx: Int,
        imeBottomPx: Int,
        heightPx: Int,
        bottomOffsetPx: Int,
    ): Int {
        val imeHeight = (imeBottomPx - imeTopPx).coerceAtLeast(0)
        val height = heightPx.coerceIn(0, imeHeight)
        val bottomOffset = bottomOffsetPx.coerceIn(
            0,
            (imeHeight - height).coerceAtLeast(0),
        )
        return (imeBottomPx - bottomOffset - height).coerceIn(
            imeTopPx,
            (imeBottomPx - height).coerceAtLeast(imeTopPx),
        )
    }
}
