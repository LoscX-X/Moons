package com.blanoir.moons.client.ui.clickgui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blanoir.moons.client.config.ClientBranding
import com.blanoir.moons.client.config.Settings
import com.blanoir.moons.client.module.framework.ModuleKeybinds
import com.blanoir.moons.client.module.impl.render.xray.PluginBlockPreviews
import com.blanoir.moons.client.ui.MinecraftScreenAccess
import com.blanoir.moons.client.ui.compose.FinalFrameSurface
import com.blanoir.moons.client.ui.compose.GlfwComposeEvents
import com.blanoir.moons.client.ui.hud.HudLayoutController
import com.blanoir.moons.client.utils.render.NativeItemIcons
import com.mojang.blaze3d.systems.RenderSystem
import java.awt.event.KeyEvent as AwtKeyEvent
import java.awt.event.MouseEvent as AwtMouseEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

// Rows subscribe explicitly so registry polling still reaches cached/lazy panels.
internal val ClickGuiRevision = mutableIntStateOf(0)

/**
 * Compose/Skia ClickGUI rendered independently into GLFW's default framebuffer. Minecraft still
 * owns the Screen lifecycle and input; Compose owns layout and final-frame drawing.
 */
@OptIn(InternalComposeUiApi::class)
class MoonsComposeScreen : Screen(Component.literal("${ClientBranding.name()} ClickGUI")) {
    private var composeScene: ComposeScene? = null
    private val frameSurface = FinalFrameSurface()
    private var currentScale = 1f
    private var currentUiDensity = 1.4f

    private var bindingModuleId by mutableStateOf<String?>(null)
    private var revision by ClickGuiRevision
    private val hudLayoutController = HudLayoutController()
    private var hudLayoutEditing by mutableStateOf(false)
    private var hudPointerCaptured = false
    private var nextRegistrySyncNanos = 0L

    override fun extractBackground(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        delta: Float,
    ) {
        // The dim layer is owned by Compose so ClickGUI never enters Minecraft's draw pipeline.
    }

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        delta: Float,
    ) {
        if (hudLayoutEditing) {
            hudLayoutController.render(
                graphics,
                mouseX.toDouble(),
                mouseY.toDouble(),
                width,
                height,
            )
        }
    }

    fun renderComposeFrame() {
        RenderSystem.assertOnRenderThread()
        val now = System.nanoTime()
        if (now >= nextRegistrySyncNanos) {
            nextRegistrySyncNanos = now + 50_000_000L
            revision++
        }
        val window = Minecraft.getInstance().window
        val frameWidth = window.width
        val frameHeight = window.height
        if (frameWidth <= 0 || frameHeight <= 0) return

        currentScale = window.guiScale.coerceAtLeast(1).toFloat()
        // Keep pointer coordinates tied to Minecraft's actual GUI scale, but render the
        // independent Compose layer at a stable density so high GUI scales do not turn
        // every 156dp panel into a ~500px window.
        currentUiDensity = (frameHeight / 1080f * 1.4f).coerceIn(1.2f, 2f)
        ensureScene(frameWidth, frameHeight)

        val scene = composeScene ?: return
        NativeItemIcons.prepareFrame()
        PluginBlockPreviews.prepareFrame()
        frameSurface.render(frameWidth, frameHeight) { canvas ->
            scene.render(canvas.asComposeCanvas(), now)
        }
    }

    private fun ensureScene(frameWidth: Int, frameHeight: Int) {
        val scene =
            composeScene
                ?: CanvasLayersComposeScene(
                        density = Density(currentUiDensity),
                        invalidate = {},
                    )
                    .also {
                        composeScene = it
                        it.setContent { ClickGuiContent() }
                    }
        scene.density = Density(currentUiDensity)
        scene.size = IntSize(frameWidth, frameHeight)
    }

    fun dispose() {
        composeScene?.close()
        composeScene = null
        frameSurface.close()
        NativeItemIcons.clear()
        PluginBlockPreviews.clear()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun removed() {
        hudLayoutController.mouseReleased()
        hudPointerCaptured = false
        setDragging(false)
        composeScene?.cancelPointerInput()
        composeScene?.focusManager?.releaseFocus()
        bindingModuleId = null
        hudLayoutEditing = false
        nextRegistrySyncNanos = 0L
        NativeItemIcons.clear()
        PluginBlockPreviews.clear()
        super.removed()
    }

    override fun onClose() {
        MinecraftScreenAccess.set(Minecraft.getInstance(), null)
    }

    override fun shouldCloseOnEsc() = false

    override fun isPauseScreen() = false

    fun isHudLayoutEditing(): Boolean = hudLayoutEditing

    fun isBindingKey(): Boolean = bindingModuleId != null

    private fun composeOffset(x: Double, y: Double) =
        Offset((x * currentScale).toFloat(), (y * currentScale).toFloat())

    @OptIn(ExperimentalComposeUiApi::class)
    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        val position = composeOffset(mouseX, mouseY)
        val window = Minecraft.getInstance().window.handle()
        composeScene?.sendPointerEvent(
            PointerEventType.Move,
            position = position,
            type = PointerType.Mouse,
            nativeEvent =
                GlfwComposeEvents.mouse(
                    position.x.toInt(),
                    position.y.toInt(),
                    GlfwComposeEvents.modifiers(window),
                    0,
                    AwtMouseEvent.MOUSE_MOVED,
                ),
        )
        super.mouseMoved(mouseX, mouseY)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        bindingModuleId?.let { moduleId ->
            val key = ModuleKeybinds.fromMouseButton(event.button())
            if (ModuleKeybinds.bind(moduleId, key)) {
                bindingModuleId = null
                revision++
            }
            return true
        }
        if (ModuleKeybinds.isGuiKey(ModuleKeybinds.fromMouseButton(event.button()))) return true
        if (
            hudLayoutEditing &&
                hudLayoutController.mouseClicked(event.button(), event.x(), event.y())
        ) {
            hudPointerCaptured = true
            setDragging(true)
            return true
        }
        hudPointerCaptured = false
        val position = composeOffset(event.x(), event.y())
        val window = Minecraft.getInstance().window.handle()
        composeScene?.sendPointerEvent(
            PointerEventType.Press,
            position = position,
            button = PointerButton(event.button()),
            nativeEvent =
                GlfwComposeEvents.mouse(
                    position.x.toInt(),
                    position.y.toInt(),
                    GlfwComposeEvents.modifiers(window),
                    event.button(),
                    AwtMouseEvent.MOUSE_PRESSED,
                ),
        )
        return true
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (hudLayoutEditing && hudPointerCaptured) {
            return hudLayoutController.mouseDragged(event.x(), event.y(), width, height)
        }
        val position = composeOffset(event.x(), event.y())
        val pointer =
            ComposeScenePointer(
                id = PointerId(0),
                position = position,
                pressed = true,
                type = PointerType.Mouse,
            )
        val window = Minecraft.getInstance().window.handle()
        composeScene?.sendPointerEvent(
            PointerEventType.Move,
            pointers = listOf(pointer),
            nativeEvent =
                GlfwComposeEvents.mouse(
                    position.x.toInt(),
                    position.y.toInt(),
                    GlfwComposeEvents.modifiers(window),
                    event.button(),
                    AwtMouseEvent.MOUSE_DRAGGED,
                ),
        )
        return true
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (hudLayoutEditing && hudPointerCaptured) {
            hudLayoutController.mouseReleased()
            hudPointerCaptured = false
            setDragging(false)
            return true
        }
        val position = composeOffset(event.x(), event.y())
        val window = Minecraft.getInstance().window.handle()
        composeScene?.sendPointerEvent(
            PointerEventType.Release,
            position = position,
            button = PointerButton(event.button()),
            nativeEvent =
                GlfwComposeEvents.mouse(
                    position.x.toInt(),
                    position.y.toInt(),
                    GlfwComposeEvents.modifiers(window),
                    event.button(),
                    AwtMouseEvent.MOUSE_RELEASED,
                ),
        )
        return true
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        horizontal: Double,
        vertical: Double,
    ): Boolean {
        if (
            hudLayoutEditing &&
                hudLayoutController.mouseScrolled(mouseX, mouseY, vertical, width, height)
        ) {
            return true
        }
        val position = composeOffset(mouseX, mouseY)
        val window = Minecraft.getInstance().window.handle()
        composeScene?.sendPointerEvent(
            PointerEventType.Scroll,
            position = position,
            scrollDelta =
                Offset(
                    (horizontal * currentScale).toFloat(),
                    (-vertical * currentScale).toFloat(),
                ),
            nativeEvent =
                GlfwComposeEvents.wheel(
                    position.x.toInt(),
                    position.y.toInt(),
                    vertical,
                    GlfwComposeEvents.modifiers(window),
                ),
        )
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (hudLayoutEditing) {
            when (event.key()) {
                GLFW.GLFW_KEY_ESCAPE -> {
                    hudLayoutController.mouseReleased()
                    hudPointerCaptured = false
                    hudLayoutEditing = false
                    return true
                }
                GLFW.GLFW_KEY_R -> if (hudLayoutController.resetSelected()) return true
            }
        }
        bindingModuleId?.let { moduleId ->
            val key = ModuleKeybinds.fromEvent(event)
            when (event.key()) {
                GLFW.GLFW_KEY_ESCAPE -> bindingModuleId = null
                GLFW.GLFW_KEY_BACKSPACE,
                GLFW.GLFW_KEY_DELETE -> {
                    ModuleKeybinds.unbind(moduleId)
                    bindingModuleId = null
                    revision++
                }
                else ->
                    if (ModuleKeybinds.bind(moduleId, key)) {
                        bindingModuleId = null
                        revision++
                    }
            }
            return true
        }
        val key = ModuleKeybinds.fromEvent(event)
        // The independent binding listener owns this key, including when a host hook is absent.
        if (ModuleKeybinds.isGuiKey(key)) return true
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose()
            return true
        }
        composeScene?.sendKeyEvent(
            GlfwComposeEvents.key(
                Minecraft.getInstance().window.handle(),
                AwtKeyEvent.KEY_PRESSED,
                event.key(),
            )
        )
        return true
    }

    override fun keyReleased(event: KeyEvent): Boolean {
        composeScene?.sendKeyEvent(
            GlfwComposeEvents.key(
                Minecraft.getInstance().window.handle(),
                AwtKeyEvent.KEY_RELEASED,
                event.key(),
            )
        )
        return true
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        composeScene?.sendKeyEvent(
            GlfwComposeEvents.key(
                Minecraft.getInstance().window.handle(),
                AwtKeyEvent.KEY_TYPED,
                GLFW.GLFW_KEY_UNKNOWN,
                event.codepoint(),
            )
        )
        return true
    }

    @Composable
    private fun ClickGuiContent() {
        revision
        if (hudLayoutEditing) {
            HudLayoutOverlay {
                hudLayoutController.mouseReleased()
                hudPointerCaptured = false
                hudLayoutEditing = false
            }
            return
        }
        MoonsClickGui(
            bindingModuleId = bindingModuleId,
            onBindingModuleChange = { bindingModuleId = it },
            onMutated = { revision++ },
            onEditHudLayout = {
                bindingModuleId = null
                hudLayoutEditing = true
            },
            onClose = ::onClose,
        )
    }

    @Composable
    private fun HudLayoutOverlay(onDone: () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            Row(
                Modifier.align(Alignment.TopCenter)
                    .padding(top = 10.dp)
                    .shadow(14.dp, RoundedCornerShape(7.dp))
                    .clip(RoundedCornerShape(7.dp))
                    .background(PanelStyle.panel)
                    .height(32.dp)
                    .padding(start = 11.dp, end = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "HUD layout",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "drag · corner/right click resize · R reset",
                    color = Color(0xFF8D8D91),
                    fontSize = 7.sp,
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(5.dp))
                        .background(guiThemeColor())
                        .clickable(onClick = onDone)
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Done",
                        color = Color(0xFF17130D),
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }

    private fun guiThemeColor(): Color =
        runCatching {
                val raw = Settings.getString("clickgui.theme.color", "#b29a65").removePrefix("#")
                Color((0xFF000000L or raw.toLong(16)).toInt())
            }
            .getOrDefault(Color(0xFFB29A65))
}
