package com.blanoir.tempos.client.ui.hud

import com.blanoir.tempos.client.module.framework.ModuleModel
import com.blanoir.tempos.client.module.framework.ModuleRegistry
import com.blanoir.tempos.client.module.framework.SettingFactory
import com.blanoir.tempos.client.api.hud.Animation
import com.blanoir.tempos.client.api.hud.Bounds
import com.blanoir.tempos.client.ui.paint.UiBackend
import com.blanoir.tempos.client.ui.paint.UiFont
import com.blanoir.tempos.client.ui.paint.UiPainter
import com.blanoir.tempos.client.ui.theme.Theme
import com.blanoir.tempos.client.utils.render.ArgbColors
import net.minecraft.client.Minecraft
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Title and enabled-module list, recorded together in physical framebuffer coordinates. */
internal object NativeTextGui {
    private const val TAG_SEPARATOR = " "
    private val hiddenTagPattern = Regex("(?i)on|enable|enabled|active")
    private val choiceTagPattern = Regex("[A-Za-z][A-Za-z0-9_-]*")
    private val percentagePattern = Regex("[-+]?\\d+(?:\\.\\d+)?(?=%)")
    private val digitPattern = Regex("\\d")
    private val normalizedTags = LinkedHashMap<String, String>()


    private val moduleEntries = LinkedHashMap<String, ModuleEntry>()
    private var activeSnapshot: List<ModuleModel.Module>? = null
    private val sortedRows = ArrayList<Row>()
    private val rowComparator = compareByDescending<Row> { it.width }.thenBy { it.name }
    private var rowsDirty = true
    private var rowScale = Float.NaN
    private var rowPixelMode = false

    private var font: UiFont? = null
    private val gradient = IntArray(5)

    private fun clearBounds() = TemposHud.updateExternalBounds(Bounds.EMPTY, HudConfig.SCALE.get())
    fun reset() {
        moduleEntries.clear()
        activeSnapshot = null
        sortedRows.clear()
        rowsDirty = true
        clearBounds()
    }
    fun close() {
        reset()
        font?.close()
        font = null
        normalizedTags.clear()
    }

    fun draw(
        canvas: UiPainter,
        backend: UiBackend,
        client: Minecraft,
        editing: Boolean,
        seconds: Double,
        animationSeconds: Double,
        nameTheme: Theme?,
    ) {
        val guiScale = client.window.guiScale.coerceAtLeast(1).toFloat()
        val hudScale = HudConfig.SCALE.get().toFloat()
        val physicalScale = guiScale * hudScale
        val rowHeight = 11f * physicalScale
        val titleGap = 3.0f * physicalScale
        val padding = 2.0f * physicalScale
        val barGap = 2.0f * physicalScale
        val barWidth = max(1.0f, 1.25f * physicalScale)
        val pixelMode = HudConfig.FONT_MODE.get() == HudConfig.HudFontMode.MINECRAFT

        updateModuleEntries(if (editing || HudConfig.VISIBLE.get()) ModuleRegistry.enabledModules() else emptyList(), animationSeconds)
        if (!editing && moduleEntries.isEmpty()) {
            clearBounds()
            return
        }
        if (font == null || rowScale != physicalScale || rowPixelMode != pixelMode) {
            font?.close()
            font = UiFont.hudAtEm(backend, 8f * physicalScale, pixelMode)
        }
        val resources = requireNotNull(font)
        val suffixFont = if (pixelMode) resources else resources.atSize(resources.size() * 14f / 16f)
        val suffixBaselineOffset = resources.hudBaseline(rowHeight) - suffixFont.hudBaseline(rowHeight)
        val header =
            if ((editing || HudConfig.VISIBLE.get()) && HudConfig.SHOW_TITLE.get())
                HudConfig.header(client)
            else ""
        if (rowScale != physicalScale || rowPixelMode != pixelMode) {
            rowScale = physicalScale
            rowPixelMode = pixelMode
            rowsDirty = true
            moduleEntries.values.forEach {
                it.row = null
                it.reservedTagWidth = Float.NaN
            }
        }
        if (rowsDirty) {
            sortedRows.clear()
            moduleEntries.values.forEach { entry ->
                val row =
                    entry.row
                        ?: run {
                            val name = entry.module.name()
                            val nameWidth = resources.width(name)
                            val tag = entry.tag
                            val separatorWidth =
                                if (tag.isBlank()) 0.0f
                                else resources.width(TAG_SEPARATOR)
                            val actualTagWidth =
                                if (tag.isBlank()) 0.0f
                                else suffixFont.width(tag)
                            val stableTagWidth =
                                if (tag.isBlank()) 0.0f
                                else if (entry.module.hudTag().widthSamples().isNotEmpty()) {
                                    if (entry.reservedTagWidth.isNaN()) {
                                        entry.reservedTagWidth =
                                            HudTagWidth.reserve(
                                                    entry.module.hudTag().widthSamples().map {
                                                        normalizeTag(it)
                                                    }
                                                ) {
                                                    suffixFont.width(it)
                                                        .toDouble()
                                                }
                                                .toFloat()
                                    }
                                    max(actualTagWidth, entry.reservedTagWidth)
                                } else
                                    max(
                                        actualTagWidth,
                                        suffixFont.width(stableTagTemplate(tag)),
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

        for (index in rows.indices) {
            val row = rows[index]
            val target = index.toDouble()
            if (row.entry.positionRows != target) {
                row.entry.positionRows =
                    if (row.entry.positionRows.isNaN()) target
                    else Animation.approach(row.entry.positionRows, target, animationSeconds, 21.0)
            }
        }

        val headerWidth =
            if (header.isBlank()) 0.0f else resources.width(header)
        val preview = "HUD Preview"
        val previewWidth =
            if (rows.isEmpty())
                resources.width(preview) + 7.0f * physicalScale
            else 0.0f
        val maximumWidth =
            max(1.0f, max(headerWidth, max(previewWidth, rows.firstOrNull()?.width ?: 0.0f)))

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

        val boundsX = (left / guiScale).toDouble()
        val boundsY = (startY / guiScale).toDouble()
        val boundsW = (maximumWidth / guiScale).toDouble()
        val boundsH = (totalHeight / guiScale).toDouble()
        val previous = TemposHud.currentBounds()
        val bounds = if (previous.x() == boundsX && previous.y() == boundsY &&
            previous.width() == boundsW && previous.height() == boundsH) previous
        else Bounds(boundsX, boundsY, boundsW, boundsH)
        TemposHud.updateExternalBounds(bounds, hudScale.toDouble())

        val overallAlpha = (255.0 * HudConfig.ALPHA.get() / 100.0).toInt().coerceIn(0, 255)
        val shadow = HudConfig.TEXT_SHADOW.get()
        val hasPanel = HudConfig.PANEL_OPACITY.get() > 0
        val nameShadow = if (!shadow) 0 else if (hasPanel) 80 else 170
        val suffixShadow = if (shadow && !hasPanel) 170 else 0
        if (header.isNotBlank()) {
            resources.hudText(
                canvas,
                header,
                right - headerWidth,
                y,
                rowHeight,
                ArgbColors.withAlpha(HudText.titleColor(), overallAlpha),
                if (shadow) 170 else 0, null,
            )
            y += titleHeight
        }

        if (rows.isEmpty() && editing) {
            val cardLeft = right - previewWidth
            drawPanel(canvas, cardLeft, y, previewWidth, rowHeight, overallAlpha)
            resources.hudText(
                canvas,
                preview,
                cardLeft + 3.0f * physicalScale,
                y,
                rowHeight,
                ArgbColors.withAlpha(HudText.hudNameColor(0, seconds), overallAlpha),
                nameShadow, null,
            )
            return
        }

        for (index in rows.indices) {
            val row = rows[index]
            val eased = 1.0 - Math.pow(1.0 - row.entry.progress.coerceIn(0.0, 1.0), 3.0)
            val visibleWidth = max(1.0f, row.width * eased.toFloat())
            val cardLeft = right - visibleWidth
            val rowY = y + row.entry.positionRows.toFloat() * rowHeight
            val animatedAlpha = min(overallAlpha, (overallAlpha * eased).toInt())
            drawPanel(canvas, cardLeft, rowY, visibleWidth, rowHeight, animatedAlpha, eased)
            val textX = cardLeft + padding
            val barColor = if (nameTheme != null) {
                val color=nameTheme.clock().unpackArgb(nameTheme.accentTint(rowY/guiScale,animatedAlpha,1f))
                resources.hudText(canvas,row.name,textX,rowY,rowHeight,color,nameShadow,null)
                ArgbColors.withAlpha(HudText.hudNameColor(index, row.nameWidth.toDouble(), seconds), animatedAlpha)
            } else drawModuleName(
                canvas,
                resources,
                row.name,
                textX,
                rowY,
                rowHeight,
                index,
                seconds,
                animatedAlpha,
                row.nameWidth,
                nameShadow,
            )
            if (row.tag.isNotBlank()) {
                val parameterColor = ArgbColors.withAlpha(HudText.parameterColor(), animatedAlpha)
                val tagX =
                    textX + row.nameWidth + row.separatorWidth + row.stableTagWidth -
                        row.actualTagWidth
                suffixFont.hudText(
                    canvas,
                    row.tag,
                    tagX,
                    rowY + suffixBaselineOffset,
                    rowHeight,
                    parameterColor,
                    suffixShadow, null,
                )
            }
            drawModuleBar(
                canvas,
                right - barWidth,
                rowY,
                barWidth,
                rowHeight,
                barColor,
                physicalScale,
            )
        }
    }

    private fun updateModuleEntries(
        active: List<ModuleModel.Module>,
        animationSeconds: Double,
    ) {
        // HudPolicy reuses the list while membership stays unchanged. Dynamic tag text is still read every frame.
        if (active !== activeSnapshot) {
            activeSnapshot = active
            moduleEntries.values.forEach { it.present = false }
        }
        for (i in active.indices) {
            val module = active[i]
            val entry =
                moduleEntries.getOrPut(module.id()) {
                    rowsDirty = true
                    ModuleEntry(module)
                }
            val tag = safeTag(module)
            if (entry.module !== module && entry.module.hudTag().widthSamples() != module.hudTag().widthSamples()) {
                entry.reservedTagWidth = Float.NaN
                entry.row = null
                rowsDirty = true
            }
            if (entry.tag != tag || entry.module.name() != module.name()) {
                entry.tag = tag
                entry.row = null
                rowsDirty = true
            }
            entry.module = module
            entry.present = true
            if (entry.progress < 1.0) {
                entry.progress = Animation.approach(entry.progress, 1.0, animationSeconds, 16.0)
            }
        }
        if (moduleEntries.size == active.size) return
        val iterator = moduleEntries.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.present) continue
            entry.progress = Animation.approach(entry.progress, 0.0, animationSeconds, 16.0)
            if (entry.progress < 0.015) {
                iterator.remove()
                rowsDirty = true
            }
        }
    }

    private fun drawModuleBar(
        canvas: UiPainter,
        x: Float,
        y: Float,
        width: Float,
        rowHeight: Float,
        color: Int,
        physicalScale: Float,
    ) {
        val inset = min(rowHeight * 0.22f, max(1.0f, physicalScale * 0.75f))
        val height = max(1.0f, rowHeight - inset * 2.0f)
        canvas.fill(x,y+inset,width,height,0f,color)
    }

    private fun drawPanel(
        canvas: UiPainter,
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
        canvas.fill(x,y,width,height,0f,ArgbColors.withAlpha(HudText.hudBackgroundColor(), panelAlpha))
    }

    private fun drawModuleName(
        canvas: UiPainter,
        resources: UiFont,
        value: String,
        x: Float,
        y: Float,
        rowHeight: Float,
        row: Int,
        seconds: Double,
        alpha: Int,
        width: Float,
        shadowAlpha: Int,
    ): Int {
        val horizontal =
            HudConfig.NAME_COLOR_MODE.get() != HudConfig.NameColorMode.FIXED &&
                (HudConfig.NAME_COLOR_MODE.get() == HudConfig.NameColorMode.RAINBOW ||
                    HudConfig.GRADIENT_DIRECTION.get() == HudConfig.GradientDirection.HORIZONTAL)

        if (horizontal && width > 0.5f) {
            for (i in gradient.indices) {
                val sample = width * i / gradient.lastIndex
                gradient[i] = ArgbColors.withAlpha(HudText.hudNameColor(row,sample.toDouble(),seconds),alpha)
            }
            resources.hudText(canvas,value,x,y,rowHeight,gradient.first(),shadowAlpha,gradient)
            return gradient.last()
        }
        val color = ArgbColors.withAlpha(HudText.hudNameColor(row, seconds), alpha)
        resources.hudText(canvas,value,x,y,rowHeight,color,shadowAlpha,null)
        return if (horizontal) ArgbColors.withAlpha(HudText.hudNameColor(row, width.toDouble(), seconds), alpha) else color
    }

    private fun safeTag(module: ModuleModel.Module): String {
        return try {
            normalizeTag(module.hudTag().text().get() ?: return "")
        } catch (_: RuntimeException) {
            ""
        }
    }

    private fun normalizeTag(raw: String): String {
        normalizedTags[raw]?.let {
            return it
        }
        val trimmed = raw.trim()
        val normalized =
            if (trimmed.matches(hiddenTagPattern)) ""
            else if (trimmed.matches(choiceTagPattern)) SettingFactory.displayChoice(trimmed)
            else trimmed
        if (normalizedTags.size >= 256) normalizedTags.remove(normalizedTags.keys.first())
        return normalized.also { normalizedTags[raw] = it }
    }

    private fun stableTagTemplate(tag: String): String =
        tag.replace(percentagePattern, "100").replace(digitPattern, "8")

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
        var module: ModuleModel.Module,
        var tag: String = safeTag(module),
        var progress: Double = 0.0,
        var positionRows: Double = Double.NaN,
        var present: Boolean = true,
        var row: Row? = null,
        var reservedTagWidth: Float = Float.NaN,
    )

 }
