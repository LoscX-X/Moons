package com.blanoir.moons.client.ui.hud

import com.blanoir.moons.client.access.MinecraftClientAccess
import com.blanoir.moons.client.module.framework.ModuleRegistry
import com.blanoir.moons.client.module.impl.render.TargetInfoHud
import com.blanoir.moons.client.ui.MinecraftScreenAccess
import com.blanoir.moons.client.ui.animation.Animation
import com.blanoir.moons.client.ui.clickgui.MoonsComposeScreen
import com.blanoir.moons.client.ui.compose.FinalFrameSurface
import com.blanoir.moons.client.ui.layout.Bounds
import com.blanoir.moons.client.utils.io.EmbeddedResources
import com.blanoir.moons.client.utils.time.FrameClock
import com.mojang.blaze3d.systems.RenderSystem
import java.util.LinkedHashMap
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import net.minecraft.client.Minecraft
import org.jetbrains.skia.Data
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontEdging
import org.jetbrains.skia.FontHinting
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.Point
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Shader
import org.jetbrains.skia.TextBlob
import org.jetbrains.skia.Typeface

/** TextGUI and TargetInfo painted by Skia through the selected version's final-frame surface. */
object TextGuiSkiaOverlay {
    private const val INTER_RESOURCE = "assets/moons/font/inter-frozen-medium.otf"
    private const val MINECRAFT_FONT_RESOURCE = "assets/moons/font/minecraft-ascii.ttf"
    private const val TAG_SEPARATOR = " - "
    private val hiddenTagPattern = Regex("(?i)on|enable|enabled|active")
    private val choiceTagPattern = Regex("[A-Za-z][A-Za-z0-9_-]*")
    private val percentagePattern = Regex("[-+]?\\d+(?:\\.\\d+)?(?=%)")
    private val digitPattern = Regex("\\d")
    private val normalizedTags = LinkedHashMap<String, String>()

    private val frameSurface = FinalFrameSurface()
    private var startedNanos = 0L
    private val animationClock = FrameClock(0.0)
    private val moduleEntries = LinkedHashMap<String, ModuleEntry>()
    private val sortedRows = ArrayList<Row>()
    private val rowComparator = compareByDescending<Row> { it.width }.thenBy { it.name }
    private var rowsDirty = true
    private var rowScale = Float.NaN
    private var rowPixelMode = false
    private val drawnBounds = DrawnBounds()
    private var fittedValue = ""
    private var fittedResult = ""
    private var fittedWidth = Float.NaN
    private var fittedScale = Float.NaN
    private var fittedPixelMode = false

    private var loadedTextResources: TextResources? = null
    private val textResources: TextResources
        get() = loadedTextResources ?: TextResources.load().also { loadedTextResources = it }

    @JvmStatic
    fun renderFrame(editorVisible: Boolean) {
        RenderSystem.assertOnRenderThread()
        val client = Minecraft.getInstance()
        if (client.player == null || client.level == null) {
            moduleEntries.clear()
            sortedRows.clear()
            rowsDirty = true
            animationClock.reset()
            MoonsHud.updateExternalBounds(Bounds(0.0, 0.0, 0.0, 0.0), HudConfig.SCALE.get())
            return
        }

        val currentScreen = MinecraftScreenAccess.current(client)
        if (currentScreen is MoonsComposeScreen && !editorVisible) {
            animationClock.reset()
            TargetInfoHud.snapshot(false)
            return
        }
        if (!editorVisible && MinecraftClientAccess.isHudHidden(client)) {
            animationClock.reset()
            return
        }

        val now = System.nanoTime()
        val animationSeconds = animationClock.nextDeltaSeconds(now)
        updateModuleEntries(
            if (editorVisible || HudConfig.VISIBLE.get()) ModuleRegistry.enabledModules()
            else emptyList(),
            animationSeconds,
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

        drawnBounds.reset()
        frameSurface.render(width, height, contentBounds = { drawnBounds.rect() }) { canvas ->
            if (drawTextGui)
                drawTextGui(
                    canvas,
                    client,
                    editorVisible,
                    seconds,
                    animationSeconds,
                )
            if (targetSnapshot.visible()) drawTargetInfo(canvas, targetSnapshot)
        }
    }

    private fun drawTextGui(
        canvas: org.jetbrains.skia.Canvas,
        client: Minecraft,
        editing: Boolean,
        seconds: Double,
        animationSeconds: Double,
    ) {
        val guiScale = client.window.guiScale.coerceAtLeast(1).toFloat()
        val hudScale = HudConfig.SCALE.get().toFloat()
        val physicalScale = guiScale * hudScale
        val fontSize = 9.0f * physicalScale
        val rowHeight = max(12.0f * physicalScale, fontSize + 3.0f * physicalScale)
        val titleGap = 3.0f * physicalScale
        val padding = 2.0f * physicalScale
        val barGap = 2.0f * physicalScale
        val barWidth = max(1.0f, 1.25f * physicalScale)
        val pixelMode = HudConfig.FONT_MODE.get() == HudConfig.HudFontMode.MINECRAFT

        val resources = textResources
        resources.configureFont(fontSize)
        val header =
            if ((editing || HudConfig.VISIBLE.get()) && HudConfig.SHOW_TITLE.get())
                HudConfig.header(client)
            else ""
        if (rowScale != physicalScale || rowPixelMode != pixelMode) {
            rowScale = physicalScale
            rowPixelMode = pixelMode
            rowsDirty = true
            moduleEntries.values.forEach { it.row = null }
        }
        if (rowsDirty) {
            sortedRows.clear()
            moduleEntries.values.forEach { entry ->
                val row =
                    entry.row
                        ?: run {
                            val name = entry.module.name()
                            val nameWidth = measure(resources, name, physicalScale, pixelMode)
                            val tag = entry.tag
                            val separatorWidth =
                                if (tag.isBlank()) 0.0f
                                else measure(resources, TAG_SEPARATOR, physicalScale, pixelMode)
                            val actualTagWidth =
                                if (tag.isBlank()) 0.0f
                                else measure(resources, tag, physicalScale, pixelMode)
                            val stableTagWidth =
                                if (tag.isBlank()) 0.0f
                                else
                                    max(
                                        actualTagWidth,
                                        measure(
                                            resources,
                                            stableTagTemplate(tag),
                                            physicalScale,
                                            pixelMode,
                                        ),
                                    )
                            Row(
                                    entry,
                                    name,
                                    tag,
                                    nameWidth,
                                    separatorWidth,
                                    actualTagWidth,
                                    stableTagWidth,
                                    nameWidth +
                                        separatorWidth +
                                        stableTagWidth +
                                        padding +
                                        barGap +
                                        barWidth,
                                )
                                .also { entry.row = it }
                        }
                sortedRows.add(row)
            }
            sortedRows.sortWith(rowComparator)
            rowsDirty = false
        }
        val rows = sortedRows

        rows.forEachIndexed { index, row ->
            val target = index.toDouble()
            row.entry.positionRows =
                if (row.entry.positionRows.isNaN()) target
                else Animation.approach(row.entry.positionRows, target, animationSeconds, 21.0)
        }

        val headerWidth =
            if (header.isBlank()) 0.0f else measure(resources, header, physicalScale, pixelMode)
        val preview = "HUD Preview"
        val previewWidth =
            if (rows.isEmpty())
                measure(resources, preview, physicalScale, pixelMode) + 7.0f * physicalScale
            else 0.0f
        val maximumWidth =
            max(1.0f, max(headerWidth, max(previewWidth, rows.maxOfOrNull { it.width } ?: 0.0f)))

        val guiWidth = client.window.width / guiScale
        val guiHeight = client.window.height / guiScale
        val unscaledWidth = floor(guiWidth / hudScale)
        val rightLogical =
            if (HudConfig.POSITION_X.get() < 0) unscaledWidth - 5.0f
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
                (totalHeight / guiScale).toDouble(),
            ),
            hudScale.toDouble(),
        )

        val overallAlpha = (255.0 * HudConfig.ALPHA.get() / 100.0).toInt().coerceIn(0, 255)
        if (header.isNotBlank()) {
            drawText(
                canvas,
                resources,
                header,
                right - headerWidth,
                y,
                rowHeight,
                HudText.withAlpha(HudText.titleColor(), overallAlpha),
                physicalScale,
                pixelMode,
            )
            y += titleHeight
        }

        if (rows.isEmpty() && editing) {
            val cardLeft = right - previewWidth
            drawPanel(canvas, cardLeft, y, previewWidth, rowHeight, overallAlpha)
            drawText(
                canvas,
                resources,
                preview,
                cardLeft + 3.0f * physicalScale,
                y,
                rowHeight,
                HudText.withAlpha(HudText.hudNameColor(0, seconds), overallAlpha),
                physicalScale,
                pixelMode,
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
                canvas,
                resources,
                row.name,
                textX,
                rowY,
                rowHeight,
                index,
                seconds,
                animatedAlpha,
                physicalScale,
                pixelMode,
                row.nameWidth,
            )
            if (row.tag.isNotBlank()) {
                val parameterColor = HudText.withAlpha(HudText.parameterColor(), animatedAlpha)
                drawText(
                    canvas,
                    resources,
                    TAG_SEPARATOR,
                    textX + row.nameWidth,
                    rowY,
                    rowHeight,
                    parameterColor,
                    physicalScale,
                    pixelMode,
                )
                val tagX = textX + row.nameWidth + row.separatorWidth
                +row.stableTagWidth - row.actualTagWidth
                drawText(
                    canvas,
                    resources,
                    row.tag,
                    tagX,
                    rowY,
                    rowHeight,
                    parameterColor,
                    physicalScale,
                    pixelMode,
                )
            }
            drawModuleBar(
                canvas,
                resources,
                right - barWidth,
                rowY,
                barWidth,
                rowHeight,
                index,
                row.nameWidth,
                seconds,
                animatedAlpha,
                physicalScale,
            )
        }
    }

    private fun updateModuleEntries(
        active: List<ModuleRegistry.Module>,
        animationSeconds: Double,
    ) {
        moduleEntries.values.forEach { it.present = false }
        active.forEach { module ->
            val entry =
                moduleEntries.getOrPut(module.id()) {
                    rowsDirty = true
                    ModuleEntry(module)
                }
            val tag = safeTag(module)
            if (entry.tag != tag || entry.module.name() != module.name()) {
                entry.tag = tag
                entry.row = null
                rowsDirty = true
            }
            entry.module = module
            entry.present = true
        }
        moduleEntries.values.forEach { entry ->
            entry.progress =
                Animation.approach(
                    entry.progress,
                    if (entry.present) 1.0 else 0.0,
                    animationSeconds,
                    16.0,
                )
        }
        val iterator = moduleEntries.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!entry.present && entry.progress < 0.015) {
                iterator.remove()
                rowsDirty = true
            }
        }
    }

    private fun drawTargetInfo(
        canvas: org.jetbrains.skia.Canvas,
        snapshot: TargetInfoHud.Snapshot,
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
        resources.configureFont(9.0f * componentScale)
        val fill = resources.targetPaint
        val edge = max(2.0f, componentScale)
        drawnBounds.include(
            originX - edge,
            originY - edge,
            originX + 166.0f * componentScale + edge,
            originY + 63.0f * componentScale + edge,
        )
        try {
            fill.color = HudText.withAlpha(0x252933, min(224, alpha))
            canvas.drawRRect(
                RRect.makeXYWH(
                    originX,
                    originY,
                    166.0f * componentScale,
                    63.0f * componentScale,
                    10.0f * componentScale,
                ),
                fill,
            )

            fill.mode = PaintMode.STROKE
            fill.strokeWidth = max(1.0f, componentScale)
            fill.color = HudText.withAlpha(0x697083, min(92, alpha))
            canvas.drawRRect(
                RRect.makeXYWH(
                    originX,
                    originY,
                    166.0f * componentScale,
                    63.0f * componentScale,
                    10.0f * componentScale,
                ),
                fill,
            )
            fill.mode = PaintMode.FILL

            val faceX = originX + 8.0f * componentScale
            val faceY = originY + 8.0f * componentScale
            val faceSize = 38.0f * componentScale
            fill.color = HudText.withAlpha(snapshot.healthColor(), min(108, alpha))
            canvas.drawRRect(
                RRect.makeXYWH(
                    faceX - 2.0f * componentScale,
                    faceY - 2.0f * componentScale,
                    faceSize + 4.0f * componentScale,
                    faceSize + 4.0f * componentScale,
                    5.0f * componentScale,
                ),
                fill,
            )
            fill.color = HudText.withAlpha(0x3A3E49, alpha)
            canvas.drawRRect(
                RRect.makeXYWH(
                    faceX,
                    faceY,
                    faceSize,
                    faceSize,
                    3.0f * componentScale,
                ),
                fill,
            )

            val initial = snapshot.name().trim().take(1).uppercase().ifBlank { "?" }
            val initialScale = componentScale * 1.35f
            val initialWidth = measure(resources, initial, initialScale, pixelMode)
            drawText(
                canvas,
                resources,
                initial,
                faceX + (faceSize - initialWidth) * 0.5f,
                faceY,
                faceSize,
                HudText.withAlpha(0xFFF5F5, alpha),
                initialScale,
                pixelMode,
            )

            val textX = originX + 54.0f * componentScale
            val maxTextWidth = 104.0f * componentScale
            val name = fitText(resources, snapshot.name(), maxTextWidth, componentScale, pixelMode)
            drawText(
                canvas,
                resources,
                name,
                textX,
                originY + 7.0f * componentScale,
                12.0f * componentScale,
                HudText.withAlpha(0xFFF5F5, alpha),
                componentScale,
                pixelMode,
            )
            drawText(
                canvas,
                resources,
                snapshot.healthText(),
                textX,
                originY + 22.0f * componentScale,
                12.0f * componentScale,
                HudText.withAlpha(0xD2D4D8, alpha),
                componentScale,
                pixelMode,
            )
            drawText(
                canvas,
                resources,
                snapshot.hitText(),
                textX,
                originY + 34.0f * componentScale,
                12.0f * componentScale,
                HudText.withAlpha(0xFFD75A, alpha),
                componentScale,
                pixelMode,
            )

            val barY = originY + 52.0f * componentScale
            val barWidth = 103.0f * componentScale
            fill.color = HudText.withAlpha(0x53565D, min(150, alpha))
            canvas.drawRRect(
                RRect.makeXYWH(
                    textX,
                    barY,
                    barWidth,
                    4.0f * componentScale,
                    2.0f * componentScale,
                ),
                fill,
            )
            val filled = barWidth * snapshot.healthRatio().toFloat().coerceIn(0.0f, 1.0f)
            if (filled > 0.0f) {
                fill.color = HudText.withAlpha(snapshot.healthColor(), alpha)
                canvas.drawRRect(
                    RRect.makeXYWH(
                        textX,
                        barY,
                        filled,
                        4.0f * componentScale,
                        2.0f * componentScale,
                    ),
                    fill,
                )
            }
        } finally {
            fill.mode = PaintMode.FILL
        }
    }

    private fun fitText(
        resources: TextResources,
        value: String,
        maxWidth: Float,
        scale: Float,
        pixelMode: Boolean,
    ): String {
        if (
            value == fittedValue &&
                maxWidth == fittedWidth &&
                scale == fittedScale &&
                pixelMode == fittedPixelMode
        )
            return fittedResult
        fittedValue = value
        fittedWidth = maxWidth
        fittedScale = scale
        fittedPixelMode = pixelMode
        if (measure(resources, value, scale, pixelMode) <= maxWidth) {
            fittedResult = value
            return value
        }
        val suffix = "..."
        var result = value
        while (
            result.isNotEmpty() && measure(resources, result + suffix, scale, pixelMode) > maxWidth
        ) {
            result = result.dropLast(1)
        }
        fittedResult = result + suffix
        return fittedResult
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
        physicalScale: Float,
    ) {
        val inset = min(rowHeight * 0.22f, max(1.0f, physicalScale * 0.75f))
        val height = max(1.0f, rowHeight - inset * 2.0f)
        resources.barPaint.color =
            HudText.withAlpha(
                HudText.hudNameColor(row, sampleOffset.toDouble(), seconds),
                alpha,
            )
        drawnBounds.include(x, y + inset, x + width, y + inset + height)
        canvas.drawRect(Rect.makeXYWH(x, y + inset, width, height), resources.barPaint)
    }

    private fun drawPanel(
        canvas: org.jetbrains.skia.Canvas,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        overallAlpha: Int,
        animationProgress: Double = 1.0,
    ) {
        val panelAlpha =
            min(
                overallAlpha,
                (255.0 * HudConfig.PANEL_OPACITY.get() / 100.0 * animationProgress).toInt(),
            )
        if (panelAlpha <= 0) return
        drawnBounds.include(x, y, x + width, y + height)
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
        width: Float,
    ) {
        val horizontal =
            HudConfig.NAME_COLOR_MODE.get() != HudConfig.NameColorMode.FIXED &&
                (HudConfig.NAME_COLOR_MODE.get() == HudConfig.NameColorMode.RAINBOW ||
                    HudConfig.GRADIENT_DIRECTION.get() == HudConfig.GradientDirection.HORIZONTAL)
        if (pixelMode && horizontal) {
            val top = centeredPixelTop(y, rowHeight, physicalScale)
            gradientShader(row, x, max(width, 0.5f), seconds, alpha).use { shader ->
                resources.pixelFont.drawGradient(
                    canvas,
                    value,
                    x,
                    top,
                    physicalScale,
                    HudText.withAlpha(0xFFFFFFFF.toInt(), alpha),
                    HudConfig.TEXT_SHADOW.get(),
                    shader,
                )
            }
            return
        }

        if (!pixelMode && horizontal && width > 0.5f) {
            drawSmoothText(
                canvas,
                resources,
                value,
                x,
                y,
                rowHeight,
                HudText.withAlpha(HudText.hudNameColor(row, 0.0, seconds), alpha),
                gradientShader(row, x, width, seconds, alpha),
            )
            return
        }

        val color = HudText.withAlpha(HudText.hudNameColor(row, seconds), alpha)
        drawText(canvas, resources, value, x, y, rowHeight, color, physicalScale, pixelMode)
    }

    private fun gradientShader(
        row: Int,
        x: Float,
        width: Float,
        seconds: Double,
        alpha: Int,
    ): Shader {
        val colors =
            IntArray(5) { index ->
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
        pixelMode: Boolean,
    ) {
        if (pixelMode) {
            resources.pixelFont.draw(
                canvas,
                value,
                x,
                centeredPixelTop(y, rowHeight, physicalScale),
                physicalScale,
                color,
                HudConfig.TEXT_SHADOW.get(),
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
        shader: Shader?,
    ) {
        val baseline = y + (rowHeight - resources.fontHeight) * 0.5f - resources.fontAscent
        // Font overhang and shadow can extend beyond the logical row while it slides in.
        val margin = max(2.0f, resources.fontSize * 2.0f)
        drawnBounds.include(
            x - margin,
            baseline - margin,
            x + resources.measure(value) + margin,
            baseline + margin,
        )
        if (HudConfig.TEXT_SHADOW.get()) {
            resources.shadowPaint.color =
                HudText.withAlpha(0xFF000000.toInt(), min(150, color ushr 24))
            canvas.drawString(
                value,
                x + 1.0f,
                baseline + 1.0f,
                resources.font,
                resources.shadowPaint,
            )
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
        pixelMode: Boolean,
    ): Float =
        if (pixelMode) resources.pixelFont.measure(value, physicalScale)
        else resources.measure(value)

    private fun safeTag(module: ModuleRegistry.Module): String {
        return try {
            val raw = module.tag().get() ?: return ""
            normalizedTags[raw]?.let {
                return it
            }
            val trimmed = raw.trim()
            val normalized =
                if (raw.matches(hiddenTagPattern)) ""
                else if (trimmed.matches(choiceTagPattern)) ModuleRegistry.displayChoice(trimmed)
                else trimmed
            if (normalizedTags.size >= 256) normalizedTags.remove(normalizedTags.keys.first())
            normalized.also { normalizedTags[raw] = it }
        } catch (_: RuntimeException) {
            ""
        }
    }

    private fun stableTagTemplate(tag: String): String =
        tag.replace(percentagePattern, "100").replace(digitPattern, "8")

    @JvmStatic
    fun close() {
        frameSurface.close()
        moduleEntries.clear()
        sortedRows.clear()
        rowsDirty = true
        normalizedTags.clear()
        animationClock.reset()
        loadedTextResources?.close()
        loadedTextResources = null
    }

    private data class Row(
        val entry: ModuleEntry,
        val name: String,
        val tag: String,
        val nameWidth: Float,
        val separatorWidth: Float,
        val actualTagWidth: Float,
        val stableTagWidth: Float,
        val width: Float,
    )

    private class ModuleEntry(
        var module: ModuleRegistry.Module,
        var tag: String = safeTag(module),
        var progress: Double = 0.0,
        var positionRows: Double = Double.NaN,
        var present: Boolean = true,
        var row: Row? = null,
    )

    /** Accumulates physical pixels, independently of the editor's logical drag bounds. */
    private class DrawnBounds {
        private var left = Float.POSITIVE_INFINITY
        private var top = Float.POSITIVE_INFINITY
        private var right = Float.NEGATIVE_INFINITY
        private var bottom = Float.NEGATIVE_INFINITY

        fun reset() {
            left = Float.POSITIVE_INFINITY
            top = Float.POSITIVE_INFINITY
            right = Float.NEGATIVE_INFINITY
            bottom = Float.NEGATIVE_INFINITY
        }

        fun include(x0: Float, y0: Float, x1: Float, y1: Float) {
            left = min(left, x0)
            top = min(top, y0)
            right = max(right, x1)
            bottom = max(bottom, y1)
        }

        fun rect(): Rect? =
            if (left < right && top < bottom) Rect.makeXYWH(left, top, right - left, bottom - top)
            else null
    }

    private class WidthCache(private val font: Font) {
        private val bySize = LinkedHashMap<Float, LinkedHashMap<String, Float>>()
        private var currentSize = Float.NaN
        private var current = LinkedHashMap<String, Float>()

        fun measure(value: String, size: Float): Float {
            if (currentSize != size) {
                currentSize = size
                current =
                    bySize.getOrPut(size) {
                        if (bySize.size >= 8) bySize.remove(bySize.keys.first())
                        LinkedHashMap()
                    }
            }
            return current[value]
                ?: run {
                    if (current.size >= 512) current.remove(current.keys.first())
                    font.measureTextWidth(value).also { current[value] = it }
                }
        }
    }

    private class TextResources
    private constructor(
        val fontData: Data,
        val typeface: Typeface,
        val font: Font,
        val textPaint: Paint,
        val shadowPaint: Paint,
        val panelPaint: Paint,
        val barPaint: Paint,
        val targetPaint: Paint,
        val pixelFont: MinecraftPixelFont,
    ) {
        private val widths = WidthCache(font)
        private val metricsBySize = LinkedHashMap<Float, Pair<Float, Float>>()
        var fontSize = Float.NaN
            private set

        var fontHeight = 0.0f
            private set

        var fontAscent = 0.0f
            private set

        fun configureFont(size: Float) {
            if (fontSize == size) return
            font.size = size
            fontSize = size
            val metrics =
                metricsBySize.getOrPut(size) {
                    if (metricsBySize.size >= 8) metricsBySize.remove(metricsBySize.keys.first())
                    font.metrics.let { it.height to it.ascent }
                }
            fontHeight = metrics.first
            fontAscent = metrics.second
        }

        fun measure(value: String): Float = widths.measure(value, fontSize)

        fun close() {
            pixelFont.close()
            targetPaint.close()
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
                val inter =
                    EmbeddedResources.readRequiredBytes(
                        loader,
                        INTER_RESOURCE,
                        "Missing embedded HUD font",
                    )
                val data = Data.makeFromBytes(inter)
                val typeface =
                    requireNotNull(FontMgr.default.makeFromData(data)) {
                        "Invalid embedded HUD font"
                    }
                val font =
                    Font(typeface, 9.0f).apply {
                        edging = FontEdging.SUBPIXEL_ANTI_ALIAS
                        isSubpixel = true
                        isLinearMetrics = true
                    }
                val textPaint = Paint().apply { isAntiAlias = true }
                val shadowPaint = Paint().apply { isAntiAlias = true }
                val panelPaint = Paint().apply { isAntiAlias = false }
                val barPaint = Paint().apply { isAntiAlias = false }
                return TextResources(
                    data,
                    typeface,
                    font,
                    textPaint,
                    shadowPaint,
                    panelPaint,
                    barPaint,
                    Paint().apply { isAntiAlias = true },
                    MinecraftPixelFont.load(loader),
                )
            }
        }
    }

    private class MinecraftPixelFont(
        private val data: Data,
        private val typeface: Typeface,
        private val font: Font,
        private val paint: Paint,
    ) {
        private val runsBySize = LinkedHashMap<Float, LinkedHashMap<String, PixelRun>>()
        private var runs = LinkedHashMap<String, PixelRun>()
        private var fontSize = Float.NaN

        private fun setScale(scale: Float) {
            val size = 8.0f * scale
            if (fontSize != size) {
                font.size = size
                fontSize = size
                runs =
                    runsBySize.getOrPut(size) {
                        if (runsBySize.size >= 4) {
                            runsBySize.remove(runsBySize.keys.first())?.values?.forEach {
                                it.blob?.close()
                            }
                        }
                        LinkedHashMap()
                    }
            }
        }

        private fun textRun(value: String, scale: Float): PixelRun {
            setScale(scale)
            return runs[value]
                ?: run {
                    if (runs.size >= 256) runs.remove(runs.keys.first())?.blob?.close()
                    val glyphs = font.getStringGlyphs(value)
                    val advances = font.getWidths(glyphs)
                    val positions = FloatArray(glyphs.size)
                    var cursor = 0.0f
                    for (index in glyphs.indices) {
                        positions[index] = cursor
                        cursor += advances[index]
                        if (index + 1 < glyphs.size) cursor += 0.25f * scale
                    }
                    PixelRun(
                            if (glyphs.isEmpty()) null
                            else TextBlob.makeFromPosH(glyphs, positions, 0.0f, font),
                            cursor,
                        )
                        .also { runs[value] = it }
                }
        }

        fun measure(value: String, scale: Float): Float {
            return textRun(value, scale).width
        }

        fun draw(
            canvas: org.jetbrains.skia.Canvas,
            value: String,
            x: Float,
            y: Float,
            scale: Float,
            color: Int,
            shadow: Boolean,
        ) {
            drawRun(canvas, value, x, y, scale, color, shadow, null)
        }

        fun drawGradient(
            canvas: org.jetbrains.skia.Canvas,
            value: String,
            x: Float,
            y: Float,
            scale: Float,
            color: Int,
            shadow: Boolean,
            shader: Shader,
        ) {
            drawRun(canvas, value, x, y, scale, color, shadow, shader)
        }

        private fun drawRun(
            canvas: org.jetbrains.skia.Canvas,
            value: String,
            x: Float,
            y: Float,
            scale: Float,
            color: Int,
            shadow: Boolean,
            shader: Shader?,
        ) {
            val run = textRun(value, scale)
            val blob = run.blob ?: return
            val baseline = y + 8.0f * scale
            val margin = max(2.0f, fontSize * 2.0f)
            drawnBounds.include(
                x - margin,
                baseline - margin,
                x + run.width + margin,
                baseline + margin,
            )
            if (shadow) {
                paint.color = HudText.withAlpha(0xFF000000.toInt(), min(150, color ushr 24))
                canvas.drawTextBlob(blob, x + scale, baseline + scale, paint)
            }
            paint.color = if (shader == null) color else 0xFFFFFFFF.toInt()
            paint.shader = shader
            try {
                canvas.drawTextBlob(blob, x, baseline, paint)
            } finally {
                paint.shader = null
            }
        }

        fun close() {
            runsBySize.values.forEach { entries -> entries.values.forEach { it.blob?.close() } }
            runsBySize.clear()
            paint.close()
            font.close()
            typeface.close()
            data.close()
        }

        private data class PixelRun(val blob: TextBlob?, val width: Float)

        companion object {
            fun load(loader: ClassLoader): MinecraftPixelFont {
                val bytes =
                    EmbeddedResources.readRequiredBytes(
                        loader,
                        MINECRAFT_FONT_RESOURCE,
                        "Missing embedded Minecraft HUD font",
                    )
                val data = Data.makeFromBytes(bytes)
                val typeface =
                    requireNotNull(FontMgr.default.makeFromData(data)) {
                        "Invalid embedded Minecraft HUD font"
                    }
                val font =
                    Font(typeface, 8.0f).apply {
                        edging = FontEdging.ALIAS
                        hinting = FontHinting.NONE
                        isSubpixel = true
                        isLinearMetrics = true
                    }
                return MinecraftPixelFont(
                    data,
                    typeface,
                    font,
                    Paint().apply { isAntiAlias = false },
                )
            }
        }
    }
}
