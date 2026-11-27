package com.blanoir.tempos.client.api.hud;

import net.minecraft.world.item.ItemStack;
import java.util.List;
import java.util.Objects;

/** Display data and editor operations supplied by the features that own each HUD element. */
public final class HudElements {
    public record Inventory(boolean visible, Bounds bounds, double scale, List<ItemStack> items, boolean background) {
        public Inventory(boolean visible, Bounds bounds, double scale, List<ItemStack> items) {
            this(visible, bounds, scale, items, true);
        }
        public static final Inventory HIDDEN = new Inventory(false, Bounds.EMPTY, 1, List.of());
    }
    public record Target(boolean visible, Bounds bounds, String name, String healthText, String hitText,
            double healthRatio, int healthColor, int alpha, float slide, java.util.UUID playerId) {
        public Target(boolean visible, Bounds bounds, String name, String healthText, String hitText,
                double healthRatio, int healthColor, int alpha, float slide) {
            this(visible, bounds, name, healthText, hitText, healthRatio, healthColor, alpha, slide, null);
        }
        public static final Target HIDDEN = new Target(false, Bounds.EMPTY, "", "", "", 0, 0x70D69A, 0, 0);
    }
    public interface Element<S> {
        S snapshot(boolean editing);
        Bounds currentBounds();
        double scale();
        void setEditorPosition(double left, double top, int screenWidth, int screenHeight);
        void resetEditorPosition();
        void resizeForEditor(double scale, double left, double top, int screenWidth, int screenHeight);
    }

    public static final Slot<Inventory> INVENTORY = new Slot<>(Inventory.HIDDEN);
    public static final Slot<Target> TARGET = new Slot<>(Target.HIDDEN);
    private HudElements() {}

    /** Early frames can arrive before the client has registered its visual features. */
    public static final class Slot<S> implements Element<S> {
        private final S hidden;
        private volatile Element<S> source;
        private Slot(S hidden) { this.hidden = hidden; }
        public void register(Element<S> element) { source = Objects.requireNonNull(element); }
        @Override public S snapshot(boolean editing) { return source == null ? hidden : source.snapshot(editing); }
        @Override public Bounds currentBounds() { return source == null ? Bounds.EMPTY : source.currentBounds(); }
        @Override public double scale() { return source == null ? 1 : source.scale(); }
        @Override public void setEditorPosition(double left, double top, int screenWidth, int screenHeight) {
            if (source != null) source.setEditorPosition(left, top, screenWidth, screenHeight);
        }
        @Override public void resetEditorPosition() { if (source != null) source.resetEditorPosition(); }
        @Override public void resizeForEditor(double scale, double left, double top, int screenWidth, int screenHeight) {
            if (source != null) source.resizeForEditor(scale, left, top, screenWidth, screenHeight);
        }
    }
}
