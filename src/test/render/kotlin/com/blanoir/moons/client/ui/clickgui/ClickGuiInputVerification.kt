package com.blanoir.moons.client.ui.clickgui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.blanoir.moons.client.config.Settings
import com.blanoir.moons.client.module.framework.ModuleRegistry
import com.blanoir.moons.client.utils.ui.SettingInput
import com.google.gson.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/** Exercises real text layout and focus restoration without a running game or GPU. */
@OptIn(ExperimentalComposeUiApi::class)
object ClickGuiInputVerification {
    @JvmStatic
    fun main(args: Array<String>) {
        val output = Path.of(args.first())
        Files.createDirectories(output)
        Settings.configure(output.resolve("home"))
        for (scale in listOf(1f, 1.2f, 1.4f, 2f)) {
            for (fontScale in listOf(1f, 1.45f)) {
                val value = mutableStateOf(-123.456789)
                val setting =
                    ModuleRegistry.Setting(
                        "number",
                        "Value",
                        "number",
                        { JsonPrimitive(value.value) },
                        -1000000.0,
                        1000000.0,
                        0.000001,
                        emptyList(),
                        { _, _ -> error("Unexpected commit") },
                    )
                val module =
                    ModuleRegistry.Module(
                        "fixture",
                        "Fixture",
                        "Render",
                        { false },
                        { _, _ -> 1 },
                        { "" },
                        listOf(setting),
                        false,
                    )
                var clearFocus: () -> Unit = {}
                val scene =
                    ImageComposeScene(400, 240, Density(scale, fontScale)) {
                        val focus = LocalFocusManager.current
                        clearFocus = { focus.clearFocus() }
                        MaterialTheme(colorScheme = panelColorScheme()) {
                            Column(Modifier.width(140.dp).background(PanelStyle.panel)) {
                                CompactSetting(module, setting, {})
                                SettingInput("Example 123", {})
                            }
                        }
                    }
                try {
                    fun render() {
                        repeat(3) { scene.render().close() }
                    }
                    fun fields() =
                        scene.semanticsOwners
                            .flatMap { descendants(it.rootSemanticsNode) }
                            .filter {
                                it.config.getOrNull(SemanticsProperties.EditableText) != null
                            }
                    fun checkVisible(expected: String, index: Int = 0) {
                        val field = fields()[index]
                        check(field.config[SemanticsProperties.EditableText].text == expected)
                        val layouts = mutableListOf<TextLayoutResult>()
                        check(
                            field.config[SemanticsActions.GetTextLayoutResult]
                                .action!!
                                .invoke(layouts)
                        )
                        val layout = layouts.single()
                        check(!layout.hasVisualOverflow) {
                            "Clipped $expected at $scale / $fontScale: ${layout.size}"
                        }
                        check(field.boundsInRoot.height >= layout.size.height) {
                            "Clipped input height"
                        }
                    }
                    render()
                    check(fields().size == 2)
                    checkVisible("-123.456789")
                    checkVisible("Example 123", 1)
                    for (number in listOf(1000000.0, 0.000001, 8.0, 0.0)) {
                        value.value = number
                        render()
                        checkVisible(formatValue(number))
                    }
                    for (draft in listOf("", "-", ".")) {
                        check(
                            fields().first().config[SemanticsActions.RequestFocus].action!!.invoke()
                        )
                        render()
                        check(
                            fields()
                                .first()
                                .config[SemanticsActions.SetText]
                                .action!!
                                .invoke(AnnotatedString(draft))
                        )
                        render()
                        clearFocus()
                        render()
                        checkVisible("0")
                    }
                    scene.render().use { image ->
                        image.encodeToData()!!.use {
                            Files.write(output.resolve("input-$scale-$fontScale.png"), it.bytes)
                        }
                    }
                } finally {
                    scene.close()
                }
            }
        }
        println("ClickGUI inputs verified at four display densities and both layout font scales.")
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap(::descendants)
}
