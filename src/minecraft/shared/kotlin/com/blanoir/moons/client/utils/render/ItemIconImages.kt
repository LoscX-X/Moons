package com.blanoir.moons.client.utils.render

import androidx.compose.runtime.mutableIntStateOf
import net.minecraft.client.Minecraft
import net.minecraft.world.item.ItemStack
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/** Component-aware offscreen images shared by independently drawn HUDs and Compose pickers. */
internal class ItemIconImages(private val capacity: Int = 128) {
    private class Key(val stack: ItemStack) {
        override fun hashCode() = 31 * ItemStack.hashItemAndComponents(stack) + stack.count

        override fun equals(other: Any?) = other is Key && ItemStack.matches(stack, other.stack)
    }

    private val revision = mutableIntStateOf(0)
    private val images = linkedMapOf<Key, Image>()
    private val pending = linkedSetOf<Key>()
    private val failed = linkedSetOf<Key>()
    private var busy = false
    private var generation = 0
    private var models: Any? = null
    private var level: Any? = null

    fun get(stack: ItemStack): Image? {
        revision.intValue
        if (stack.isEmpty) return null
        val probe = Key(stack)
        val image = images[probe]
        if (image == null && probe !in failed && probe !in pending) pending.add(Key(stack.copy()))
        return image
    }

    fun cancelPending(stack: ItemStack) {
        pending.remove(Key(stack))
    }

    fun retainRequests(stacks: List<ItemStack>) {
        if (pending.isEmpty()) return
        val retained = HashSet<Key>(stacks.size)
        stacks.forEach { if (!it.isEmpty) retained.add(Key(it)) }
        pending.retainAll(retained)
    }

    fun prepareFrame() {
        val client = Minecraft.getInstance()
        val current = client.modelManager.blockStateModelSet
        if (models !== current || level !== client.level) {
            clear()
            models = current
            level = client.level
        }
        if (busy) return
        val request = pending.firstOrNull() ?: return
        pending.remove(request)
        busy = true
        val started = generation
        NativeItemIconCapture.capture(request.stack) { pixels ->
            client.execute {
                if (started == generation) {
                    busy = false
                    pending.remove(request)
                    if (pixels == null) {
                        failed.add(request)
                        if (failed.size > capacity) failed.remove(failed.first())
                    } else {
                        val size = NativeItemIconCapture.SIZE
                        images[request] =
                            Image.makeRaster(
                                ImageInfo(size, size, ColorType.RGBA_8888, ColorAlphaType.PREMUL),
                                pixels,
                                size * 4,
                            )
                        if (images.size > capacity) images.remove(images.keys.first())?.close()
                    }
                    revision.intValue++
                }
            }
        }
    }

    fun clear() {
        generation++
        busy = false
        pending.clear()
        failed.clear()
        images.values.forEach { it.close() }
        images.clear()
        revision.intValue++
    }
}
