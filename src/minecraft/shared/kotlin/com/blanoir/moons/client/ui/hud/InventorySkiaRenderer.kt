package com.blanoir.moons.client.ui.hud

import com.blanoir.moons.client.module.impl.render.InventorySee
import com.blanoir.moons.client.utils.render.ItemIconImages
import net.minecraft.client.Minecraft
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Font
import org.jetbrains.skia.Paint
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect

/** Inventory panel, slots and decorations draw only into Moons' final-frame Skia surface. */
internal object InventorySkiaRenderer {
    private val icons = ItemIconImages()
    private var visible = false

    fun prepare(snapshot: InventorySee.Snapshot) {
        if (!snapshot.visible()) {
            if (visible) icons.clear()
            visible = false
            return
        }
        visible = true
        icons.retainRequests(snapshot.items())
        snapshot.items().forEach { icons.get(it) }
        icons.prepareFrame()
    }

    fun draw(canvas: Canvas, snapshot: InventorySee.Snapshot, font: Font, paint: Paint): Rect {
        val guiScale = Minecraft.getInstance().window.guiScale.coerceAtLeast(1).toFloat()
        val scale = guiScale * snapshot.scale().toFloat()
        val x = snapshot.bounds().x().toFloat() * guiScale
        val y = snapshot.bounds().y().toFloat() * guiScale
        val rect = Rect.makeXYWH(x, y, 188 * scale, 68 * scale)
        canvas.save()
        try {
            canvas.translate(x, y)
            canvas.scale(scale, scale)
            paint.color = 0xd80c0d10.toInt()
            canvas.drawRRect(RRect.makeXYWH(0f, 0f, 188f, 68f, 6f), paint)
            font.size = 8f
            snapshot.items().forEachIndexed { index, stack ->
                val left = 5f + (index % 9) * 20f
                val top = 5f + (index / 9) * 20f
                paint.color = 0x761f2025
                canvas.drawRRect(RRect.makeXYWH(left, top, 18f, 18f, 3f), paint)
                if (!stack.isEmpty) {
                    val image = icons.get(stack)
                    if (image != null)
                        canvas.drawImageRect(image, Rect.makeXYWH(left + 1, top + 1, 16f, 16f))
                    if (stack.isBarVisible) {
                        paint.color = 0xff000000.toInt()
                        canvas.drawRect(Rect.makeXYWH(left + 3, top + 14, 13f, 2f), paint)
                        paint.color = 0xff000000.toInt() or stack.barColor
                        canvas.drawRect(
                            Rect.makeXYWH(
                                left + 3,
                                top + 14,
                                stack.barWidth.coerceIn(0, 13).toFloat(),
                                1f,
                            ),
                            paint,
                        )
                    }
                    if (stack.count > 1) {
                        val count = stack.count.toString()
                        val textX = left + 18 - font.measureTextWidth(count)
                        paint.color = 0xff000000.toInt()
                        canvas.drawString(count, textX + 0.75f, top + 17.75f, font, paint)
                        paint.color = 0xffeeeeee.toInt()
                        canvas.drawString(count, textX, top + 17, font, paint)
                    }
                }
            }
        } finally {
            canvas.restore()
        }
        return rect
    }

    fun close() {
        icons.clear()
        visible = false
    }
}
