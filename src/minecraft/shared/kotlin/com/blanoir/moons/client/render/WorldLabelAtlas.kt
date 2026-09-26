package com.blanoir.moons.client.render

import java.util.EnumMap
import kotlin.math.ceil
import org.jetbrains.skia.*

/** Bounded, renderer-owned white text atlas; only changed tiles cross the CPU/GPU boundary. */
object WorldLabelAtlas {
    const val SIZE = 2048
    private const val RASTER_SCALE = 2f
    private const val HEIGHT = 32
    private const val PADDING = 2

    data class Tile(val x: Int, val y: Int, val width: Int, val advance: Float)

    data class Upload(val x: Int, val y: Int, val width: Int, val height: Int, val rgba: ByteArray)

    private val tiles =
        EnumMap<WorldLabelFont, MutableMap<String, Tile>>(WorldLabelFont::class.java)
    private val uploads = ArrayList<Upload>()
    private var nextX = 4
    private var nextY = 0
    private val resources = EnumMap<WorldLabelFont, Fonts>(WorldLabelFont::class.java)

    @JvmStatic
    @JvmOverloads
    fun prepare(values: Collection<String>, mode: WorldLabelFont = WorldLabelFont.SMOOTH) {
        val fonts = resources.getOrPut(mode) { Fonts(mode) }
        val distinct = values.asSequence().filter { it.isNotEmpty() }.distinct().toList()
        // Repack before exposing any UVs to this batch, so resetting cannot invalidate its
        // vertices.
        if (!fits(distinct, fonts, tiles[mode] ?: emptyMap())) resetTiles()
        if (tiles.isEmpty() && uploads.isEmpty()) whitePixel()
        val fontTiles = tiles.getOrPut(mode) { LinkedHashMap() }
        for (text in distinct) {
            if (text in fontTiles) continue
            val parts = fonts.parts(text)
            val advance = parts.sumOf { it.first.measureTextWidth(it.second).toDouble() }.toFloat()
            val width = (ceil(advance).toInt() + PADDING * 2).coerceIn(4, SIZE)
            if (nextX + width > SIZE) {
                nextX = 0
                nextY += HEIGHT
            }
            if (nextY + HEIGHT > SIZE) continue // Extremely crowded frames remain bounded.
            val info = ImageInfo(width, HEIGHT, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
            Surface.makeRaster(info).use { surface ->
                surface.canvas.clear(0)
                var x = PADDING.toFloat()
                for ((font, value) in parts) {
                    surface.canvas.drawString(value, x, fonts.baseline, font, fonts.paint)
                    x += font.measureTextWidth(value)
                }
                Bitmap().use { bitmap ->
                    bitmap.allocPixels(info)
                    check(surface.readPixels(bitmap, 0, 0))
                    val pixels = requireNotNull(bitmap.readPixels(info, width * 4, 0, 0))
                    uploads.add(Upload(nextX, nextY, width, HEIGHT, pixels))
                }
            }
            fontTiles[text] = Tile(nextX, nextY, width, advance / RASTER_SCALE)
            nextX += width
        }
    }

    @JvmStatic
    @JvmOverloads
    fun tile(text: String, mode: WorldLabelFont = WorldLabelFont.SMOOTH): Tile? =
        tiles[mode]?.get(text)

    @JvmStatic fun takeUploads(): List<Upload> = uploads.toList().also { uploads.clear() }

    private fun fits(values: List<String>, fonts: Fonts, fontTiles: Map<String, Tile>): Boolean {
        var x = nextX
        var y = nextY
        for (text in values) {
            if (text in fontTiles) continue
            val width =
                (ceil(fonts.parts(text).sumOf { it.first.measureTextWidth(it.second).toDouble() })
                        .toInt() + 4)
                    .coerceIn(4, SIZE)
            if (x + width > SIZE) {
                x = 0
                y += HEIGHT
            }
            if (y + HEIGHT > SIZE) return false
            x += width
        }
        return true
    }

    private fun whitePixel() {
        uploads.add(Upload(0, 0, 2, 2, ByteArray(16) { -1 }))
    }

    private fun resetTiles() {
        tiles.clear()
        uploads.clear()
        nextX = 4
        nextY = 0
        whitePixel()
    }

    @JvmStatic
    fun close() {
        resources.values.forEach { it.close() }
        resources.clear()
        tiles.clear()
        uploads.clear()
        nextX = 4
        nextY = 0
    }

    private class Fonts(mode: WorldLabelFont) : AutoCloseable {
        private val pixel = mode == WorldLabelFont.MINECRAFT
        val baseline = if (pixel) 20f else 22f
        private val data =
            Data.makeFromBytes(
                requireNotNull(
                        WorldLabelAtlas::class
                            .java
                            .classLoader
                            .getResourceAsStream(
                                if (pixel) "assets/moons/font/minecraft-ascii.ttf"
                                else "assets/moons/font/inter-frozen-medium.otf"
                            )
                    )
                    .use { it.readBytes() }
            )
        private val face = requireNotNull(FontMgr.default.makeFromData(data))
        private val primary =
            Font(face, if (pixel) 16f else 18f).apply {
                edging = if (pixel) FontEdging.ALIAS else FontEdging.ANTI_ALIAS
                if (pixel) hinting = FontHinting.NONE
                isSubpixel = !pixel
            }
        private val fallbacks = LinkedHashMap<Int, Pair<Typeface, Font>>()
        val paint =
            Paint().apply {
                color = -1
                isAntiAlias = !pixel
            }

        fun parts(text: String): List<Pair<Font, String>> {
            val result = ArrayList<Pair<Font, String>>()
            var current = primary
            val run = StringBuilder()
            val points = text.codePoints().limit(256).toArray()
            for (point in points) {
                val font =
                    if (primary.getUTF32Glyph(point).toInt() != 0) primary else fallback(point)
                if (font !== current && run.isNotEmpty()) {
                    result.add(current to run.toString())
                    run.setLength(0)
                }
                current = font
                run.appendCodePoint(point)
            }
            if (run.isNotEmpty()) result.add(current to run.toString())
            return result
        }

        private fun fallback(point: Int): Font {
            fallbacks[point]?.let {
                return it.second
            }
            if (fallbacks.size >= 128) return primary
            val typeface =
                FontMgr.default.matchFamilyStyleCharacter(
                    null,
                    FontStyle.NORMAL,
                    arrayOf("zh-CN"),
                    point,
                ) ?: return primary
            return Font(typeface, if (pixel) 16f else 18f)
                .apply { edging = if (pixel) FontEdging.ALIAS else FontEdging.ANTI_ALIAS }
                .also { fallbacks[point] = typeface to it }
        }

        override fun close() {
            fallbacks.values.forEach {
                it.second.close()
                it.first.close()
            }
            paint.close()
            primary.close()
            face.close()
            data.close()
        }
    }
}
