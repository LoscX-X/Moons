package com.blanoir.moons.client.ui.compose

import java.lang.ref.Reference
import java.nio.ByteBuffer
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Pixmap
import org.jetbrains.skia.Surface
import org.lwjgl.system.MemoryUtil

/** Skia owns the native-format canvas; only completed RGBA pixels cross the upload boundary. */
internal class RasterFrameBuffer(private val width: Int, private val height: Int) : AutoCloseable {
    private val capacity: Int
    private val surface: Surface
    private var upload: ByteBuffer? = null
    private var firstRow = ByteArray(0)
    private var lastRow = ByteArray(0)
    private var closed = false

    init {
        require(width > 0 && height > 0)
        capacity = Math.multiplyExact(Math.multiplyExact(width, height), 4)
        // N32 allows solid HUD drawing to use native premultiplied blitters. On
        // Windows, RGBA forces the generic pipeline implicated by the Lunar crashes.
        surface = Surface.makeRasterN32Premul(width, height)
    }

    val canvas: Canvas
        get() {
            check(!closed)
            return surface.canvas
        }

    fun pack(left: Int, top: Int, width: Int, height: Int): ByteBuffer {
        check(!closed)
        require(left >= 0 && top >= 0 && width > 0 && height > 0)
        require(width <= this.width - left && height <= this.height - top)
        val rowBytes = Math.multiplyExact(width, 4)
        val byteCount = Math.multiplyExact(rowBytes, height)
        var pixels = upload
        if (pixels == null || pixels.capacity() < byteCount) {
            val size = minOf(capacity.toLong(), (byteCount.toLong() + 65_535L) and -65_536L)
            pixels = ByteBuffer.allocateDirect(size.toInt())
            upload = pixels
        }
        pixels.clear()
        pixels.limit(byteCount)
        // The temporary view cannot escape or outlive the JVM-owned upload memory.
        // readPixels handles native BGRA/RGBA channel order and preserves premultiplication.
        try {
            Pixmap.make(
                    ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL),
                    MemoryUtil.memAddress(pixels),
                    rowBytes,
                )
                .use { check(surface.readPixels(it, left, top)) }
        } finally {
            Reference.reachabilityFence(pixels)
        }
        if (firstRow.size < rowBytes) {
            firstRow = ByteArray(rowBytes)
            lastRow = ByteArray(rowBytes)
        }
        // Flip the upload, never the live canvas. Absolute buffer operations check bounds
        // and leave position/limit ready for either graphics backend's staging copy.
        for (row in 0 until height / 2) {
            val first = row * rowBytes
            val last = (height - row - 1) * rowBytes
            pixels.get(first, firstRow, 0, rowBytes)
            pixels.get(last, lastRow, 0, rowBytes)
            pixels.put(first, lastRow, 0, rowBytes)
            pixels.put(last, firstRow, 0, rowBytes)
        }
        return pixels
    }

    override fun close() {
        if (closed) return
        closed = true
        surface.close()
        upload = null
        firstRow = ByteArray(0)
        lastRow = ByteArray(0)
    }
}
