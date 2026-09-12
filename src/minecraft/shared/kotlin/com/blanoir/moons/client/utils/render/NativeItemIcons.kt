package com.blanoir.moons.client.utils.render

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.ItemStack
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/** Only visible picker entries request icons; vanilla rendering runs before the Compose frame. */
internal object NativeItemIcons {
    private val revision = mutableIntStateOf(0)
    private val images = linkedMapOf<String, Image>()
    private val pending = linkedMapOf<String, ItemStack>()
    private var generation = 0
    private var busy = false
    private var resources: Any? = null

    fun get(stack: ItemStack): ImageBitmap? {
        revision.intValue
        if (stack.isEmpty) return null
        val id = BuiltInRegistries.ITEM.getKey(stack.item).toString()
        val image = images[id]
        if (image == null) pending.putIfAbsent(id, stack)
        return image?.toComposeImageBitmap()
    }

    fun prepareFrame() {
        val manager = Minecraft.getInstance().resourceManager
        if (resources !== manager) {
            clear()
            resources = manager
        }
        if (busy) return
        val request = pending.entries.firstOrNull() ?: return
        pending.remove(request.key)
        busy = true
        val started = generation
        NativeItemIconCapture.capture(request.value) { pixels ->
            Minecraft.getInstance().execute {
                if (started == generation) {
                    busy = false
                    pending.remove(request.key)
                    val size = NativeItemIconCapture.SIZE
                    images[request.key] =
                        Image.makeRaster(
                            ImageInfo(size, size, ColorType.RGBA_8888, ColorAlphaType.PREMUL),
                            pixels,
                            size * 4,
                        )
                    if (images.size > 256) images.remove(images.keys.first())?.close()
                    revision.intValue++
                }
            }
        }
    }

    fun cancelPending(stack: ItemStack) {
        if (!stack.isEmpty) pending.remove(BuiltInRegistries.ITEM.getKey(stack.item).toString())
    }

    fun clear() {
        revision.intValue++
        generation++
        busy = false
        pending.clear()
        images.values.forEach { it.close() }
        images.clear()
    }
}
