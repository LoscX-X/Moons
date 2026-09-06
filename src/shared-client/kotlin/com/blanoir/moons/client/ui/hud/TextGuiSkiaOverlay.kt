package com.blanoir.moons.client.ui.hud

import com.blanoir.moons.client.access.MinecraftClientAccess
import com.blanoir.moons.client.module.framework.ModuleRegistry
import com.blanoir.moons.client.module.impl.render.TargetInfoHud
import com.blanoir.moons.client.ui.animation.Animation
import com.blanoir.moons.client.ui.clickgui.MoonsComposeScreen
import com.blanoir.moons.client.ui.compose.FinalFrameGl
import com.blanoir.moons.client.ui.MinecraftScreenAccess
import com.blanoir.moons.client.ui.layout.Bounds
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.ColorFilter
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.Data
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontEdging
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FramebufferFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.Point
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RRect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.Typeface
import org.jetbrains.skia.Surface as SkiaSurface
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.LinkedHashMap
import javax.imageio.ImageIO
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * TextGUI and TargetInfo rendered directly into GLFW's final framebuffer.
 * No Minecraft text, pose stack, resource reload or HUD render event is used.
 */
object TextGuiSkiaOverlay {
    private const val INTER_RESOURCE = "assets/moons/font/inter-frozen-medium.otf"
    private const val MINECRAFT_FONT_RESOURCE = "assets/moons/font/minecraft-ascii.png.b64"
    private const val TAG_SEPARATOR = " - "

    private var context: DirectContext? = null
    private var target: BackendRenderTarget? = null
    private var surface: SkiaSurface? = null
    private var surfaceWidth = -1
    private var surfaceHeight = -1
    private var startedNanos = 0L
    private var lastAnimationNanos = 0L
    private val moduleEntries = LinkedHashMap<String, ModuleEntry>()

    private var loadedTextResources: TextResources? = null
    private val textResources: TextResources
        get() = loadedTextResources ?: TextResources.load().also { loadedTextResources = it }

    @JvmStatic
    fun renderFrame(editorVisible: Boolean) {
        RenderSystem.assertOnRenderThread()
        val client = Minecraft.getInstance()
        if (client.player == null || client.level == null) {
            moduleEntries.clear()
            lastAnimationNanos = 0L
            MoonsHud.updateExternalBounds(Bounds(0.0, 0.0, 0.0, 0.0), HudConfig.SCALE.get())
            return
        }

        val currentScreen = MinecraftScreenAccess.current(client)
        if (currentScreen is MoonsComposeScreen && !editorVisible) {
            lastAnimationNanos = 0L
            TargetInfoHud.snapshot(false)
            return
        }
        if (!editorVisible && MinecraftClientAccess.isHudHidden(client)) {
            lastAnimationNanos = 0L
            return
        }

        val now = System.nanoTime()
        val animationSeconds = animationFrameSeconds(now)
        val modules = ModuleRegistry.enabledModules()
        updateModuleEntries(
            if (editorVisible || HudConfig.VISIBLE.get()) modules else emptyList(),
            animationSeconds
        )
        val drawTextGui = editorVisible || moduleEntries.isNotEmpty()
        val targetSnapshot = TargetInfoHud.snapshot(editorVisible)
        if (!drawTextGui) {
            MoonsHud.updateExternalBounds(Bounds(0.0, 0.0, 0.0, 0.0), HudConfig.SCALE.get())
        }
        if (!drawTextGui && !targetSnapshot.visible()) {
            return
        }

        val window = client.window
        val width = window.width
        val height = window.height
        if (width <= 0 || height <= 0) return
        if (startedNanos == 0L) startedNanos = now
        val seconds = (now - startedNanos) / 1_000_000_000.0

        val state = FinalFrameGl.prepare(width, height)
        try {
            ensureSurface(width, height)
            context?.resetAll()
            val canvas = surface?.canvas ?: return
            if (drawTextGui) drawTextGui(
                canvas, client, moduleEntries.values.toList(), editorVisible,
                seconds, animationSeconds
            )
            if (targetSnapshot.visible()) drawTargetInfo(canvas, targetSnapshot)
            surface?.flushAndSubmit()
        } finally {
            state.restore()
        }
    }

    private fun drawTextGui(
        canvas: org.jetbrains.skia.Canvas,
        client: Minecraft,
        entries: List<ModuleEntry>,
        editing: Boolean,
        seconds: Double,
        animationSeconds: Double
    ) {
        val guiScale = client.window.guiScale.coerceAtLeast(1).toFloat()
        val hudScale = HudConfig.SCALE.get().toFloat()
        val physicalScale = guiScale * hudScale
        val fontSize = 9.0f * physicalScale
        val rowHeight = max(10.0f * physicalScale, fontSize + physicalScale)
        val titleGap = 2.0f * physicalScale
        val padding = 2.0f * physicalScale
        val barGap = 2.0f * physicalScale
        val barWidth = max(1.0f, 1.25f * physicalScale)
        val pixelMode = HudConfig.FONT_MODE.get() == HudConfig.HudFontMode.MINECRAFT

        val resources = textResources
        resources.font.size = fontSize
        val header = if ((editing || HudConfig.VISIBLE.get()) && HudConfig.SHOW_TITLE.get())
            HudConfig.header(client) else ""
        val rows = entries.map { entry ->
            val nameWidth = measure(resources, entry.module.name(), physicalScale, pixelMode)
            val tag = entry.tag
            val separatorWidth = if (tag.isBlank()) 0.0f
                else measure(resources, TAG_SEPARATOR, physicalScale, pixelMode)
            val actualTagWidth = if (tag.isBlank()) 0.0f
                else measure(resources, tag, physicalScale, pixelMode)
            val stableTagWidth = if (tag.isBlank()) 0.0f else max(
                actualTagWidth,
                measure(resources, stableTagTemplate(tag), physicalScale, pixelMode)
            )
            Row(entry, tag, nameWidth, separatorWidth, actualTagWidth, stableTagWidth,
                nameWidth + separatorWidth + stableTagWidth + padding + barGap + barWidth)
        }.sortedWith(compareByDescending<Row> { it.width }.thenBy { it.entry.module.name() })

        rows.forEachIndexed { index, row ->
            val target = index.toDouble()
            row.entry.positionRows = if (row.entry.positionRows.isNaN()) target else
                Animation.approach(row.entry.positionRows, target, animationSeconds, 21.0)
        }

        val headerWidth = if (header.isBlank()) 0.0f
            else measure(resources, header, physicalScale, pixelMode)
        val preview = "HUD Preview"
        val previewWidth = if (rows.isEmpty())
            measure(resources, preview, physicalScale, pixelMode) + 7.0f * physicalScale else 0.0f
        val maximumWidth = max(1.0f, max(headerWidth, max(previewWidth, rows.maxOfOrNull { it.width } ?: 0.0f)))

        val guiWidth = client.window.width / guiScale
        val guiHeight = client.window.height / guiScale
        val unscaledWidth = floor(guiWidth / hudScale)
        val rightLogical = if (HudConfig.POSITION_X.get() < 0) unscaledWidth - 5.0f
            else min(unscaledWidth, HudConfig.POSITION_X.get().toFloat())
        val baseLogicalY = min(floor(guiHeight / hudScale), HudConfig.POSITION_Y.get().toFloat())
        val right = rightLogical * physicalScale
        var y = baseLogicalY * physicalScale
        val startY = y
        val titleHeight = if (header.isBlank()) 0.0f else rowHeight + titleGap
        val contentRows = max(1, rows.size)
        val totalHeight = titleHeight + contentRows * rowHeight
        val left = right - maximumWidth

        MoonsHud.updateExternalBounds(
            Bounds(
                (left / guiScale).toDouble(),
                (startY / guiScale).toDouble(),
                (maximumWidth / guiScale).toDouble(),
                (totalHeight / guiScale).toDouble()
            ),
            hudScale.toDouble()
        )

        val overallAlpha = (255.0 * HudConfig.ALPHA.get() / 100.0).toInt().coerceIn(0, 255)
        if (header.isNotBlank()) {
            drawText(
                canvas, resources, header, right - headerWidth, y, rowHeight,
                HudText.withAlpha(HudText.titleColor(), overallAlpha),
                physicalScale, pixelMode
            )
            y += titleHeight
        }

        if (rows.isEmpty() && editing) {
            val cardLeft = right - previewWidth
            drawPanel(canvas, cardLeft, y, previewWidth, rowHeight, overallAlpha)
            drawText(
                canvas, resources, preview, cardLeft + 3.0f * physicalScale, y, rowHeight,
                HudText.withAlpha(HudText.hudNameColor(0, seconds), overallAlpha),
                physicalScale, pixelMode
            )
            return
        }

        rows.forEachIndexed { index, row ->
            val eased = 1.0 - Math.pow(1.0 - row.entry.progress.coerceIn(0.0, 1.0), 3.0)
            val visibleWidth = max(1.0f, row.width * eased.toFloat())
            val cardLeft = right - visibleWidth
            val rowY = y + row.entry.positionRows.toFloat() * rowHeight
            val animatedAlpha = min(overallAlpha, (overallAlpha * eased).toInt())
            drawPanel(canvas, cardLeft, rowY, visibleWidth, rowHeight, animatedAlpha, eased)
            val textX = cardLeft + padding
            drawModuleName(
                canvas, resources, row.entry.module.name(), textX, rowY, rowHeight,
                index, seconds, animatedAlpha, physicalScale, pixelMode, row.nameWidth
            )
            if (row.tag.isNotBlank()) {
                val parameterColor = HudText.withAlpha(HudText.parameterColor(), animatedAlpha)
                drawText(
                    canvas, resources, TAG_SEPARATOR, textX + row.nameWidth, rowY, rowHeight,
                    parameterColor, physicalScale, pixelMode
                )
                val tagX = textX + row.nameWidth + row.separatorWidth
                    + row.stableTagWidth - row.actualTagWidth
                drawText(
                    canvas, resources, row.tag, tagX, rowY, rowHeight,
                    parameterColor, physicalScale, pixelMode
                )
            }
            drawModuleBar(
                canvas, resources, right - barWidth, rowY, barWidth, rowHeight,
                index, row.nameWidth, seconds, animatedAlpha, physicalScale
            )
        }
    }

    private fun animationFrameSeconds(now: Long): Double {
        val previous = lastAnimationNanos
        lastAnimationNanos = now
        return if (previous == 0L) 0.0 else
            ((now - previous) / 1_000_000_000.0).coerceIn(0.0, 0.1)
    }

    private fun updateModuleEntries(
        active: List<ModuleRegistry.Module>,
        animationSeconds: Double
    ) {
        val activeIds = HashSet<String>()
        active.forEach { module ->
            activeIds += module.id()
            val entry = moduleEntries.getOrPut(module.id()) { ModuleEntry(module) }
            entry.module = module
            entry.tag = safeTag(module)
            entry.present = true
        }
        moduleEntries.forEach { (id, entry) ->
            entry.present = id in activeIds
            entry.progress = Animation.approach(
                entry.progress, if (entry.present) 1.0 else 0.0,
                animationSeconds, 16.0
            )
        }
        moduleEntries.entries.removeIf { (_, entry) ->
            !entry.present && entry.progress < 0.015
        }
    }

    private fun drawTargetInfo(
        canvas: org.jetbrains.skia.Canvas,
        snapshot: TargetInfoHud.Snapshot
    ) {
        val client = Minecraft.getInstance()
        val guiScale = client.window.guiScale.coerceAtLeast(1).toFloat()
        val componentScale = TargetInfoHud.scale().toFloat() * guiScale
        val bounds = snapshot.bounds()
        val originX = (bounds.x().toFloat() + snapshot.slide()) * guiScale
        val originY = bounds.y().toFloat() * guiScale
        val alpha = snapshot.alpha().coerceIn(0, 255)
        val resources = textResources
        val pixelMode = HudConfig.FONT_MODE.get() == HudConfig.HudFontMode.MINECRAFT
        resources.font.size = 9.0f * componentScale
        val fill = Paint().apply { isAntiAlias = true }
        try {
            fill.color = HudText.withAlpha(0x252933, min(224, alpha))
            canvas.drawRRect(RRect.makeXYWH(originX, originY, 166.0f * componentScale,
                63.0f * componentScale, 10.0f * componentScale), fill)

            fill.mode = PaintMode.STROKE
            fill.strokeWidth = max(1.0f, componentScale)
            fill.color = HudText.withAlpha(0x697083, min(92, alpha))
            canvas.drawRRect(RRect.makeXYWH(originX, originY, 166.0f * componentScale,
                63.0f * componentScale, 10.0f * componentScale), fill)
            fill.mode = PaintMode.FILL

            val faceX = originX + 8.0f * componentScale
            val faceY = originY + 8.0f * componentScale
            val faceSize = 38.0f * componentScale
            fill.color = HudText.withAlpha(snapshot.healthColor(), min(108, alpha))
            canvas.drawRRect(RRect.makeXYWH(faceX - 2.0f * componentScale,
                faceY - 2.0f * componentScale, faceSize + 4.0f * componentScale,
                faceSize + 4.0f * componentScale, 5.0f * componentScale), fill)
            fill.color = HudText.withAlpha(0x3A3E49, alpha)
            canvas.drawRRect(RRect.makeXYWH(faceX, faceY, faceSize, faceSize,
                3.0f * componentScale), fill)

            val initial = snapshot.name().trim().take(1).uppercase().ifBlank { "?" }
            val initialScale = componentScale * 1.35f
            val initialWidth = measure(resources, initial, initialScale, pixelMode)
            drawText(canvas, resources, initial, faceX + (faceSize - initialWidth) * 0.5f,
                faceY, faceSize, HudText.withAlpha(0xFFF5F5, alpha), initialScale, pixelMode)

            val textX = originX + 54.0f * componentScale
            val maxTextWidth = 104.0f * componentScale
            val name = fitText(resources, snapshot.name(), maxTextWidth, componentScale, pixelMode)
            drawText(canvas, resources, name, textX, originY + 7.0f * componentScale,
                12.0f * componentScale, HudText.withAlpha(0xFFF5F5, alpha), componentScale, pixelMode)
            drawText(canvas, resources, snapshot.healthText(), textX, originY + 22.0f * componentScale,
                12.0f * componentScale, HudText.withAlpha(0xD2D4D8, alpha), componentScale, pixelMode)
            drawText(canvas, resources, snapshot.hitText(), textX, originY + 34.0f * componentScale,
                12.0f * componentScale, HudText.withAlpha(0xFFD75A, alpha), componentScale, pixelMode)

            val barY = originY + 52.0f * componentScale
            val barWidth = 103.0f * componentScale
            fill.color = HudText.withAlpha(0x53565D, min(150, alpha))
            canvas.drawRRect(RRect.makeXYWH(textX, barY, barWidth, 4.0f * componentScale,
                2.0f * componentScale), fill)
            val filled = barWidth * snapshot.healthRatio().toFloat().coerceIn(0.0f, 1.0f)
            if (filled > 0.0f) {
                fill.color = HudText.withAlpha(snapshot.healthColor(), alpha)
                canvas.drawRRect(RRect.makeXYWH(textX, barY, filled, 4.0f * componentScale,
                    2.0f * componentScale), fill)
            }
        } finally {
            fill.close()
        }
    }

    private fun fitText(
        resources: TextResources,
        value: String,
        maxWidth: Float,
        scale: Float,
        pixelMode: Boolean
    ): String {
        if (measure(resources, value, scale, pixelMode) <= maxWidth) return value
        val suffix = "..."
        var result = value
        while (result.isNotEmpty()
            && measure(resources, result + suffix, scale, pixelMode) > maxWidth) {
            result = result.dropLast(1)
        }
        return result + suffix
    }

    private fun drawModuleBar(
        canvas: org.jetbrains.skia.Canvas,
        resources: TextResources,
        x: Float,
        y: Float,
        width: Float,
        rowHeight: Float,
        row: Int,
        sampleOffset: Float,
        seconds: Double,
        alpha: Int,
        physicalScale: Float
    ) {
        val inset = min(rowHeight * 0.22f, max(1.0f, physicalScale * 0.75f))
        val height = max(1.0f, rowHeight - inset * 2.0f)
        resources.barPaint.color = HudText.withAlpha(
            HudText.hudNameColor(row, sampleOffset.toDouble(), seconds), alpha
        )
        canvas.drawRect(Rect.makeXYWH(x, y + inset, width, height), resources.barPaint)
    }

    private fun drawPanel(
        canvas: org.jetbrains.skia.Canvas,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        overallAlpha: Int,
        animationProgress: Double = 1.0
    ) {
        val panelAlpha = min(overallAlpha,
            (255.0 * HudConfig.PANEL_OPACITY.get() / 100.0 * animationProgress).toInt())
        if (panelAlpha <= 0) return
        textResources.panelPaint.color = HudText.withAlpha(HudText.hudBackgroundColor(), panelAlpha)
        canvas.drawRect(Rect.makeXYWH(x, y, width, height), textResources.panelPaint)
    }

    private fun drawModuleName(
        canvas: org.jetbrains.skia.Canvas,
        resources: TextResources,
        value: String,
        x: Float,
        y: Float,
        rowHeight: Float,
        row: Int,
        seconds: Double,
        alpha: Int,
        physicalScale: Float,
        pixelMode: Boolean,
        width: Float
    ) {
        val horizontal = HudConfig.NAME_COLOR_MODE.get() != HudConfig.NameColorMode.FIXED
            && (HudConfig.NAME_COLOR_MODE.get() == HudConfig.NameColorMode.RAINBOW
                || HudConfig.GRADIENT_DIRECTION.get() == HudConfig.GradientDirection.HORIZONTAL)
        if (pixelMode && horizontal) {
            var cursor = x
            value.codePoints().forEach { codePoint ->
                val glyph = String(Character.toChars(codePoint))
                val glyphWidth = resources.pixelFont.measure(glyph, physicalScale)
                val color = HudText.withAlpha(
                    HudText.hudNameColor(row, (cursor - x + glyphWidth * 0.5f).toDouble(), seconds), alpha
                )
                resources.pixelFont.draw(
                    canvas, glyph, cursor, centeredPixelTop(y, rowHeight, physicalScale),
                    physicalScale, color, HudConfig.TEXT_SHADOW.get()
                )
                cursor += glyphWidth
            }
            return
        }

        if (!pixelMode && horizontal && width > 0.5f) {
            drawSmoothText(
                canvas, resources, value, x, y, rowHeight,
                HudText.withAlpha(HudText.hudNameColor(row, 0.0, seconds), alpha),
                gradientShader(row, x, width, seconds, alpha)
            )
            return
        }

        val color = HudText.withAlpha(HudText.hudNameColor(row, seconds), alpha)
        drawText(canvas, resources, value, x, y, rowHeight, color, physicalScale, pixelMode)
    }

    private fun gradientShader(row: Int, x: Float, width: Float, seconds: Double, alpha: Int): Shader {
        val colors = IntArray(5) { index ->
            val offset = width * index / 4.0f
            HudText.withAlpha(HudText.hudNameColor(row, offset.toDouble(), seconds), alpha)
        }
        return Shader.makeLinearGradient(Point(x, 0.0f), Point(x + width, 0.0f), colors)
    }

    private fun drawText(
        canvas: org.jetbrains.skia.Canvas,
        resources: TextResources,
        value: String,
        x: Float,
        y: Float,
        rowHeight: Float,
        color: Int,
        physicalScale: Float,
        pixelMode: Boolean
    ) {
        if (pixelMode) {
            resources.pixelFont.draw(
                canvas, value, x, centeredPixelTop(y, rowHeight, physicalScale),
                physicalScale, color, HudConfig.TEXT_SHADOW.get()
            )
        } else {
            drawSmoothText(canvas, resources, value, x, y, rowHeight, color, null)
        }
    }

    private fun drawSmoothText(
        canvas: org.jetbrains.skia.Canvas,
        resources: TextResources,
        value: String,
        x: Float,
        y: Float,
        rowHeight: Float,
        color: Int,
        shader: Shader?
    ) {
        val metrics = resources.font.metrics
        val baseline = y + (rowHeight - metrics.height) * 0.5f - metrics.ascent
        if (HudConfig.TEXT_SHADOW.get()) {
            resources.shadowPaint.color = HudText.withAlpha(0xFF000000.toInt(), min(150, color ushr 24))
            canvas.drawString(value, x + 1.0f, baseline + 1.0f, resources.font, resources.shadowPaint)
        }
        resources.textPaint.color = if (shader == null) color else 0xFFFFFFFF.toInt()
        resources.textPaint.shader = shader
        canvas.drawString(value, x, baseline, resources.font, resources.textPaint)
        resources.textPaint.shader = null
        shader?.close()
    }

    private fun centeredPixelTop(y: Float, rowHeight: Float, physicalScale: Float): Float =
        y + (rowHeight - 8.0f * physicalScale) * 0.5f

    private fun measure(
        resources: TextResources,
        value: String,
        physicalScale: Float,
        pixelMode: Boolean
    ): Float = if (pixelMode) resources.pixelFont.measure(value, physicalScale)
        else resources.font.measureTextWidth(value)

    private fun safeTag(module: ModuleRegistry.Module): String {
        return try {
            val raw = module.tag().get() ?: return ""
            if (raw.matches(Regex("(?i)on|enable|enabled|active"))) return ""
            val trimmed = raw.trim()
            if (trimmed.matches(Regex("[A-Za-z][A-Za-z0-9_-]*"))) {
                ModuleRegistry.displayChoice(trimmed)
            } else trimmed
        } catch (_: RuntimeException) {
            ""
        }
    }

    private fun stableTagTemplate(tag: String): String = tag
        .replace(Regex("[-+]?\\d+(?:\\.\\d+)?(?=%)"), "100")
        .replace(Regex("\\d"), "8")

    private fun ensureSurface(width: Int, height: Int) {
        if (surface != null && surfaceWidth == width && surfaceHeight == height) return
        closeSurface()
        val newContext = DirectContext.makeGL().also { context = it }
        val newTarget = BackendRenderTarget.makeGL(
            width, height, 0, 0, 0, FramebufferFormat.GR_GL_RGBA8
        ).also { target = it }
        surface = SkiaSurface.makeFromBackendRenderTarget(
            newContext, newTarget, SurfaceOrigin.BOTTOM_LEFT,
            SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB
        )
        surfaceWidth = width
        surfaceHeight = height
    }

    private fun closeSurface() {
        surface?.close()
        target?.close()
        context?.close()
        surface = null
        target = null
        context = null
        surfaceWidth = -1
        surfaceHeight = -1
    }

    @JvmStatic
    fun close() {
        closeSurface()
        moduleEntries.clear()
        lastAnimationNanos = 0L
        loadedTextResources?.close()
        loadedTextResources = null
    }

    private data class Row(
        val entry: ModuleEntry,
        val tag: String,
        val nameWidth: Float,
        val separatorWidth: Float,
        val actualTagWidth: Float,
        val stableTagWidth: Float,
        val width: Float
    )

    private class ModuleEntry(
        var module: ModuleRegistry.Module,
        var tag: String = safeTag(module),
        var progress: Double = 0.0,
        var positionRows: Double = Double.NaN,
        var present: Boolean = true
    )

    private class TextResources private constructor(
        val fontData: Data,
        val typeface: Typeface,
        val font: Font,
        val textPaint: Paint,
        val shadowPaint: Paint,
        val panelPaint: Paint,
        val barPaint: Paint,
        val pixelFont: MinecraftPixelFont
    ) {
        fun close() {
            pixelFont.close()
            barPaint.close()
            panelPaint.close()
            shadowPaint.close()
            textPaint.close()
            font.close()
            typeface.close()
            fontData.close()
        }

        companion object {
            fun load(): TextResources {
                val loader = TextGuiSkiaOverlay::class.java.classLoader
                val inter = requireNotNull(loader.getResourceAsStream(INTER_RESOURCE)) {
                    "Missing embedded HUD font"
                }.use { it.readAllBytes() }
                val data = Data.makeFromBytes(inter)
                val typeface = requireNotNull(FontMgr.default.makeFromData(data)) {
                    "Invalid embedded HUD font"
                }
                val font = Font(typeface, 9.0f).apply {
                    edging = FontEdging.SUBPIXEL_ANTI_ALIAS
                    isSubpixel = true
                    isLinearMetrics = true
                }
                val textPaint = Paint().apply { isAntiAlias = true }
                val shadowPaint = Paint().apply { isAntiAlias = true }
                val panelPaint = Paint().apply { isAntiAlias = false }
                val barPaint = Paint().apply { isAntiAlias = false }
                return TextResources(
                    data, typeface, font, textPaint, shadowPaint, panelPaint, barPaint,
                    MinecraftPixelFont.load(loader)
                )
            }
        }
    }

    private class MinecraftPixelFont(
        private val image: Image,
        private val advances: IntArray
    ) {
        fun measure(value: String, scale: Float): Float {
            var width = 0.0f
            value.codePoints().forEach { width += advances[glyphIndex(it)] * scale }
            return width
        }

        fun draw(
            canvas: org.jetbrains.skia.Canvas,
            value: String,
            x: Float,
            y: Float,
            scale: Float,
            color: Int,
            shadow: Boolean
        ) {
            if (shadow) drawPass(canvas, value, x + scale, y + scale, scale,
                HudText.withAlpha(0xFF000000.toInt(), min(150, color ushr 24)))
            drawPass(canvas, value, x, y, scale, color)
        }

        private fun drawPass(
            canvas: org.jetbrains.skia.Canvas,
            value: String,
            x: Float,
            y: Float,
            scale: Float,
            color: Int
        ) {
            val filter = ColorFilter.makeBlend(color, BlendMode.SRC_IN)
            val paint = Paint().apply {
                isAntiAlias = false
                colorFilter = filter
            }
            try {
                var cursor = x
                value.codePoints().forEach { codePoint ->
                    val glyph = glyphIndex(codePoint)
                    if (glyph != 32) {
                        val sourceX = (glyph and 15) * 8.0f
                        val sourceY = (glyph ushr 4) * 8.0f
                        canvas.drawImageRect(
                            image,
                            Rect.makeXYWH(sourceX, sourceY, 8.0f, 8.0f),
                            Rect.makeXYWH(cursor, y, 8.0f * scale, 8.0f * scale),
                            SamplingMode.DEFAULT,
                            paint,
                            true
                        )
                    }
                    cursor += advances[glyph] * scale
                }
            } finally {
                paint.close()
                filter.close()
            }
        }

        fun close() = image.close()

        companion object {
            fun load(loader: ClassLoader): MinecraftPixelFont {
                val encoded = requireNotNull(loader.getResourceAsStream(MINECRAFT_FONT_RESOURCE)) {
                    "Missing embedded Minecraft HUD font"
                }.bufferedReader().use { it.readText() }
                val png = Base64.getMimeDecoder().decode(encoded)
                val image = Image.makeFromEncoded(png)
                val buffered = requireNotNull(ImageIO.read(ByteArrayInputStream(png)))
                val advances = IntArray(256) { glyph ->
                    if (glyph == 32) return@IntArray 4
                    val originX = (glyph and 15) * 8
                    val originY = (glyph ushr 4) * 8
                    var right = -1
                    for (pixelY in 0 until 8) {
                        for (pixelX in 0 until 8) {
                            if ((buffered.getRGB(originX + pixelX, originY + pixelY) ushr 24) != 0) {
                                right = max(right, pixelX)
                            }
                        }
                    }
                    (right + 2).coerceIn(2, 8)
                }
                return MinecraftPixelFont(image, advances)
            }

            private fun glyphIndex(codePoint: Int): Int =
                if (codePoint in 0..255) codePoint else '?'.code
        }

        private fun glyphIndex(codePoint: Int): Int = Companion.glyphIndex(codePoint)
    }
}
