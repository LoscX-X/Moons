package com.blanoir.moons.client.ui.compose

import java.util.Random
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect

/** Real raster pixels, fake GPU transport: checks equivalence without a graphics context. */
object RasterFramePipelineVerification {
    @JvmStatic
    fun main(args: Array<String>) {
        val backend = RecordingBackend()
        val pipeline = RasterFramePipeline(backend)
        val random = Random(0x4D4F4F4E)
        val width = 96
        val height = 64
        try {
            repeat(120) { frame ->
                val boxes =
                    List(1 + random.nextInt(8)) {
                        val left = random.nextInt(width + 20) - 10
                        val top = random.nextInt(height + 20) - 10
                        Rect.makeXYWH(
                            left.toFloat(),
                            top.toFloat(),
                            (1 + random.nextInt(25)).toFloat(),
                            (1 + random.nextInt(25)).toFloat(),
                        )
                    }
                val colors = List(boxes.size) { random.nextInt() or 0x30000000 }
                val draw: (Canvas) -> Unit = { canvas ->
                    Paint().use { paint ->
                        boxes.forEachIndexed { index, box ->
                            paint.color = colors[index]
                            canvas.drawRect(box, paint)
                        }
                    }
                }
                pipeline.render(width, height, contentRegions = { boxes }, draw = draw)
                RasterFrameBuffer(width, height).use { reference ->
                    reference.canvas.clear(0)
                    draw(reference.canvas)
                    val packed = reference.pack(0, 0, width, height)
                    val expected = ByteArray(packed.remaining())
                    packed.get(expected)
                    check(expected.contentEquals(backend.target)) {
                        "Frame $frame differs from whole-canvas rendering"
                    }
                }
            }
            check(backend.creates == 1)
            val retained = backend.target.copyOf()
            val uploads = backend.uploads
            pipeline.render(
                width,
                height,
                redraw = false,
                contentRegions = { error("Retained bounds re-evaluated") },
            ) {
                error("Retained frame redrawn")
            }
            check(retained.contentEquals(backend.target) && backend.uploads == uploads)

            pipeline.render(width, height, contentRegions = { emptyList() }) {}
            check(backend.target.all { it == 0.toByte() } && backend.uploads == uploads)
            pipeline.render(width, height, redraw = false) { error("Empty cached frame redrawn") }
            check(backend.target.all { it == 0.toByte() })

            try {
                pipeline.render(width, height) { canvas ->
                    canvas.translate(7f, 11f)
                    canvas.clear(0xFFFF0000.toInt())
                    error("draw fixture")
                }
                error("Draw failure lost")
            } catch (failure: IllegalStateException) {
                check(failure.message == "draw fixture")
            }
            val marker = Rect.makeXYWH(2f, 3f, 4f, 5f)
            pipeline.render(width, height, redraw = false, contentBounds = { marker }) { canvas ->
                Paint().use { paint ->
                    paint.color = 0xFF00FF00.toInt()
                    canvas.drawRect(marker, paint)
                }
            }
            RasterFrameBuffer(width, height).use { reference ->
                reference.canvas.clear(0)
                Paint().use { paint ->
                    paint.color = 0xFF00FF00.toInt()
                    reference.canvas.drawRect(marker, paint)
                }
                val expected = ByteArray(width * height * 4)
                reference.pack(0, 0, width, height).get(expected)
                check(expected.contentEquals(backend.target)) {
                    "Failed draw left pixels or canvas transform behind"
                }
            }
            backend.device = Any()
            pipeline.render(width, height) {}
            check(backend.creates == 2)
            pipeline.render(width / 2, height / 2) {}
            check(backend.creates == 3)
            val before = backend.creates
            pipeline.render(0, height) { error("Invalid size drawn") }
            backend.available = false
            pipeline.render(width, height) { error("Unavailable target drawn") }
            check(backend.creates == before)
            backend.available = true
            pipeline.close()
            pipeline.close()
            check(!backend.ready)
            pipeline.render(width, height) {}
            check(backend.creates == 4)
        } finally {
            pipeline.close()
        }
        println(
            "MOONS_RASTER_PIPELINE_VERIFIED pixels=120 retained empty failed-draw device resize close"
        )
    }

    private class RecordingBackend : OverlayBackend<ByteArray> {
        var device = Any()
        var available = true
        var creates = 0
        var uploads = 0
        var target = ByteArray(0)
        private var texture: ByteArray? = null
        private var width = 0

        override val deviceIdentity: Any
            get() = device

        override val ready: Boolean
            get() = texture != null

        override fun outputTarget(width: Int, height: Int): ByteArray? {
            if (!available) return null
            target = ByteArray(width * height * 4)
            return target
        }

        override fun create(width: Int, height: Int) {
            this.width = width
            texture = ByteArray(width * height * 4)
            creates++
        }

        override fun upload(surface: RasterFrameBuffer, regions: List<RasterRegion>, height: Int) {
            uploads++
            for (region in regions) {
                val packed = surface.pack(region.left, region.top, region.width, region.height)
                val bytes = ByteArray(packed.remaining())
                packed.get(bytes)
                for (row in 0 until region.height) bytes.copyInto(
                    texture!!,
                    ((height - region.bottom + row) * width + region.left) * 4,
                    row * region.width * 4,
                    (row + 1) * region.width * 4,
                )
            }
        }

        override fun blend(target: ByteArray, region: RasterRegion, height: Int) {
            for (row in 0 until region.height) {
                val offset = ((height - region.bottom + row) * width + region.left) * 4
                texture!!.copyInto(target, offset, offset, offset + region.width * 4)
            }
        }

        override fun close() {}

        override fun reset() {
            texture = null
        }
    }
}
