package com.blanoir.tempos.client.ui.hud

import com.blanoir.tempos.client.render.item.NativeItemIconCapture
import com.blanoir.tempos.client.ui.paint.UiBackend
import com.blanoir.tempos.client.ui.paint.UiPainter
import com.blanoir.tempos.client.ui.paint.UiTexture
import net.minecraft.client.Minecraft
import net.minecraft.world.item.ItemStack
import org.lwjgl.system.MemoryUtil

/** Inventory and optional hotbar slots share one atlas of game-rendered item icons. */
internal class InventoryIconAtlas : AutoCloseable {
    private val side = NativeItemIconCapture.SIZE
    private val stride = side + 2
    private val stacks = arrayOfNulls<ItemStack>(36)
    private val ready = BooleanArray(36)
    private val pending = BooleanArray(36)
    private var contents: List<ItemStack>? = null
    private var atlas: UiTexture? = null
    private var owner: UiBackend? = null
    private var models: Any? = null
    private var level: Any? = null
    private var generation = 0
    private var busy = false

    fun prepare(backend: UiBackend, client: Minecraft, items: List<ItemStack>) {
        val currentModels = client.modelManager.blockStateModelSet
        if (owner !== backend || models !== currentModels || level !== client.level) {
            close()
            owner = backend
            models = currentModels
            level = client.level
        }
        if (contents !== items) {
            for (i in stacks.indices) {
                val previous = stacks[i]
                val next = items.getOrNull(i) ?: ItemStack.EMPTY
                if (previous != null && ItemStack.matches(previous,next)) continue
                stacks[i] = next
                ready[i] = false
                pending[i] = !next.isEmpty
            }
            contents = items
        }
        if (busy) return
        val slot = pending.indexOf(true)
        if (slot < 0) return
        val stack = stacks[slot] ?: return
        val started = generation
        busy = true
        pending[slot] = false
        NativeItemIconCapture.capture(stack) { pixels ->
            client.execute {
                if (started == generation) {
                    try {
                        if (stack === stacks[slot] && pixels != null) {
                            val page = atlas ?: backend.createTexture(9*stride,4*stride).also { atlas = it }
                            upload(page,slot,pixels)
                            ready[slot] = true
                        }
                    } finally {
                        busy = false
                    }
                }
            }
        }
    }

    private fun upload(page: UiTexture, slot: Int, pixels: ByteArray) {
        val buffer = MemoryUtil.memCalloc(stride * stride * 4)
        try {
            // The game capture is premultiplied. UiPainter applies source alpha on composition.
            for (y in 0 until side) for (x in 0 until side) {
                val src = (y * side + x) * 4
                val dst = ((y + 1) * stride + x + 1) * 4
                val alpha = pixels[src + 3].toInt() and 255
                if (alpha == 0) continue
                for (component in 0..2) {
                    val v = pixels[src + component].toInt() and 255
                    buffer.put(dst + component, ((v * 255 + alpha / 2) / alpha).coerceAtMost(255).toByte())
                }
                buffer.put(dst+3,alpha.toByte())
            }
            page.update(slot%9*stride,slot/9*stride,stride,stride,buffer)
        } finally {
            MemoryUtil.memFree(buffer)
        }
    }

    fun draw(p: UiPainter, slot: Int, x: Float, y: Float, size: Float) {
        val page = atlas ?: return
        if (!ready[slot]) return
        val tileX = (slot % 9 * stride + 1).toFloat()
        val tileY = (slot / 9 * stride + 1).toFloat()
        p.image(page,x,y,size,size,tileX/(9*stride),tileY/(4*stride),
            (tileX+side)/(9*stride),(tileY+side)/(4*stride),-1)
    }

    override fun close() {
        generation++
        busy = false
        atlas?.close()
        atlas = null
        owner = null
        models = null
        level = null
        contents = null
        stacks.fill(null)
        ready.fill(false)
        pending.fill(false)
    }
}
