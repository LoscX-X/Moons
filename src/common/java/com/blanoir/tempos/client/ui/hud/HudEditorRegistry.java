package com.blanoir.tempos.client.ui.hud;

import com.blanoir.tempos.client.api.hud.HudElements;
import com.blanoir.tempos.client.api.hud.Bounds;

import java.util.List;
import java.util.function.Supplier;

/** Central list of independently movable HUD elements. */
final class HudEditorRegistry {
    private static final List<Target> TARGETS =
            List.of(
                    new Target(
                            "module_list",
                            "Text GUI",
                            TemposHud::currentBounds,
                            TemposHud::setEditorPosition,
                            TemposHud::resetEditorPosition,
                            TemposHud::scale,
                            TemposHud::resizeForEditor),
                    new Target(
                            "inventory_see",
                            "InventorySee",
                            HudElements.INVENTORY::currentBounds,
                            HudElements.INVENTORY::setEditorPosition,
                            HudElements.INVENTORY::resetEditorPosition,
                            HudElements.INVENTORY::scale,
                            HudElements.INVENTORY::resizeForEditor),
                    new Target(
                            "target_info",
                            "Target Info",
                            HudElements.TARGET::currentBounds,
                            HudElements.TARGET::setEditorPosition,
                            HudElements.TARGET::resetEditorPosition,
                            HudElements.TARGET::scale,
                            HudElements.TARGET::resizeForEditor));

    private HudEditorRegistry() {}

    static List<Target> targets() {
        return TARGETS;
    }

    record Target(
            String id,
            String name,
            Supplier<Bounds> bounds,
            Positioner positioner,
            Runnable resetter,
            Supplier<Double> scale,
            Resizer resizer) {
        Bounds currentBounds() {
            return bounds.get();
        }

        void move(double left, double top, int screenWidth, int screenHeight) {
            positioner.move(left, top, screenWidth, screenHeight);
        }

        void reset() {
            resetter.run();
        }

        double currentScale() {
            return scale.get();
        }

        void resize(double nextScale, double left, double top, int screenWidth, int screenHeight) {
            resizer.resize(nextScale, left, top, screenWidth, screenHeight);
        }
    }

    @FunctionalInterface
    interface Positioner {
        void move(double left, double top, int screenWidth, int screenHeight);
    }

    @FunctionalInterface
    interface Resizer {
        void resize(double scale, double left, double top, int screenWidth, int screenHeight);
    }
}
