package com.blanoir.moons.client.ui.clickgui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.blanoir.moons.client.config.ConfigProfiles
import com.blanoir.moons.client.config.Settings
import com.blanoir.moons.client.module.framework.ModuleRegistry
import com.google.gson.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface

/** Real Compose rendering and pointer/semantics actions, with a detached module catalog. */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
object SettingsClickGuiVerification {
    @JvmStatic
    fun main(args: Array<String>) {
        java.awt.EventQueue.invokeAndWait { verify() }
    }

    private fun verify() {
        Settings.configure(Files.createTempDirectory("moons-settings-ui-"))
        Settings.load()
        val output = Path.of(System.getProperty("moons.gui.previewDir", "build/gui-preview"))
        Files.createDirectories(output)
        val values = linkedMapOf<String, Boolean>()
        var distance = 3.2
        val backtrackEntry =
            Class.forName("com.blanoir.moons.features.catalog.Network")
                .getDeclaredMethod("backtrack")
        backtrackEntry.isAccessible = true
        val backtrackSettings = (backtrackEntry.invoke(null) as ModuleRegistry.Module).settings()
        val fixture =
            listOf(
                    "SilentAura" to "Combat",
                    "AimAssist" to "Combat",
                    "Velocity" to "Combat",
                    "SprintReset" to "Combat",
                    "Reach" to "Combat",
                    "TriggerBot" to "Combat",
                    "Backtrack" to "Network",
                    "FakeLag" to "Network",
                    "Sprint" to "Movement",
                    "NoSlow" to "Movement",
                    "AutoTool" to "Player",
                    "AutoMLG" to "Player",
                    "ESP" to "Render",
                    "Fullbright" to "Render",
                    "Scaffold" to "World",
                    "AntiBot" to "Misc",
                    "StaticFov" to "Misc",
                )
                .mapIndexed { index, (name, category) ->
                    val id = name.lowercase()
                    values[id] = index % 3 != 1
                    ModuleRegistry.Module(
                        id,
                        name,
                        category,
                        { values.getValue(id) },
                        { _, enabled ->
                            values[id] = enabled
                            1
                        },
                        { "" },
                        if (index == 0)
                            listOf(
                                ModuleRegistry.Setting(
                                    "range",
                                    "Attack range",
                                    "number",
                                    { JsonPrimitive(distance) },
                                    1.0,
                                    6.0,
                                    .1,
                                    emptyList(),
                                    { _, value ->
                                        distance = value.asDouble
                                        1
                                    },
                                )
                            )
                        else if (name == "Backtrack") backtrackSettings else emptyList(),
                    )
                }
        ModuleRegistry.installCatalog { fixture.forEach(ModuleRegistry::add) }
        ModuleRegistry.ensureCatalog()
        check(ModuleGui.layout() == "settings")
        Settings.setString(GUI_THEME_KEY, "#b29a65")
        check(guiThemeValue() == DEFAULT_GUI_THEME) { "Legacy gold theme must migrate" }
        val owners = mutableListOf<SemanticsOwner>()
        val platform =
            object : PlatformContext.Empty() {
                override val semanticsOwnerListener =
                    object : PlatformContext.SemanticsOwnerListener {
                        override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) {
                            owners.add(semanticsOwner)
                        }

                        override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) {
                            owners.remove(semanticsOwner)
                        }

                        override fun onSemanticsChange(semanticsOwner: SemanticsOwner) {}

                        override fun onLayoutChange(
                            semanticsOwner: SemanticsOwner,
                            semanticsNodeId: Int,
                        ) {}
                    }
            }
        val scene =
            CanvasLayersComposeScene(
                density = Density(1f),
                size = IntSize(860, 640),
                platformContext = platform,
            )
        val binding = mutableStateOf<String?>(null)
        var closed = false
        var editedHud = false
        var time = System.nanoTime()
        fun nodes(): List<SemanticsNode> {
            fun tree(node: SemanticsNode): List<SemanticsNode> =
                listOf(node) + node.children.flatMap(::tree)
            return owners.flatMap { tree(it.unmergedRootSemanticsNode) }
        }
        fun description(value: String) =
            nodes().firstOrNull {
                value in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            } ?: error("Missing description $value")
        fun text(value: String) =
            nodes().firstOrNull {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } ==
                    true
            } ?: error("Missing text $value")
        Surface.makeRasterN32Premul(1720, 1280).use { surface ->
            fun render() {
                repeat(10) {
                    androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                    time += 50_000_000L
                    surface.canvas.clear(0xFF292C2E.toInt())
                    scene.render(surface.canvas.asComposeCanvas(), time)
                }
            }
            fun save(name: String) {
                surface
                    .makeImageSnapshot(
                        org.jetbrains.skia.IRect.makeWH(scene.size!!.width, scene.size!!.height)
                    )!!
                    .use { image ->
                        image.encodeToData(EncodedImageFormat.PNG)!!.use {
                            Files.write(output.resolve(name), it.bytes)
                        }
                    }
            }
            fun click(node: SemanticsNode) {
                val point = node.boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Move, point)
                scene.sendPointerEvent(
                    PointerEventType.Press,
                    point,
                    button = PointerButton.Primary,
                )
                scene.sendPointerEvent(
                    PointerEventType.Release,
                    point,
                    button = PointerButton.Primary,
                )
                render()
            }
            fun type(value: String) {
                val field = nodes().first { it.config.contains(SemanticsActions.SetText) }
                check(
                    field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value))
                )
                render()
            }
            try {
                scene.setContent {
                    MoonsClickGui(
                        binding.value,
                        { binding.value = it },
                        { ClickGuiRevision.intValue++ },
                        { editedHud = true },
                        { closed = true },
                        fixture,
                    )
                }
                render()
                save("settings-clickgui.png")
                click(description("Toggle SilentAura"))
                check(values["silentaura"] == false) {
                    "Status click must toggle, not open settings"
                }
                description("Search modules")
                type("Velocity")
                save("settings-search.png")
                text("Velocity")
                check(
                    nodes().none {
                        it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                            text.text == "SilentAura"
                        } == true
                    }
                )
                type("no-such-module")
                text("No modules found")
                save("settings-empty.png")
                click(description("Clear search"))
                click(description("Combat"))
                text("SilentAura")
                check(
                    nodes().none {
                        it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                            text.text == "Scaffold"
                        } == true
                    }
                )
                click(text("SilentAura"))
                text("Keybind")
                text("Attack range")
                type("4.1")
                check(distance == 4.1) { "Setting changes must reach the registry" }
                click(text("None"))
                check(binding.value == "silentaura") {
                    "Key binding must reach the screen callback"
                }
                binding.value = null
                render()
                save("settings-configuration.png")
                click(description("General"))
                click(text("StaticFov"))
                text("Keybind")
                text("This module has no additional options.")
                click(description("General"))
                click(text("Edit HUD layout"))
                check(editedHud)
                click(description("Configs"))
                type("Duel")
                click(text("Create"))
                check(ConfigProfiles.list() == listOf("default", "Duel"))
                distance = 5.2
                click(text("Save"))
                distance = 2.7
                click(text("Load"))
                check(distance == 5.2) { "Loading a selected config must apply its module values" }
                text("Loaded Duel")
                type("Practice")
                click(text("Create"))
                click(description("Select config Duel"))
                save("settings-configs.png")
                click(description("General"))
                click(text("Panel layout"))
                check(ModuleGui.layout() == "panels")
                save("panel-clickgui.png")
                scene.size = IntSize(1720, 1280)
                scene.density = Density(2f)
                render()
                save("panel-clickgui-2x.png")
                click(text("SilentAura"))
                // Secondary clicks expand the module's existing controls.
                val modulePoint = text("SilentAura").boundsInRoot.center
                scene.sendPointerEvent(
                    PointerEventType.Press,
                    modulePoint,
                    button = PointerButton.Secondary,
                )
                scene.sendPointerEvent(
                    PointerEventType.Release,
                    modulePoint,
                    button = PointerButton.Secondary,
                )
                render()
                save("panel-controls-2x.png")
                description("Search modules")
                    .config[SemanticsActions.SetText]
                    .action!!
                    .invoke(AnnotatedString("Backtrack"))
                render()
                val backtrackPoint = text("Backtrack").boundsInRoot.center
                scene.sendPointerEvent(
                    PointerEventType.Press,
                    backtrackPoint,
                    button = PointerButton.Secondary,
                )
                scene.sendPointerEvent(
                    PointerEventType.Release,
                    backtrackPoint,
                    button = PointerButton.Secondary,
                )
                render()
                text("Time (ms)")
                text("Max range")
                save("backtrack-controls-2x.png")
                scene.size = IntSize(860, 640)
                scene.density = Density(1f)
                ModuleGui.setLayout(null, "settings")
                ClickGuiRevision.intValue++
                render()
                scene.size = IntSize(440, 400)
                render()
                save("settings-compact.png")
                click(description("Close ClickGUI"))
                check(closed)
                println(
                    "SETTINGS_CLICKGUI_VERIFIED search toggle config keybind hud profiles layout compact close; previews=$output"
                )
            } finally {
                scene.close()
            }
        }
    }
}
