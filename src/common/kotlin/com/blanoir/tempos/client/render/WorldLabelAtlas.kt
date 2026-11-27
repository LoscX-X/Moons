package com.blanoir.tempos.client.render

import java.util.EnumMap
import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import kotlin.math.ceil

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
            val advance = parts.sumOf { fonts.width(it.first, it.second) }.toFloat()
            val width = (ceil(advance).toInt() + PADDING * 2).coerceIn(4, SIZE)
            if (nextX + width > SIZE) {
                nextX = 0
                nextY += HEIGHT
            }
            if (nextY + HEIGHT > SIZE) continue // Extremely crowded frames remain bounded.
            val image = BufferedImage(width, HEIGHT, BufferedImage.TYPE_INT_ARGB_PRE)
            val graphics = image.createGraphics()
            try {
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    if (fonts.pixel) RenderingHints.VALUE_TEXT_ANTIALIAS_OFF else RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
                graphics.color = Color.WHITE
                var x = PADDING.toFloat()
                for ((font, value) in parts) {
                    graphics.font = font
                    graphics.drawString(value, x, fonts.baseline)
                    x += fonts.width(font, value).toFloat()
                }
            } finally {
                graphics.dispose()
            }
            // Read the raw premultiplied raster: getRGB would unpremultiply edge pixels.
            val pixels = (image.raster.dataBuffer as DataBufferInt).data
            val rgba = ByteArray(width * HEIGHT * 4)
            for (index in pixels.indices) {
                val argb = pixels[index]
                val offset = index * 4
                rgba[offset] = (argb ushr 16).toByte()
                rgba[offset + 1] = (argb ushr 8).toByte()
                rgba[offset + 2] = argb.toByte()
                rgba[offset + 3] = (argb ushr 24).toByte()
            }
            uploads.add(Upload(nextX, nextY, width, HEIGHT, rgba))
            image.flush()
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
                (ceil(fonts.parts(text).sumOf { fonts.width(it.first, it.second) })
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
        val pixel = mode == WorldLabelFont.MINECRAFT
        val baseline = if (pixel) 20f else 22f
        private val size = if (pixel) 16f else 18f
        private val metrics = FontRenderContext(null, !pixel, true)
        private val primary =
            requireNotNull(WorldLabelAtlas::class.java.classLoader.getResourceAsStream(
                if (pixel) "assets/tempos/font/minecraft-ascii.ttf"
                else "assets/tempos/font/inter-frozen-medium.otf"))
                .use { Font.createFont(Font.TRUETYPE_FONT, it) }.deriveFont(size)
        private val fallbacks = LinkedHashMap<Int, Font>()
        private val systemFonts by lazy {
            // Prefer a logical composite font, which retains the JDK's system-script fallback.
            listOf(Font(Font.DIALOG, Font.PLAIN, 1)) +
                GraphicsEnvironment.getLocalGraphicsEnvironment().allFonts.toList()
        }

        fun width(font: Font, value: String): Double = font.getStringBounds(value, metrics).width

        fun parts(text: String): List<Pair<Font, String>> {
            val result = ArrayList<Pair<Font, String>>()
            var current = primary
            val run = StringBuilder()
            val points = text.codePoints().limit(256).toArray()
            for (point in points) {
                val font =
                    if (primary.canDisplay(point)) primary else fallback(point)
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
            fallbacks[point]?.let { return it }
            if (fallbacks.size >= 128) return primary
            val font = systemFonts.firstOrNull { it.canDisplay(point) } ?: return primary
            return font.deriveFont(size).also { fallbacks[point] = it }
        }

        override fun close() {
            fallbacks.clear()
        }
    }
}
