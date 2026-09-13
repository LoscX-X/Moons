package com.blanoir.moons.client.utils.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import net.minecraft.world.item.ItemStack

/** Compose adapter for the component-aware offscreen icon cache. */
internal object NativeItemIcons {
    private val images = ItemIconImages(256)

    fun get(stack: ItemStack): ImageBitmap? = images.get(stack)?.toComposeImageBitmap()

    fun prepareFrame() = images.prepareFrame()

    fun cancelPending(stack: ItemStack) = images.cancelPending(stack)

    fun clear() = images.clear()
}
