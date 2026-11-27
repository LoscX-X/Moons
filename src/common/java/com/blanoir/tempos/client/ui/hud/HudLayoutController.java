package com.blanoir.tempos.client.ui.hud;

import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.api.hud.Bounds;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.List;

/** Direct manipulation for HUD components while the ClickGUI remains open. */
public final class HudLayoutController {
    private static final int SNAP_DISTANCE = 3;
    private static final int RESIZE_HANDLE_SIZE = 6;

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
    private Settings.Batch settingsBatch;
    private HudEditorRegistry.Target wheelTarget;
    private double wheelRequested, wheelApplied, wheelDirection;

    boolean guideX() { return guideX; }
    boolean guideY() { return guideY; }
    HudEditorRegistry.Target selected() { return active; }

    public boolean mouseClicked(int button, double x, double y) {
        if (button != InputConstants.MOUSE_BUTTON_LEFT
                && button != InputConstants.MOUSE_BUTTON_RIGHT) return false;
        List<HudEditorRegistry.Target> targets = HudEditorRegistry.targets();
        for (int index = targets.size() - 1; index >= 0; index--) {
            HudEditorRegistry.Target target = targets.get(index);
            Bounds bounds = target.currentBounds();
            if (bounds.width() <= 0.0D || bounds.height() <= 0.0D) continue;
            boolean resizeHandle =
                    bounds.contains(x, y)
                            && x >= bounds.right() - RESIZE_HANDLE_SIZE
                            && y >= bounds.bottom() - RESIZE_HANDLE_SIZE;
            if (bounds.contains(x, y)
                    && (button == InputConstants.MOUSE_BUTTON_RIGHT || resizeHandle)) {
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
            if (button == InputConstants.MOUSE_BUTTON_LEFT && bounds.contains(x, y)) {
                beginInteraction();
                active = target;
                action = Action.MOVE;
                dragOffsetX = x - bounds.x();
                dragOffsetY = y - bounds.y();
                return true;
            }
        }
        mouseReleased();
        active = null;
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
        if (target == null || vertical == 0.0D || !Double.isFinite(vertical)) return false;
        active = target;
        Bounds bounds = target.currentBounds();
        double original = target.currentScale(), direction = Math.signum(vertical);
        if (target != wheelTarget || original != wheelApplied || direction != wheelDirection)
            wheelRequested = original;
        wheelTarget = target;
        wheelDirection = direction;
        // Inventory and Target Info snap to whole physical pixels. Retain sub-step wheel motion.
        double next = wheelRequested * Math.pow(1.1D, vertical);
        wheelRequested = Double.isFinite(next) ? next : Double.MAX_VALUE;
        try (var batch = Settings.batch()) {
            target.resize(wheelRequested, bounds.x(), bounds.y(), screenWidth, screenHeight);
            wheelApplied = target.currentScale();
            if (original > 0 && original != wheelApplied) {
                double factor = wheelApplied / original;
                target.move(x - (x - bounds.x()) * factor, y - (y - bounds.y()) * factor, screenWidth, screenHeight);
            }
        }
        return true;
    }

    public void mouseReleased() {
        action = Action.NONE;
        guideX = false;
        guideY = false;
        Settings.Batch batch = settingsBatch;
        settingsBatch = null;
        if (batch != null) batch.close();
    }

    public boolean resetSelected() {
        if (active == null) return false;
        wheelTarget = null;
        try (var batch = Settings.batch()) {
            active.reset();
        }
        return true;
    }

    private void beginInteraction() {
        mouseReleased();
        wheelTarget = null;
        settingsBatch = Settings.batch();
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

    private enum Action {
        NONE,
        MOVE,
        RESIZE
    }
}
