package com.blanoir.moons.client.module.impl.render;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.ui.MinecraftScreenAccess;
import com.blanoir.moons.client.ui.clickgui.MoonsComposeScreen;
import com.blanoir.moons.client.ui.layout.Bounds;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/** Inventory data and editor settings for the independent final-frame Skia HUD. */
public final class InventorySee {
    private static final int WIDTH = 188;
    private static final int HEIGHT = 68;

    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("inventorysee.enabled").defaultValue(false).build();
    private static final IntSetting POSITION_X =
            new IntSetting.Builder()
                    .name("inventorysee.x")
                    .defaultValue(16)
                    .range(0, 10000)
                    .build();
    private static final IntSetting POSITION_Y =
            new IntSetting.Builder()
                    .name("inventorysee.y")
                    .defaultValue(42)
                    .range(0, 10000)
                    .build();
    private static final DoubleSetting SCALE =
            new DoubleSetting.Builder()
                    .name("inventorysee.scale")
                    .defaultValue(1.0D)
                    .range(0.25D, 2.0D)
                    .build();

    private InventorySee() {}

    private static java.util.List<ItemStack> previous = java.util.List.of();

    public static void init() {
        // Final-frame Skia owns drawing; no native HUD extraction callback is registered.
    }

    public record Snapshot(
            boolean visible, Bounds bounds, double scale, java.util.List<ItemStack> items) {
        public static final Snapshot HIDDEN =
                new Snapshot(false, new Bounds(0, 0, 0, 0), 1, java.util.List.of());
    }

    /** Copies only changed inventory contents. Snapshots are never modified by the renderer. */
    public static Snapshot snapshot(boolean editing) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.level == null) {
            previous = java.util.List.of();
            return Snapshot.HIDDEN;
        }
        if (!editing
                && (!isEnabled()
                        || MinecraftClientAccess.isHudHidden(client)
                        || MinecraftScreenAccess.current(client) instanceof MoonsComposeScreen))
            return Snapshot.HIDDEN;
        var inventory = client.player.getInventory();
        boolean changed = previous.size() != 27;
        for (int slot = 0; !changed && slot < 27; slot++) {
            changed = !ItemStack.matches(previous.get(slot), inventory.getItem(9 + slot));
        }
        if (changed) {
            var next = new java.util.ArrayList<ItemStack>(27);
            for (int slot = 9; slot < 36; slot++) next.add(inventory.getItem(slot).copy());
            previous = java.util.List.copyOf(next);
        }
        return new Snapshot(true, currentBounds(), scale(), previous);
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static int setEnabled(Minecraft client, boolean enabled) {
        ENABLED.set(enabled);
        ClientChat.send(client, "InventorySee " + (enabled ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static Bounds currentBounds() {
        Minecraft client = Minecraft.getInstance();
        double scale = scale();
        double width = WIDTH * scale;
        double height = HEIGHT * scale;
        double left =
                Math.max(
                        0.0D,
                        Math.min(POSITION_X.get(), client.getWindow().getGuiScaledWidth() - width));
        double top =
                Math.max(
                        0.0D,
                        Math.min(
                                POSITION_Y.get(),
                                client.getWindow().getGuiScaledHeight() - height));
        return new Bounds(left, top, width, height);
    }

    public static void setEditorPosition(
            double left, double top, int screenWidth, int screenHeight) {
        double width = WIDTH * scale();
        double height = HEIGHT * scale();
        int x = (int) Math.round(Math.max(0.0D, Math.min(screenWidth - width, left)));
        int y = (int) Math.round(Math.max(0.0D, Math.min(screenHeight - height, top)));
        POSITION_X.set(x);
        POSITION_Y.set(y);
    }

    public static double scale() {
        return crispScale(SCALE.get());
    }

    public static int setScale(Minecraft ignoredClient, double value) {
        SCALE.set(crispScale(value));
        return 1;
    }

    public static void resizeForEditor(
            double scale, double left, double top, int screenWidth, int screenHeight) {
        SCALE.set(crispScale(scale));
        setEditorPosition(left, top, screenWidth, screenHeight);
    }

    public static void resetEditorPosition() {
        POSITION_X.set(16);
        POSITION_Y.set(42);
        SCALE.set(1.0D);
    }

    private static double crispScale(double requested) {
        int guiScale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        double physicalScale = Math.max(1.0D, Math.rint(requested * guiScale));
        return Math.min(2.0D, physicalScale / guiScale);
    }
}
