package com.blanoir.moons.client.ui.compose

import kotlin.math.ceil
import kotlin.math.floor
import org.jetbrains.skia.Rect

/** Non-overlapping pixel regions keep distant HUD widgets out of each other's uploads. */
internal data class RasterRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int
        get() = right - left

    val height: Int
        get() = bottom - top

    fun rect(): Rect =
        Rect.makeLTRB(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

    private fun overlaps(other: RasterRegion): Boolean =
        left <= other.right && right >= other.left && top <= other.bottom && bottom >= other.top

    private fun union(other: RasterRegion) =
        RasterRegion(
            minOf(left, other.left),
            minOf(top, other.top),
            maxOf(right, other.right),
            maxOf(bottom, other.bottom),
        )

    companion object {
        fun collect(bounds: List<Rect>, width: Int, height: Int): List<RasterRegion> {
            val regions = ArrayList<RasterRegion>(bounds.size)
            for (boundsRect in bounds) {
                if (
                    !boundsRect.left.isFinite() ||
                        !boundsRect.top.isFinite() ||
                        !boundsRect.right.isFinite() ||
                        !boundsRect.bottom.isFinite()
                )
                    continue
                var region =
                    RasterRegion(
                        floor(boundsRect.left).toInt().coerceIn(0, width),
                        floor(boundsRect.top).toInt().coerceIn(0, height),
                        ceil(boundsRect.right).toInt().coerceIn(0, width),
                        ceil(boundsRect.bottom).toInt().coerceIn(0, height),
                    )
                if (region.width <= 0 || region.height <= 0) continue
                // Restart after expansion: its bounding box can now touch a previous
                // region. A pixel must never be alpha-blended twice.
                var index = 0
                while (index < regions.size) {
                    if (region.overlaps(regions[index])) {
                        region = region.union(regions.removeAt(index))
                        index = 0
                    } else index++
                }
                regions.add(region)
            }
            return regions
        }
    }
}
