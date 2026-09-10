package com.blanoir.moons.client.ui.clickgui

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.blanoir.moons.client.module.framework.ModuleCategories
import com.blanoir.moons.client.module.framework.ModuleRegistry
import java.util.concurrent.atomic.AtomicBoolean
import org.jetbrains.skia.Surface

/**
 * Warms Compose, text shaping and icons on a CPU canvas, without touching OpenGL or live modules.
 */
object ClickGuiWarmup {
    private val started = AtomicBoolean()
    @Volatile private var cancelled = false
    @Volatile private var worker: Thread? = null

    @JvmStatic
    fun start() {
        if (!started.compareAndSet(false, true)) return
        worker =
            Thread.ofPlatform()
                .daemon()
                .name("moons-clickgui-warmup")
                .unstarted {
                    try {
                        if (!cancelled) renderPreview()
                    } catch (failure: Exception) {
                        System.err.println("[client] ClickGUI warmup skipped: ${failure.message}")
                    } catch (failure: LinkageError) {
                        System.err.println(
                            "[client] ClickGUI warmup unavailable: ${failure.message}"
                        )
                    }
                }
                .also {
                    it.priority = Thread.MIN_PRIORITY
                    it.start()
                }
    }

    @JvmStatic
    fun cancel() {
        cancelled = true
        worker?.interrupt()
        worker = null
    }

    @OptIn(InternalComposeUiApi::class)
    private fun renderPreview() {
        val start = System.nanoTime()
        // These descriptors are detached: no enabled getter, toggle or setting reads game state.
        val modules =
            ModuleCategories.ordered().flatMap { category ->
                List(16) { index ->
                    ModuleRegistry.Module(
                        "warmup-$category-$index",
                        "Module ${index + 1}",
                        category,
                        { index % 3 == 0 },
                        { _, _ -> 1 },
                        { "" },
                        emptyList(),
                    )
                }
            }
        if (cancelled) return
        val scene = CanvasLayersComposeScene(density = Density(1.2f), size = IntSize(960, 640))
        try {
            Surface.makeRasterN32Premul(960, 640).use { surface ->
                scene.setContent {
                    MoonsClickGui(null, {}, {}, {}, {}, modulesOverride = modules)
                }
                repeat(3) { frame ->
                    if (cancelled) return
                    scene.render(surface.canvas.asComposeCanvas(), start + frame * 16_666_667L)
                }
            }
        } finally {
            scene.close()
        }
        System.out.println(
            "[client] ClickGUI CPU warmup complete in ${(System.nanoTime() - start) / 1_000_000} ms"
        )
    }
}
