package com.blanoir.moons.client.ui.clickgui.legacy;

import com.blanoir.moons.client.config.ClientBranding;
import com.blanoir.moons.client.utils.time.FrameClock;
import com.blanoir.moons.client.ui.animation.UiMotion;
import com.blanoir.moons.client.ui.component.ControlRenderer;
import com.blanoir.moons.client.ui.render.SmoothGui;
import com.blanoir.moons.client.ui.render.MoonsFonts;
import com.blanoir.moons.client.ui.theme.UiPalette;
import com.blanoir.moons.client.ui.theme.UiTheme;
import com.blanoir.moons.client.ui.theme.UiThemes;
import com.blanoir.moons.client.module.framework.ModuleKeybinds;
import com.blanoir.moons.client.module.framework.ModuleCategories;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.module.framework.ModuleRegistry.Module;
import com.blanoir.moons.client.module.framework.ModuleRegistry.Setting;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dormant native fallback for hosts where the Compose/Skia screen cannot be used.
 *
 * <p>The active entry point remains {@code ui.clickgui.ModuleGui}; this class is
 * deliberately kept out of that normal route so it cannot silently diverge from
 * the panel GUI while still remaining available for a future fallback adapter.
 */
public final class MoonsNativeScreen extends Screen {
    private static final UiTheme THEME = UiThemes.PEARL_GOLD;
    private static final UiPalette PALETTE = THEME.palette();
    private static final float UI_SCALE = THEME.metrics().scale();
    private static final int CATEGORY_HEIGHT = 24;
    private static final int CATEGORY_STRIDE = 27;
    private static final int MODULE_HEIGHT = 25;
    private static final int MODULE_STRIDE = 27;
    private static final int ROW_RADIUS = THEME.metrics().controlRadius();
    private static final int MODULE_ROW_INSET = 6;
    private static final int MODULE_CONTENT_INSET = 7;
    private static final int SETTINGS_ROW_INSET = 8;
    private static final int SETTINGS_CONTENT_INSET = THEME.metrics().contentPadding();
    private static final int KEYBIND_ROW_HEIGHT = 24;
    private static final int TOGGLE_WIDTH = THEME.metrics().toggleWidth();
    private static final int TOGGLE_HEIGHT = THEME.metrics().toggleHeight();
    private static final List<String> CATEGORIES =
            ModuleCategories.ordered();

    private static final int WINDOW = PALETTE.window();
    private static final int HEADER = PALETTE.header();
    private static final int COLUMN = PALETTE.panel();
    private static final int ROW = PALETTE.row();
    private static final int ROW_HOVER = PALETTE.rowHover();
    private static final int SELECTED = PALETTE.selected();
    private static final int DIVIDER = PALETTE.border();
    private static final int ACCENT = PALETTE.primary();
    private static final int ACCENT_SECONDARY = PALETTE.primaryLight();
    private static final int ACCENT_DARK = PALETTE.primaryDark();
    private static final int TEXT = PALETTE.textPrimary();
    private static final int MUTED = PALETTE.textSecondary();
    private static final int DIM = PALETTE.textDisabled();

    private String category = "Combat";
    private String search = "";
    private Module selected;
    private Setting editingSetting;
    private String editBuffer = "";
    private boolean bindingKey;
    private boolean searchFocused;
    private Setting draggingSetting;
    private int draggingRangeIndex = -1;
    private String draggingModuleId;
    private int moduleScroll;
    private int settingsScroll;
    private int categoryScroll;
    private final UiMotion.Controls controls = new UiMotion.Controls(THEME);
    private final ControlRenderer controlRenderer = new ControlRenderer(THEME);
    private final FrameClock animationClock = new FrameClock();
    private double animationFrameSeconds = 1.0D / 60.0D;

    public MoonsNativeScreen() {
        super(Component.literal(ClientBranding.name() + " ClickGUI"));
    }

    @Override
    public void added() {
        super.added();
        ensureSelection();
    }

    @Override
    public void removed() {
        commitEditor();
        bindingKey = false;
        stopDragging();
        controls.clear();
        super.removed();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // Blur only the exposed game world. Native controls are extracted on
        // the next stratum and therefore stay crisp.
        graphics.blurBeforeThisStratum();
        graphics.fill(0, 0, width, height, 0x2A18150F);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        animationFrameSeconds = animationClock.nextDeltaSeconds();
        graphics.pose().pushMatrix();
        graphics.pose().scale(UI_SCALE, UI_SCALE);
        mouseX = toUi(mouseX);
        mouseY = toUi(mouseY);
        Layout l = layout();
        ensureSelection();
        clampScroll(l);

        SmoothGui.shadow(graphics, l.x, l.y, l.right(), l.bottom(), 10,
                UiMotion.withAlpha(PALETTE.field(), 128), 8);
        // Keep the custom illustration inside the ClickGUI window. A solid
        // pearl base preserves the rounded outer corners while the cropped
        // image fills the visible body of the panel.
        SmoothGui.roundedRect(graphics, l.x, l.y, l.right(), l.bottom(), 10, 0xFFF7F4ED);
        SmoothGui.roundedRect(graphics, l.x, l.y, l.right(), l.bottom(), 10, WINDOW);
        SmoothGui.roundedRect(graphics, l.x + 1, l.y + 1, l.right() - 1, l.contentTop + 7, 9, HEADER);
        graphics.fill(l.x + 1, l.contentTop, l.right() - 1, l.contentTop + 7, HEADER);
        // Columns reach the lower edge. The side columns are deliberately wider
        // than their scissors: only the two outer corners stay rounded while
        // internal column joins remain perfectly square and continuous.
        graphics.enableScissor(l.x + 1, l.contentTop, l.categoryRight, l.bottom() - 1);
        SmoothGui.roundedRect(graphics, l.x + 1, l.contentTop - 8,
                l.categoryRight + 8, l.bottom() - 1, 9, COLUMN);
        graphics.disableScissor();
        // All three columns use the same frosted surface. The old middle-column
        // alpha was much lower, which made the wallpaper appear to cut through
        // that column and through the gaps between module rows.
        graphics.fill(l.categoryRight, l.contentTop, l.moduleRight, l.bottom() - 1, COLUMN);
        graphics.enableScissor(l.moduleRight, l.contentTop, l.right() - 1, l.bottom() - 1);
        SmoothGui.roundedRect(graphics, l.moduleRight - 8, l.contentTop - 8,
                l.right() - 1, l.bottom() - 1, 9, COLUMN);
        graphics.disableScissor();
        drawSoftVerticalDivider(graphics, l.categoryRight, l.contentTop + 5, l.bottom() - 7);
        drawSoftVerticalDivider(graphics, l.moduleRight, l.contentTop + 5, l.bottom() - 7);
        drawAccentLine(graphics, l.x + 9, l.right() - 9, l.contentTop);
        drawSoftHorizontalDivider(graphics, l.x + 7, l.right() - 7, l.listTop - 7);

        SmoothGui.shadow(graphics, l.x + 12, l.y + 10, l.x + 19, l.y + 17, 3,
                UiMotion.withAlpha(ACCENT, 86), 2);
        SmoothGui.roundedRect(graphics, l.x + 12, l.y + 10, l.x + 19, l.y + 17, 3, ACCENT);
        int headerTextY = centeredTextY(l.y, l.contentTop - l.y);
        drawText(graphics, ClientBranding.name(), l.x + 24, headerTextY, TEXT, false);
        drawSearch(graphics, l, mouseX, mouseY);
        int closeX = l.right() - 24;
        boolean closeHover = inside(mouseX, mouseY, closeX, l.y + 7, 18, 18);
        UiMotion.Visual closeVisual = controls.update("close", closeHover, false, animationFrameSeconds);
        SmoothGui.roundedRect(graphics, closeX, l.y + 7, closeX + 18, l.y + 25, 5,
                UiMotion.mixColor(ROW, ROW_HOVER, closeVisual.hover()));
        drawCenteredText(graphics, "×", closeX + 9, centeredTextY(l.y + 7, 18), closeHover ? TEXT : MUTED);

        drawCategories(graphics, l, mouseX, mouseY);
        drawModules(graphics, l, mouseX, mouseY);
        drawSettings(graphics, l, mouseX, mouseY);
        graphics.pose().popMatrix();
    }

    private void drawSearch(GuiGraphicsExtractor graphics, Layout l, int mouseX, int mouseY) {
        int w = searchBoxWidth(l);
        int x = l.right() - 36 - w;
        int y = l.y + 7;
        int color = searchFocused ? ACCENT_DARK : DIVIDER;
        if (searchFocused) {
            SmoothGui.shadow(graphics, x, y, x + w, y + 18, 5,
                    UiMotion.withAlpha(ACCENT, 54), 2);
        }
        SmoothGui.roundedOutline(graphics, x, y, x + w, y + 18, 5, 1,
                color, PALETTE.field());
        String shown = search.isEmpty() && !searchFocused ? "Search modules / settings" : search;
        drawText(graphics, clip(shown, w - 16), x + 7, centeredTextY(y, 18),
                search.isEmpty() ? DIM : TEXT, false);
        if (searchFocused && (System.currentTimeMillis() / 500L) % 2L == 0L) {
            int cursorX = Math.min(x + w - 7, x + 7 + textWidth(clip(search, w - 16)));
            graphics.fill(cursorX, y + 4, cursorX + 1, y + 14, ACCENT);
        }
    }

    private void drawCategories(GuiGraphicsExtractor graphics, Layout l, int mouseX, int mouseY) {
        int headingHeight = l.listTop - 7 - l.contentTop;
        drawText(graphics, "MENU", l.x + 11, centeredTextY(l.contentTop, headingHeight), MUTED, false);
        int top = l.listTop;
        int bottom = l.bottom() - 8;
        int y = top - categoryScroll;
        graphics.enableScissor(l.x, top, l.categoryRight, bottom);
        for (String current : CATEGORIES) {
            boolean active = search.isEmpty() && current.equals(category);
            boolean hover = inside(mouseX, mouseY, l.x + 7, y, l.categoryWidth - 14, CATEGORY_HEIGHT);
            UiMotion.Visual visual = controls.update(
                    "category:" + current, hover, active, animationFrameSeconds);
            if (visual.selected() > 0.002D || visual.hover() > 0.002D) {
                SmoothGui.roundedRect(graphics, l.x + 7, y, l.categoryRight - 7, y + CATEGORY_HEIGHT, ROW_RADIUS,
                        rowColor(visual));
            }
            if (visual.selected() > 0.01D) {
                graphics.fill(l.x + 7, y, l.x + 7 + Math.max(1, (int) Math.round(2 * visual.selected())),
                        y + CATEGORY_HEIGHT, ACCENT);
            }
            drawText(graphics, clip(current, l.categoryWidth - 17), l.x + 14,
                    centeredTextY(y, CATEGORY_HEIGHT), active ? TEXT : MUTED, false);
            y += CATEGORY_STRIDE;
        }
        graphics.disableScissor();
        drawScrollbar(graphics, l.categoryRight - 3, top, bottom,
                CATEGORIES.size() * CATEGORY_STRIDE, categoryScroll);
    }

    private void drawModules(GuiGraphicsExtractor graphics, Layout l, int mouseX, int mouseY) {
        List<Module> modules = filteredModules();
        String heading = search.isEmpty() ? category.toUpperCase(Locale.ROOT) : "SEARCH RESULTS";
        int headingY = centeredTextY(l.contentTop, l.listTop - 7 - l.contentTop);
        if (modules.isEmpty()) {
            drawText(graphics, search.isEmpty() ? "No modules" : "No matches",
                    l.categoryRight + 13, l.listTop + 10, DIM, false);
        }
        String count = Integer.toString(modules.size());
        int countWidth = textWidth(count);
        int groupWidth = textWidth(heading) + 8 + countWidth + 8;
        int groupX = (l.categoryRight + l.moduleRight - groupWidth) / 2;
        drawText(graphics, heading, groupX, headingY, MUTED, false);
        int badgeX = groupX + textWidth(heading) + 8;
        SmoothGui.roundedRect(graphics, badgeX - 4, headingY - 2,
                badgeX + countWidth + 4, headingY + font.lineHeight + 2, 4,
                UiMotion.withAlpha(PALETTE.selected(), 154));
        drawText(graphics, count, badgeX, headingY, ACCENT, false);

        int top = l.listTop;
        int bottom = l.bottom() - 8;
        graphics.enableScissor(l.categoryRight + 1, top, l.moduleRight, bottom);
        for (int index = 0; index < modules.size(); index++) {
            Module module = modules.get(index);
            int y = top + index * MODULE_STRIDE - moduleScroll;
            if (y + MODULE_HEIGHT < top || y > bottom) continue;
            int rowLeft = moduleRowLeft(l);
            int rowRight = moduleRowRight(l);
            int contentLeft = rowLeft + MODULE_CONTENT_INSET;
            int toggleX = moduleToggleX(l);
            int toggleY = y + (MODULE_HEIGHT - TOGGLE_HEIGHT) / 2;
            boolean hover = inside(mouseX, mouseY, rowLeft, y, rowRight - rowLeft, MODULE_HEIGHT);
            boolean active = selected != null && module.id().equals(selected.id());
            UiMotion.Visual rowVisual = controls.update(
                    "module-row:" + module.id(), hover, active, animationFrameSeconds);
            SmoothGui.roundedRect(graphics, rowLeft, y, rowRight, y + MODULE_HEIGHT, ROW_RADIUS,
                    rowColor(rowVisual));
            if (rowVisual.selected() > 0.01D) {
                graphics.fill(rowLeft, y,
                        rowLeft + Math.max(1, (int) Math.round(2 * rowVisual.selected())),
                        y + MODULE_HEIGHT, ACCENT);
            }
            String tag = safeTag(module);
            int moduleTextWidth = Math.max(24, toggleX - 7 - contentLeft);
            int nameY = tag.isBlank() ? centeredTextY(y, MODULE_HEIGHT) : y + 3;
            drawText(graphics, clip(module.name(), moduleTextWidth), contentLeft, nameY,
                    enabled(module) ? TEXT : MUTED, false);
            if (!tag.isBlank()) {
                drawText(graphics, clip(tag, moduleTextWidth), contentLeft, y + 14, MUTED, false);
            }
            drawSwitch(graphics, toggleX, toggleY,
                    enabled(module), "module:" + module.id(),
                    inside(mouseX, mouseY, toggleX, toggleY, TOGGLE_WIDTH, TOGGLE_HEIGHT));
        }
        graphics.disableScissor();
        drawScrollbar(graphics, l.moduleRight - 3, top, bottom,
                modules.size() * MODULE_STRIDE, moduleScroll);
    }

    private void drawSettings(GuiGraphicsExtractor graphics, Layout l, int mouseX, int mouseY) {
        int x = settingsRowLeft(l);
        int right = settingsRowRight(l);
        if (selected == null) {
            drawCenteredText(graphics, "Select a module", (l.moduleRight + l.right()) / 2,
                    l.contentTop + 30, MUTED);
            return;
        }

        String categoryText = selected.category().toUpperCase(Locale.ROOT);
        int headingCenter = (l.moduleRight + l.right()) / 2;
        drawCenteredText(graphics, clip(selected.name(), settingsContentRight(l) - settingsContentLeft(l)), headingCenter,
                l.contentTop + 5, TEXT);
        String selectedStatus = enabled(selected) ? "ENABLED" : "DISABLED";
        int categoryWidth = textWidth(categoryText);
        int statusWidth = textWidth(selectedStatus);
        int badgesWidth = categoryWidth + statusWidth + 22;
        int badgesX = headingCenter - badgesWidth / 2;
        int badgeTop = l.contentTop + 20;
        SmoothGui.roundedRect(graphics, badgesX - 4, badgeTop,
                badgesX + categoryWidth + 5, badgeTop + 14, 4,
                UiMotion.withAlpha(PALETTE.selected(), 138));
        drawText(graphics, categoryText, badgesX, centeredTextY(badgeTop, 14), DIM, false);
        int statusX = badgesX + categoryWidth + 14;
        SmoothGui.roundedRect(graphics, statusX - 4, badgeTop,
                statusX + statusWidth + 5, badgeTop + 14, 4,
                UiMotion.withAlpha(enabled(selected) ? PALETTE.primaryDark() : PALETTE.controlTrack(), 112));
        drawText(graphics, selectedStatus, statusX, centeredTextY(badgeTop, 14),
                enabled(selected) ? ACCENT : MUTED, false);

        int top = l.listTop;
        int bottom = l.bottom() - 8;
        int contentTop = settingsTop(l);
        graphics.enableScissor(l.moduleRight + 1, top, l.right(), bottom);
        int y = contentTop - settingsScroll;
        drawKeybindRow(graphics, x, y, right, mouseX, mouseY);
        y += KEYBIND_ROW_HEIGHT + 4;
        for (Setting setting : visibleSettings(selected)) {
            int rowHeight = rowHeight(setting);
            if (y + rowHeight >= top && y <= bottom) {
                drawSetting(graphics, setting, x, y, right, rowHeight, mouseX, mouseY);
            }
            y += rowHeight + 4;
        }
        graphics.disableScissor();
        drawScrollbar(graphics, l.right() - 3, contentTop, bottom, settingsHeight(), settingsScroll);
    }

    private void drawKeybindRow(GuiGraphicsExtractor graphics, int x, int y, int right,
                                int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, x, y, right - x, KEYBIND_ROW_HEIGHT);
        UiMotion.Visual visual = controls.update("keybind:" + selected.id(), hover, bindingKey,
                animationFrameSeconds);
        SmoothGui.roundedRect(graphics, x, y, right, y + KEYBIND_ROW_HEIGHT, ROW_RADIUS, rowColor(visual));
        int contentLeft = x + SETTINGS_CONTENT_INSET;
        int contentRight = right - SETTINGS_CONTENT_INSET;
        InputConstants.Key bound = ModuleKeybinds.getBoundKey(selected.id());
        String shown;
        if (bindingKey) {
            shown = "Press a key or mouse button";
        } else if (bound == InputConstants.UNKNOWN) {
            shown = "Keybind: None";
        } else {
            shown = "Keybind: " + bound.getDisplayName().getString();
        }
        drawText(graphics, clip(shown, contentRight - contentLeft - 24), contentLeft,
                centeredTextY(y, KEYBIND_ROW_HEIGHT), bindingKey ? ACCENT : TEXT, false);
        if (bound != InputConstants.UNKNOWN && !bindingKey) {
            int clearX = contentRight - 18;
            int clearY = y + (KEYBIND_ROW_HEIGHT - 18) / 2;
            boolean clearHover = inside(mouseX, mouseY, clearX, clearY, 18, 18);
            SmoothGui.roundedRect(graphics, clearX, clearY, clearX + 18, clearY + 18, 4,
                    clearHover ? ROW_HOVER : ROW);
            drawCenteredText(graphics, "×", clearX + 9, centeredTextY(clearY, 18),
                    clearHover ? TEXT : MUTED);
        }
    }

    private void drawSetting(GuiGraphicsExtractor graphics, Setting setting, int x, int y, int right,
                             int rowHeight, int mouseX, int mouseY) {
        int contentLeft = x + SETTINGS_CONTENT_INSET;
        int contentRight = right - SETTINGS_CONTENT_INSET;
        int toggleX = contentRight - TOGGLE_WIDTH;
        int toggleY = y + Math.max(0, (rowHeight - TOGGLE_HEIGHT) / 2);
        boolean hover = inside(mouseX, mouseY, x, y, right - x, rowHeight);
        UiMotion.Visual rowVisual = controls.update(
                "setting-row:" + selected.id() + ":" + setting.id(), hover, false, animationFrameSeconds);
        SmoothGui.roundedRect(graphics, x, y, right, y + rowHeight, ROW_RADIUS, rowColor(rowVisual));
        boolean booleanSetting = "boolean".equals(setting.type());
        int labelBandHeight = booleanSetting ? rowHeight : 20;
        int labelY = centeredTextY(y, labelBandHeight);
        String value = displayValue(setting);
        String sliderShown = null;
        if ("number".equals(setting.type()) || "integer".equals(setting.type())
                || "range".equals(setting.type())) {
            sliderShown = clip(value, Math.max(36, contentRight - contentLeft));
        }
        int labelWidth = booleanSetting ? Math.max(42, toggleX - contentLeft - 8)
                : sliderShown != null ? Math.max(42, (contentRight - contentLeft) * 55 / 100)
                : contentRight - contentLeft;
        String shownLabel = clip(setting.name(), labelWidth);
        drawText(graphics, shownLabel, contentLeft, labelY, setting.isEnabled() ? TEXT : MUTED, false);

        switch (setting.type()) {
            case "boolean" -> drawSwitch(graphics, toggleX, toggleY, setting.value().get().getAsBoolean(),
                    "setting:" + selected.id() + ":" + setting.id(),
                    inside(mouseX, mouseY, toggleX, toggleY, TOGGLE_WIDTH, TOGGLE_HEIGHT));
            case "number", "integer" -> drawSlider(graphics, setting, contentLeft, contentRight, y + 24);
            case "range" -> drawRange(graphics, setting, contentLeft, contentRight, y + 24);
            case "choice" -> drawChoice(graphics, setting, contentLeft, y + 20,
                    contentRight, y + rowHeight - 5, mouseX, mouseY);
            case "text", "color" -> drawEditor(graphics, setting, contentLeft, y + 20,
                    contentRight, y + rowHeight - 5);
            default -> { }
        }
        if (sliderShown != null) {
            int valueX = contentLeft + textWidth(shownLabel) + 8;
            int available = Math.max(22, contentRight - valueX);
            String shownValue = clip(sliderShown, Math.min(available, 86));
            int valueWidth = textWidth(shownValue);
            int pillTop = labelY - 2;
            SmoothGui.roundedRect(graphics, valueX - 4, pillTop,
                    valueX + valueWidth + 4, pillTop + font.lineHeight + 4, 4,
                    UiMotion.withAlpha(PALETTE.selected(), 181));
            drawText(graphics, shownValue, valueX, labelY, ACCENT, false);
        }
    }

    private void drawSlider(GuiGraphicsExtractor graphics, Setting setting, int left, int right, int y) {
        double min = setting.min();
        double max = setting.max();
        double value = setting.value().get().getAsDouble();
        double ratio = max <= min ? 0 : (value - min) / (max - min);
        int handle = left + (int) Math.round(clamp(ratio, 0, 1) * (right - left));
        controlRenderer.slider(graphics, left, right, y, left, handle, false);
    }

    private void drawRange(GuiGraphicsExtractor graphics, Setting setting, int left, int right, int y) {
        JsonArray values = setting.value().get().getAsJsonArray();
        double min = setting.min();
        double max = setting.max();
        int low = sliderX(values.get(0).getAsDouble(), min, max, left, right);
        int high = sliderX(values.get(1).getAsDouble(), min, max, left, right);
        controlRenderer.slider(graphics, left, right, y, low, high, true);
    }

    private void drawChoice(GuiGraphicsExtractor graphics, Setting setting, int left, int top, int right,
                            int bottom, int mouseX, int mouseY) {
        List<String> options = setting.options();
        if (options.isEmpty()) {
            return;
        }

        String current = setting.value().get().getAsString();
        int gap = 3;
        int totalGap = gap * (options.size() - 1);
        int cellWidth = Math.max(1, (right - left - totalGap) / options.size());
        for (int index = 0; index < options.size(); index++) {
            int cellLeft = left + index * (cellWidth + gap);
            int cellRight = index == options.size() - 1 ? right : cellLeft + cellWidth;
            String option = options.get(index);
            boolean active = option.equalsIgnoreCase(current);
            boolean hover = inside(mouseX, mouseY, cellLeft, top, cellRight - cellLeft, bottom - top);
            UiMotion.Visual visual = controls.update(
                    "choice:" + selected.id() + ":" + setting.id() + ":" + option,
                    hover, active, animationFrameSeconds);
            int color = UiMotion.mixColor(PALETTE.field(), ROW_HOVER, visual.hover());
            color = UiMotion.mixColor(color, ACCENT_DARK, visual.selected());
            SmoothGui.roundedRect(graphics, cellLeft, top, cellRight, bottom, 4, color);
            drawCenteredText(graphics, clip(ModuleRegistry.displayChoice(option),
                            Math.max(1, cellRight - cellLeft - 6)),
                    (cellLeft + cellRight) / 2, centeredTextY(top, bottom - top),
                    active ? TEXT : MUTED);
        }
    }

    private void drawEditor(GuiGraphicsExtractor graphics, Setting setting, int left, int top, int right, int bottom) {
        boolean active = editingSetting != null && editingSetting.id().equals(setting.id());
        SmoothGui.roundedOutline(graphics, left, top, right, bottom, 4, 1,
                active ? ACCENT_DARK : DIVIDER, PALETTE.field());
        String shown = active ? editBuffer : setting.value().get().getAsString();
        int reserved = "color".equals(setting.type()) ? 26 : 12;
        drawText(graphics, clip(shown, right - left - reserved), left + 6,
                centeredTextY(top, bottom - top), active ? TEXT : MUTED, false);
        if ("color".equals(setting.type())) {
            int color = parsePreviewColor(shown);
            graphics.fill(right - 18, top + 4, right - 6, bottom - 4, color);
        }
    }

    private void drawSwitch(GuiGraphicsExtractor graphics, int x, int y,
                            boolean enabled, String animationKey, boolean hovered) {
        UiMotion.Visual visual = controls.update(animationKey, hovered, enabled, animationFrameSeconds);
        controlRenderer.toggle(graphics, x, y, TOGGLE_WIDTH, TOGGLE_HEIGHT, ROW_RADIUS, visual);
    }

    private int rowColor(UiMotion.Visual visual) {
        return controlRenderer.rowColor(visual);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        Layout l = layout();
        int mx = toUi(event.x());
        int my = toUi(event.y());
        if (inside(mx, my, l.right() - 24, l.y + 7, 18, 18)) {
            onClose();
            return true;
        }

        InputConstants.Key mouseKey = ModuleKeybinds.fromMouseButton(event.button());

        if (bindingKey && selected != null) {
            if (ModuleKeybinds.isGuiKey(mouseKey)
                    || event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                bindingKey = false;
            } else {
                commitEditor();
                ModuleKeybinds.bind(selected.id(), mouseKey);
                bindingKey = false;
            }
            return true;
        }

        if (ModuleKeybinds.isGuiKey(mouseKey)) {
            onClose();
            return true;
        }

        int searchBox = searchBoxWidth(l);
        if (inside(mx, my, l.right() - 36 - searchBox, l.y + 7, searchBox, 18)) {
            commitEditor();
            searchFocused = true;
            return true;
        }

        if (inside(mx, my, l.x, l.listTop, l.categoryWidth, l.bottom() - 8 - l.listTop)) {
            int categoryIndex = (my - l.listTop + categoryScroll) / CATEGORY_STRIDE;
            if (categoryIndex >= 0 && categoryIndex < CATEGORIES.size()) {
                String current = CATEGORIES.get(categoryIndex);
                commitEditor();
                searchFocused = false;
                search = "";
                category = current;
                moduleScroll = 0;
                settingsScroll = 0;
                selected = firstModule(filteredModules());
                return true;
            }
        }

        List<Module> modules = filteredModules();
        if (inside(mx, my, l.categoryRight + 1, l.listTop, l.moduleWidth - 1, l.bottom() - 8 - l.listTop)) {
            int index = (my - l.listTop + moduleScroll) / MODULE_STRIDE;
            if (index >= 0 && index < modules.size()) {
                Module module = modules.get(index);
                commitEditor();
                searchFocused = false;
                if (inside(mx, my, moduleToggleX(l), l.listTop + index * MODULE_STRIDE - moduleScroll
                        + (MODULE_HEIGHT - TOGGLE_HEIGHT) / 2, TOGGLE_WIDTH, TOGGLE_HEIGHT) || doubleClick) {
                    ModuleRegistry.setEnabled(module.id(), !enabled(module));
                }
                selected = module;
                settingsScroll = 0;
                return true;
            }
        }

        if (selected != null && inside(mx, my, l.moduleRight + 1, l.listTop,
                l.settingsWidth - 1, l.bottom() - 8 - l.listTop)) {
            int keybindX = settingsRowLeft(l);
            int keybindRight = settingsRowRight(l);
            int keybindY = settingsTop(l) - settingsScroll;
            if (inside(mx, my, keybindX, keybindY, keybindRight - keybindX, KEYBIND_ROW_HEIGHT)) {
                commitEditor();
                searchFocused = false;
                int clearX = keybindRight - SETTINGS_CONTENT_INSET - 18;
                boolean clearHover = inside(mx, my, clearX, keybindY + (KEYBIND_ROW_HEIGHT - 18) / 2, 18, 18);
                if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT
                        || (moduleIsBound() && clearHover)) {
                    ModuleKeybinds.unbind(selected.id());
                    bindingKey = false;
                } else {
                    bindingKey = true;
                }
                return true;
            }
            SettingHit hit = settingAt(my, l);
            if (hit != null && hit.setting.isEnabled()) {
                searchFocused = false;
                interactWithSetting(hit.setting, event.button(), mx, my, hit.top, l);
                return true;
            }
        }

        commitEditor();
        searchFocused = false;
        return super.mouseClicked(event, doubleClick);
    }

    private void interactWithSetting(
            Setting setting,
            int button,
            int mouseX,
            int mouseY,
            int rowTop,
            Layout l
    ) {
        switch (setting.type()) {
            case "boolean" -> {
                ModuleRegistry.setValue(selected.id(), setting.id(),
                        new JsonPrimitive(!setting.value().get().getAsBoolean()));
            }
            case "choice" -> {
                List<String> options = setting.options();
                if (options.isEmpty()) return;
                String value = setting.value().get().getAsString();
                int directIndex = choiceOptionAt(setting, mouseX, mouseY, rowTop, l);
                if (directIndex >= 0) {
                    String option = options.get(directIndex);
                    ModuleRegistry.setValue(selected.id(), setting.id(), new JsonPrimitive(option));
                    return;
                }
                int current = 0;
                for (int i = 0; i < options.size(); i++) {
                    if (options.get(i).equalsIgnoreCase(value)) {
                        current = i;
                        break;
                    }
                }
                int direction = button == GLFW.GLFW_MOUSE_BUTTON_RIGHT ? -1 : 1;
                int next = Math.floorMod(current + direction, options.size());
                ModuleRegistry.setValue(selected.id(), setting.id(), new JsonPrimitive(options.get(next)));
            }
            case "number", "integer" -> {
                if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
                commitEditor();
                startDragging(setting, -1);
                applySlider(setting, mouseX, l);
            }
            case "range" -> {
                if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
                commitEditor();
                startDragging(setting, nearestRangeHandle(setting, mouseX, l));
                applyRangeSlider(setting, draggingRangeIndex, mouseX, l);
            }
            case "text", "color" -> {
                commitEditor();
                editingSetting = setting;
                editBuffer = setting.value().get().getAsString();
            }
            default -> { }
        }
    }

    private int choiceOptionAt(Setting setting, int mouseX, int mouseY, int rowTop, Layout layout) {
        int left = settingsContentLeft(layout);
        int right = settingsContentRight(layout);
        int top = rowTop + 20;
        int bottom = rowTop + rowHeight(setting) - 5;
        if (!inside(mouseX, mouseY, left, top, right - left, bottom - top)) {
            return -1;
        }

        List<String> options = setting.options();
        int gap = 3;
        int cellWidth = Math.max(1, (right - left - gap * (options.size() - 1)) / options.size());
        for (int index = 0; index < options.size(); index++) {
            int cellLeft = left + index * (cellWidth + gap);
            int cellRight = index == options.size() - 1 ? right : cellLeft + cellWidth;
            if (mouseX >= cellLeft && mouseX < cellRight) {
                return index;
            }
        }
        return -1;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingSetting == null) return super.mouseDragged(event, dragX, dragY);
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT || selected == null
                || !selected.id().equals(draggingModuleId)) {
            stopDragging();
            return false;
        }
        if ("range".equals(draggingSetting.type())) {
            applyRangeSlider(draggingSetting, draggingRangeIndex, toUi(event.x()), layout());
        } else {
            applySlider(draggingSetting, toUi(event.x()), layout());
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        boolean handled = draggingSetting != null;
        stopDragging();
        return handled || super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        Layout l = layout();
        x = toUi(x);
        y = toUi(y);
        int amount = (int) Math.round(vertical * 26.0);
        if (inside(x, y, l.x, l.listTop, l.categoryWidth, l.bottom() - l.listTop)) {
            categoryScroll -= amount;
        } else if (inside(x, y, l.categoryRight, l.listTop, l.moduleWidth, l.bottom() - l.listTop)) {
            moduleScroll -= amount;
        } else if (inside(x, y, l.moduleRight, l.listTop, l.settingsWidth, l.bottom() - l.listTop)) {
            settingsScroll -= amount;
        }
        clampScroll(l);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        InputConstants.Key pressedKey = ModuleKeybinds.fromEvent(event);
        if (bindingKey) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE || ModuleKeybinds.isGuiKey(pressedKey)) {
                bindingKey = false;
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_BACKSPACE || event.key() == GLFW.GLFW_KEY_DELETE) {
                ModuleKeybinds.unbind(selected.id());
                bindingKey = false;
                return true;
            }
            commitEditor();
            if (ModuleKeybinds.bind(selected.id(), pressedKey)) {
                bindingKey = false;
            }
            return true;
        }
        if (ModuleKeybinds.isGuiKey(pressedKey)) {
            onClose();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (editingSetting != null) {
                editingSetting = null;
                editBuffer = "";
            } else if (searchFocused) {
                searchFocused = false;
            } else {
                onClose();
            }
            return true;
        }
        if (editingSetting != null || searchFocused) {
            if ((event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0 && event.key() == GLFW.GLFW_KEY_V) {
                appendText(minecraft.keyboardHandler.getClipboard());
                return true;
            }
            if ((event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0 && event.key() == GLFW.GLFW_KEY_C) {
                minecraft.keyboardHandler.setClipboard(editingSetting != null ? editBuffer : search);
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_BACKSPACE) {
                if (editingSetting != null) editBuffer = removeLast(editBuffer);
                else {
                    search = removeLast(search);
                    selectionAfterSearch();
                }
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_DELETE) {
                if (editingSetting != null) editBuffer = "";
                else {
                    search = "";
                    selectionAfterSearch();
                }
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
                if (editingSetting != null) commitEditor();
                else searchFocused = false;
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (bindingKey) {
            return true;
        }
        if ((editingSetting == null && !searchFocused) || !event.isAllowedChatCharacter()) {
            return super.charTyped(event);
        }
        appendText(event.codepointAsString());
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void appendText(String value) {
        if (value == null || value.isEmpty()) return;
        String clean = value.replaceAll("[\\p{Cntrl}]", "");
        if (editingSetting != null) {
            editBuffer = limit(editBuffer + clean, 96);
        } else {
            search = limit(search + clean, 64);
            selectionAfterSearch();
        }
    }

    private void commitEditor() {
        if (editingSetting != null && selected != null) {
            ModuleRegistry.setValue(selected.id(), editingSetting.id(), new JsonPrimitive(editBuffer));
        }
        editingSetting = null;
        editBuffer = "";
    }

    private void selectionAfterSearch() {
        moduleScroll = 0;
        settingsScroll = 0;
        List<Module> modules = filteredModules();
        if (selected == null || modules.stream().noneMatch(module -> module.id().equals(selected.id()))) {
            selected = firstModule(modules);
        }
    }

    private void applySlider(Setting setting, double mouseX, Layout l) {
        if (selected == null) return;
        double value = sliderValue(setting, mouseX, l);
        if ("integer".equals(setting.type())) value = Math.rint(value);
        ModuleRegistry.setValue(selected.id(), setting.id(), new JsonPrimitive(value));
    }

    private void applyRangeSlider(Setting setting, int index, double mouseX, Layout l) {
        if (selected == null || index < 0) return;
        JsonArray old = setting.value().get().getAsJsonArray();
        double low = old.get(0).getAsDouble();
        double high = old.get(1).getAsDouble();
        double value = sliderValue(setting, mouseX, l);
        if (index == 0) low = Math.min(value, high);
        else high = Math.max(value, low);
        JsonArray next = new JsonArray();
        next.add(low);
        next.add(high);
        ModuleRegistry.setValue(selected.id(), setting.id(), next);
    }

    private int nearestRangeHandle(Setting setting, double mouseX, Layout l) {
        int[] track = sliderTrack(l);
        JsonArray values = setting.value().get().getAsJsonArray();
        int low = sliderX(values.get(0).getAsDouble(), setting.min(), setting.max(), track[0], track[1]);
        int high = sliderX(values.get(1).getAsDouble(), setting.min(), setting.max(), track[0], track[1]);
        if (low == high) {
            // A collapsed range used to always select the low handle, making it
            // impossible to expand toward larger values from the GUI.
            return mouseX < low ? 0 : 1;
        }
        return Math.abs(mouseX - low) <= Math.abs(mouseX - high) ? 0 : 1;
    }

    private void startDragging(Setting setting, int rangeIndex) {
        draggingSetting = setting;
        draggingRangeIndex = rangeIndex;
        draggingModuleId = selected == null ? null : selected.id();
    }

    private void stopDragging() {
        draggingSetting = null;
        draggingRangeIndex = -1;
        draggingModuleId = null;
    }

    private double sliderValue(Setting setting, double mouseX, Layout l) {
        int[] track = sliderTrack(l);
        int left = track[0];
        int right = track[1];
        double ratio = clamp((mouseX - left) / Math.max(1.0, right - left), 0, 1);
        double value = setting.min() + ratio * (setting.max() - setting.min());
        double step = setting.step() == null || setting.step() <= 0 ? 0 : setting.step();
        if (step > 0) value = setting.min() + Math.round((value - setting.min()) / step) * step;
        return clamp(value, setting.min(), setting.max());
    }

    private static int[] sliderTrack(Layout l) {
        return new int[]{settingsContentLeft(l), settingsContentRight(l)};
    }

    private static int moduleRowLeft(Layout layout) {
        return layout.categoryRight + MODULE_ROW_INSET;
    }

    private static int moduleRowRight(Layout layout) {
        return layout.moduleRight - MODULE_ROW_INSET;
    }

    private static int moduleToggleX(Layout layout) {
        return moduleRowRight(layout) - MODULE_CONTENT_INSET - TOGGLE_WIDTH;
    }

    private static int settingsRowLeft(Layout layout) {
        return layout.moduleRight + SETTINGS_ROW_INSET;
    }

    private static int settingsRowRight(Layout layout) {
        return layout.right() - SETTINGS_ROW_INSET;
    }

    private static int settingsTop(Layout layout) {
        return layout.listTop;
    }

    private static int settingsContentLeft(Layout layout) {
        return settingsRowLeft(layout) + SETTINGS_CONTENT_INSET;
    }

    private static int settingsContentRight(Layout layout) {
        return settingsRowRight(layout) - SETTINGS_CONTENT_INSET;
    }

    private SettingHit settingAt(int mouseY, Layout l) {
        if (selected == null) return null;
        int y = settingsTop(l) - settingsScroll + KEYBIND_ROW_HEIGHT + 4;
        for (Setting setting : visibleSettings(selected)) {
            int height = rowHeight(setting);
            if (mouseY >= y && mouseY < y + height) return new SettingHit(setting, y);
            y += height + 4;
        }
        return null;
    }

    private List<Module> filteredModules() {
        String query = search.trim().toLowerCase(Locale.ROOT);
        List<Module> modules = new ArrayList<>();
        for (Module module : ModuleRegistry.modules()) {
            if (query.isEmpty()) {
                if (module.category().equals(category)) modules.add(module);
                continue;
            }
            boolean matches = module.name().toLowerCase(Locale.ROOT).contains(query)
                    || module.category().toLowerCase(Locale.ROOT).contains(query)
                    || module.settings().stream().anyMatch(setting ->
                    setting.name().toLowerCase(Locale.ROOT).contains(query));
            if (matches) modules.add(module);
        }
        return modules;
    }

    private void ensureSelection() {
        List<Module> modules = filteredModules();
        if (selected == null || modules.stream().noneMatch(module -> module.id().equals(selected.id()))) {
            selected = firstModule(modules);
            settingsScroll = 0;
        }
    }

    private void clampScroll(Layout l) {
        int categoryMax = Math.max(0, CATEGORIES.size() * CATEGORY_STRIDE - (l.bottom() - 8 - l.listTop));
        categoryScroll = (int) clamp(categoryScroll, 0, categoryMax);
        int moduleMax = Math.max(0, filteredModules().size() * MODULE_STRIDE - (l.bottom() - 8 - l.listTop));
        moduleScroll = (int) clamp(moduleScroll, 0, moduleMax);
        int settingHeight = 0;
        if (selected != null) {
            settingHeight += KEYBIND_ROW_HEIGHT + 4;
            for (Setting setting : visibleSettings(selected)) settingHeight += rowHeight(setting) + 4;
        }
        int settingsMax = Math.max(0, settingHeight - (l.bottom() - 8 - settingsTop(l)));
        settingsScroll = (int) clamp(settingsScroll, 0, settingsMax);
    }

    private int settingsHeight() {
        if (selected == null) return 0;
        int result = KEYBIND_ROW_HEIGHT + 4;
        for (Setting setting : visibleSettings(selected)) result += rowHeight(setting) + 4;
        return result;
    }

    private static List<Setting> visibleSettings(Module module) {
        return module.settings().stream().filter(Setting::isVisible).toList();
    }

    private static int rowHeight(Setting setting) {
        return switch (setting.type()) {
            case "number", "integer", "range" -> 31;
            case "choice", "text", "color" -> 38;
            default -> 24;
        };
    }

    private String displayValue(Setting setting) {
        if (editingSetting != null && editingSetting.id().equals(setting.id())) return editBuffer;
        JsonElement value = setting.value().get();
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            return format(array.get(0).getAsDouble()) + "–" + format(array.get(1).getAsDouble())
                    + " / " + format(setting.max());
        }
        if ("boolean".equals(setting.type())) return value.getAsBoolean() ? "ON" : "OFF";
        if ("number".equals(setting.type()) || "integer".equals(setting.type())) {
            return format(value.getAsDouble()) + " / " + format(setting.max());
        }
        if ("choice".equals(setting.type())) {
            String raw = value.getAsString();
            for (String option : setting.options()) {
                if (option.equalsIgnoreCase(raw)) {
                    return ModuleRegistry.displayChoice(option);
                }
            }
            return ModuleRegistry.displayChoice(raw);
        }
        return value.getAsString();
    }

    private void drawText(GuiGraphicsExtractor graphics, String value, int x, int y, int color, boolean shadow) {
        graphics.text(font, MoonsFonts.clickGuiText(value), x, y, color, shadow);
    }

    private void drawCenteredText(GuiGraphicsExtractor graphics, String value, int x, int y, int color) {
        drawText(graphics, value, x - textWidth(value) / 2, y, color, false);
    }

    private int textWidth(String value) {
        return font.width(MoonsFonts.clickGuiText(value));
    }

    private String clip(String value, int maxWidth) {
        if (maxWidth <= 0 || textWidth(value) <= maxWidth) return value;
        String suffix = "…";
        int end = value.length();
        while (end > 0 && textWidth(value.substring(0, end) + suffix) > maxWidth) {
            end--;
        }
        return end == 0 ? suffix : value.substring(0, end) + suffix;
    }

    private Layout layout() {
        int uiWidth = Math.max(1, (int) Math.floor(width / UI_SCALE));
        int uiHeight = Math.max(1, (int) Math.floor(height / UI_SCALE));
        int panelWidth = Math.max(360, Math.min(560, (int) Math.round(uiWidth * 0.54)));
        int panelHeight = Math.max(230, Math.min(380, (int) Math.round(uiHeight * 0.64)));
        panelWidth = Math.min(panelWidth, uiWidth - 20);
        panelHeight = Math.min(panelHeight, uiHeight - 20);
        int x = (uiWidth - panelWidth) / 2;
        int y = (uiHeight - panelHeight) / 2;
        int longestCategory = 0;
        for (String current : CATEGORIES) longestCategory = Math.max(longestCategory, textWidth(current));
        int categoryWidth = Math.min(84, Math.max(longestCategory + 28, (int) Math.round(panelWidth * 0.14)));
        int moduleWidth = Math.min(180, Math.max(110, (int) Math.round(panelWidth * 0.31)));
        int settingsWidth = panelWidth - categoryWidth - moduleWidth;
        if (settingsWidth < 110) {
            moduleWidth = Math.max(96, panelWidth - categoryWidth - 110);
            settingsWidth = panelWidth - categoryWidth - moduleWidth;
        }
        if (settingsWidth < 96) {
            categoryWidth = Math.max(48, panelWidth - moduleWidth - 96);
        }
        return new Layout(x, y, panelWidth, panelHeight, categoryWidth, moduleWidth);
    }

    private static int searchWidth(Layout layout) {
        return Math.min(180, Math.max(128, (int) Math.round(layout.width * 0.38)));
    }

    private int searchBoxWidth(Layout layout) {
        int w = searchWidth(layout);
        int cap = layout.width - 36 - 66 - textWidth("Modules") - 12;
        return Math.max(96, Math.min(w, cap));
    }

    private static void drawAccentLine(GuiGraphicsExtractor graphics, int left, int right, int y) {
        int segments = 24;
        int width = Math.max(1, right - left);
        for (int index = 0; index < segments; index++) {
            int x1 = left + width * index / segments;
            int x2 = left + width * (index + 1) / segments;
            double amount = index / (double) (segments - 1);
            double edgeFade = Math.sin(Math.PI * (index + 0.5D) / segments);
            int color = UiMotion.mixColor(ACCENT, ACCENT_SECONDARY, amount);
            graphics.fill(x1, y, x2, y + 2,
                    UiMotion.withAlpha(color, (int) Math.round(64 + 112 * edgeFade)));
        }
    }

    private static void drawSoftVerticalDivider(GuiGraphicsExtractor graphics, int x, int top, int bottom) {
        int segments = 18;
        int height = Math.max(1, bottom - top);
        for (int index = 0; index < segments; index++) {
            int y1 = top + height * index / segments;
            int y2 = top + height * (index + 1) / segments;
            double edgeFade = Math.sin(Math.PI * (index + 0.5D) / segments);
            int alpha = (int) Math.round(30 + 84 * edgeFade);
            graphics.fill(x, y1, x + 1, y2, UiMotion.withAlpha(DIVIDER, alpha));
        }
    }

    private static void drawSoftHorizontalDivider(GuiGraphicsExtractor graphics, int left, int right, int y) {
        int segments = 24;
        int width = Math.max(1, right - left);
        for (int index = 0; index < segments; index++) {
            int x1 = left + width * index / segments;
            int x2 = left + width * (index + 1) / segments;
            double edgeFade = Math.sin(Math.PI * (index + 0.5D) / segments);
            int alpha = (int) Math.round(26 + 76 * edgeFade);
            graphics.fill(x1, y, x2, y + 1, UiMotion.withAlpha(DIVIDER, alpha));
        }
    }

    private static void drawScrollbar(GuiGraphicsExtractor graphics, int x, int top, int bottom,
                                      int contentHeight, int scroll) {
        int available = bottom - top;
        if (contentHeight <= available || available <= 0) return;
        int maxScroll = contentHeight - available;
        int thumbHeight = Math.max(12, available * available / contentHeight);
        int thumbY = top + (int) Math.round((available - thumbHeight) * (scroll / (double) maxScroll));
        SmoothGui.roundedRect(graphics, x, top, x + 1, bottom, 0,
                UiMotion.withAlpha(PALETTE.border(), 85));
        SmoothGui.roundedRect(graphics, x - 1, thumbY, x + 2, thumbY + thumbHeight, 1,
                UiMotion.withAlpha(PALETTE.primary(), 170));
    }

    private static boolean enabled(Module module) {
        try {
            return module.enabled().getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean moduleIsBound() {
        return ModuleKeybinds.isBound(selected.id());
    }

    private static String safeTag(Module module) {
        try {
            String tag = module.tag().get();
            return tag == null ? "" : tag;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static int sliderX(double value, double min, double max, int left, int right) {
        double ratio = max <= min ? 0 : clamp((value - min) / (max - min), 0, 1);
        return left + (int) Math.round(ratio * (right - left));
    }

    private static int parsePreviewColor(String value) {
        try {
            String raw = value.replace("#", "").trim();
            if (raw.length() == 6) return 0xFF000000 | Integer.parseInt(raw, 16);
            if (raw.length() == 8) return (int) Long.parseLong(raw, 16);
        } catch (NumberFormatException ignored) {
        }
        return PALETTE.textDisabled();
    }

    private static String format(double value) {
        if (Math.rint(value) == value) return Long.toString(Math.round(value));
        return String.format(Locale.ROOT, "%.3f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String removeLast(String value) {
        if (value.isEmpty()) return value;
        int end = value.offsetByCodePoints(value.length(), -1);
        return value.substring(0, end);
    }

    private static String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static Module firstModule(List<Module> modules) {
        return modules.isEmpty() ? null : modules.getFirst();
    }

    private static boolean inside(double x, double y, int left, int top, int width, int height) {
        return x >= left && x < left + width && y >= top && y < top + height;
    }

    private static int toUi(double value) {
        return (int) Math.floor(value / UI_SCALE);
    }

    private int centeredTextY(int top, int height) {
        return top + Math.max(0, (height - font.lineHeight) / 2);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record SettingHit(Setting setting, int top) { }

    private static final class Layout {
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final int categoryWidth;
        private final int moduleWidth;
        private final int settingsWidth;
        private final int categoryRight;
        private final int moduleRight;
        private final int contentTop;
        private final int listTop;

        private Layout(int x, int y, int width, int height, int categoryWidth, int moduleWidth) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.categoryWidth = categoryWidth;
            this.moduleWidth = moduleWidth;
            this.settingsWidth = width - categoryWidth - moduleWidth;
            this.categoryRight = x + categoryWidth;
            this.moduleRight = categoryRight + moduleWidth;
            this.contentTop = y + 34;
            this.listTop = contentTop + 46;
        }

        private int right() { return x + width; }
        private int bottom() { return y + height; }
    }
}
