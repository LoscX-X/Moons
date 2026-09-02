package com.blanoir.moons.client.ui.hud;

import com.blanoir.moons.client.module.impl.render.InventorySee;
import com.blanoir.moons.client.module.impl.render.TargetInfoHud;
import com.blanoir.moons.client.ui.layout.Bounds;
import java.util.List;
import java.util.function.Supplier;

/** Central list of independently movable HUD elements. */
final class HudEditorRegistry {
    private static final List<Target> TARGETS = List.of(
            new Target("module_list", "Text GUI",
                    MoonsHud::currentBounds,
                    MoonsHud::setEditorPosition,
                    MoonsHud::resetEditorPosition,
                    MoonsHud::scale,
                    MoonsHud::resizeForEditor),
            new Target("inventory_see", "InventorySee",
                    InventorySee::currentBounds,
                    InventorySee::setEditorPosition,
                    InventorySee::resetEditorPosition,
                    InventorySee::scale,
                    InventorySee::resizeForEditor),
            new Target("target_info", "Target Info",
                    TargetInfoHud::currentBounds,
                    TargetInfoHud::setEditorPosition,
                    TargetInfoHud::resetEditorPosition,
                    TargetInfoHud::scale,
                    TargetInfoHud::resizeForEditor)
    );

    private HudEditorRegistry() {
    }

    static List<Target> targets() {
        return TARGETS;
    }

    record Target(String id, String name, Supplier<Bounds> bounds,
                  Positioner positioner, Runnable resetter,
                  Supplier<Double> scale, Resizer resizer) {
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

        void resize(double nextScale, double left, double top,
                    int screenWidth, int screenHeight) {
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
