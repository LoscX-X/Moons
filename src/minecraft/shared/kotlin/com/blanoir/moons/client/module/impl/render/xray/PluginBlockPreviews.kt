package com.blanoir.moons.client.module.impl.render.xray

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.blanoir.moons.client.utils.render.NativeItemIconCapture
import net.minecraft.client.Minecraft
import net.minecraft.world.level.block.state.BlockState
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/** Visible rows only; separate from Xray's world-position cache. */
internal object PluginBlockPreviews {
    private val revision = mutableIntStateOf(0)
    private val images = linkedMapOf<BlockState, Image>()
    private val pending = linkedSetOf<BlockState>()
    private val failed = mutableSetOf<BlockState>()
    private var generation = 0
    private var busy = false
    private var models: Any? = null
    private var scope = ""

    fun get(state: BlockState?): ImageBitmap? {
        revision.intValue
        if (state == null) return null
        val image = images[state]
        if (image == null && state !in failed) pending.add(state)
        return image?.toComposeImageBitmap()
    }

    fun prepareFrame() {
        val client = Minecraft.getInstance()
        val currentModels = client.modelManager.blockStateModelSet
        val currentScope = PluginXrayTargets.scope()
        if (models !== currentModels || scope != currentScope) {
            clear()
            models = currentModels
            scope = currentScope
        }
        if (busy) return
        val request = pending.firstOrNull() ?: return
        pending.remove(request)
        busy = true
        val started = generation
        try {
            val model = PluginBlockPreviewModel.create(request)
            if (model.isEmpty) {
                busy = false
                failed.add(request)
                revision.intValue++
                return
            }
            NativeItemIconCapture.capture(model) { pixels ->
                client.execute {
                    if (started == generation) {
                        busy = false
                        pending.remove(request)
                        val size = NativeItemIconCapture.SIZE
                        images[request] =
                            Image.makeRaster(
                                ImageInfo(size, size, ColorType.RGBA_8888, ColorAlphaType.PREMUL),
                                pixels,
                                size * 4,
                            )
                        if (images.size > 128) images.remove(images.keys.first())?.close()
                        revision.intValue++
                    }
                }
            }
        } catch (_: RuntimeException) {
            busy = false
            failed.add(request)
            revision.intValue++
        }
    }

    fun cancelPending(state: BlockState?) {
        pending.remove(state)
    }

    fun unavailable(state: BlockState?): Boolean {
        revision.intValue
        return state == null || state in failed
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
