package com.blanoir.moons.client.ui.hud;

import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.ui.layout.Bounds;
import com.blanoir.moons.client.ui.render.SmoothGui;
import com.blanoir.moons.client.utils.render.ArgbColors;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import org.lwjgl.glfw.GLFW;

import java.util.List;

/** Direct manipulation for HUD components while the ClickGUI remains open. */
public final class HudLayoutController {
    private static final int SNAP_DISTANCE = 3;

    private HudEditorRegistry.Target active;
    private Action action = Action.NONE;
    private double dragOffsetX;
    private double dragOffsetY;
    private double resizeAnchorX;
    private double resizeAnchorY;
    private double resizeStartWidth;
    private double resizeStartHeight;
    private double resizeStartScale;
    private double resizePointerOffsetX;
    private double resizePointerOffsetY;
    private boolean guideX;
    private boolean guideY;
    private boolean settingsBatchOpen;

    public void render(
            GuiGraphicsExtractor graphics,
            double mouseX,
            double mouseY,
            int screenWidth,
            int screenHeight) {
        int accent = accentColor();
        if (guideX) graphics.verticalLine(screenWidth / 2, 0, screenHeight, withAlpha(accent, 165));
        if (guideY)
            graphics.horizontalLine(0, screenWidth, screenHeight / 2, withAlpha(accent, 165));
        List<HudEditorRegistry.Target> targets = HudEditorRegistry.targets();
        for (HudEditorRegistry.Target target : targets) {
            Bounds bounds = target.currentBounds();
            if (bounds.width() <= 0.0D || bounds.height() <= 0.0D) continue;
            boolean focused = target == active || bounds.contains(mouseX, mouseY);
            int left = (int) Math.floor(bounds.x()) - 2;
            int top = (int) Math.floor(bounds.y()) - 2;
            int right = (int) Math.ceil(bounds.right()) + 2;
            int bottom = (int) Math.ceil(bounds.bottom()) + 2;
            SmoothGui.roundedOutline(
                    graphics,
                    left,
                    top,
                    right,
                    bottom,
                    4,
                    1,
                    withAlpha(focused ? accent : 0x999999, focused ? 225 : 90),
                    0x00000000);
        }
    }

    public boolean mouseClicked(int button, double x, double y) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT && button != GLFW.GLFW_MOUSE_BUTTON_RIGHT)
            return false;
        List<HudEditorRegistry.Target> targets = HudEditorRegistry.targets();
        for (int index = targets.size() - 1; index >= 0; index--) {
            HudEditorRegistry.Target target = targets.get(index);
            Bounds bounds = target.currentBounds();
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && bounds.contains(x, y)) {
                beginInteraction();
                active = target;
                action = Action.RESIZE;
                resizeAnchorX = bounds.x();
                resizeAnchorY = bounds.y();
                resizeStartWidth = bounds.width();
                resizeStartHeight = bounds.height();
                resizeStartScale = target.currentScale();
                resizePointerOffsetX = x - bounds.right();
                resizePointerOffsetY = y - bounds.bottom();
                return true;
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && bounds.contains(x, y)) {
                beginInteraction();
                active = target;
                action = Action.MOVE;
                dragOffsetX = x - bounds.x();
                dragOffsetY = y - bounds.y();
                return true;
            }
        }
        active = null;
        action = Action.NONE;
        return false;
    }

    public boolean mouseDragged(double x, double y, int screenWidth, int screenHeight) {
        if (active == null || action == Action.NONE) return false;
        if (action == Action.RESIZE) resize(x, y, screenWidth, screenHeight);
        else move(x, y, screenWidth, screenHeight);
        return true;
    }

    public boolean mouseScrolled(
            double x, double y, double vertical, int screenWidth, int screenHeight) {
        HudEditorRegistry.Target target = targetAt(x, y);
        if (target == null || vertical == 0.0D) return false;
        active = target;
        Bounds bounds = target.currentBounds();
        double factor = Math.pow(1.1D, vertical);
        double left = x - (x - bounds.x()) * factor;
        double top = y - (y - bounds.y()) * factor;
        Settings.beginBatch();
        try {
            target.resize(target.currentScale() * factor, left, top, screenWidth, screenHeight);
        } finally {
            Settings.endBatch();
        }
        return true;
    }

    public void mouseReleased() {
        action = Action.NONE;
        guideX = false;
        guideY = false;
        if (settingsBatchOpen) {
            settingsBatchOpen = false;
            Settings.endBatch();
        }
    }

    public boolean resetSelected() {
        if (active == null) return false;
        Settings.beginBatch();
        try {
            active.reset();
        } finally {
            Settings.endBatch();
        }
        return true;
    }

    private void beginInteraction() {
        mouseReleased();
        Settings.beginBatch();
        settingsBatchOpen = true;
    }

    private void move(double mouseX, double mouseY, int screenWidth, int screenHeight) {
        Bounds bounds = active.currentBounds();
        double x = mouseX - dragOffsetX;
        double y = mouseY - dragOffsetY;
        guideX = Math.abs(x + bounds.width() / 2.0D - screenWidth / 2.0D) <= SNAP_DISTANCE;
        guideY = Math.abs(y + bounds.height() / 2.0D - screenHeight / 2.0D) <= SNAP_DISTANCE;
        if (guideX) x = (screenWidth - bounds.width()) / 2.0D;
        if (guideY) y = (screenHeight - bounds.height()) / 2.0D;
        if (Math.abs(x) <= SNAP_DISTANCE) x = 0.0D;
        if (Math.abs(y) <= SNAP_DISTANCE) y = 0.0D;
        if (Math.abs(x + bounds.width() - screenWidth) <= SNAP_DISTANCE)
            x = screenWidth - bounds.width();
        if (Math.abs(y + bounds.height() - screenHeight) <= SNAP_DISTANCE)
            y = screenHeight - bounds.height();
        active.move(x, y, screenWidth, screenHeight);
    }

    private void resize(double mouseX, double mouseY, int screenWidth, int screenHeight) {
        double localX = mouseX - resizePointerOffsetX - resizeAnchorX;
        double localY = mouseY - resizePointerOffsetY - resizeAnchorY;
        double denominator =
                resizeStartWidth * resizeStartWidth + resizeStartHeight * resizeStartHeight;
        if (denominator <= 0.0D) return;
        double ratio = (localX * resizeStartWidth + localY * resizeStartHeight) / denominator;
        active.resize(
                resizeStartScale * ratio, resizeAnchorX, resizeAnchorY, screenWidth, screenHeight);
    }

    private static HudEditorRegistry.Target targetAt(double x, double y) {
        List<HudEditorRegistry.Target> targets = HudEditorRegistry.targets();
        for (int index = targets.size() - 1; index >= 0; index--) {
            HudEditorRegistry.Target target = targets.get(index);
            Bounds bounds = target.currentBounds();
            if (bounds.contains(x, y)) return target;
        }
        return null;
    }

    private static int accentColor() {
        try {
            return Integer.parseInt(
                            Settings.getString("clickgui.theme.color", "#b29a65").replace("#", ""),
                            16)
                    & 0xFFFFFF;
        } catch (RuntimeException ignored) {
            return 0xB29A65;
        }
    }

    private static int withAlpha(int color, int alpha) {
        return ArgbColors.withAlpha(color, alpha);
    }

    private enum Action {
        NONE,
        MOVE,
        RESIZE
    }
}
