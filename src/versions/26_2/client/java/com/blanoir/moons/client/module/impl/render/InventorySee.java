package com.blanoir.moons.client.module.impl.render;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.frame.HudRenderEvent;
import com.blanoir.moons.client.ui.MinecraftScreenAccess;
import com.blanoir.moons.client.ui.clickgui.MoonsComposeScreen;
import com.blanoir.moons.client.ui.layout.Bounds;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.ui.render.SmoothGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

/** Inventory HUD rendered through Minecraft's native item-model pipeline. */
public final class InventorySee {
    private static final int WIDTH = 188;
    private static final int HEIGHT = 68;

    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder()
                    .name("inventorysee.enabled")
                    .defaultValue(false)
                    .build();
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

    private InventorySee() {
    }

    public static void init() {
        EventBus.HUD_RENDER.register("InventorySee.hud", InventorySee::render);
    }

    private static void render(HudRenderEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;

        Object currentScreen = MinecraftScreenAccess.current(client);
        boolean editing = currentScreen instanceof MoonsComposeScreen screen
                && screen.isHudLayoutEditing();
        if (currentScreen instanceof MoonsComposeScreen && !editing) return;
        if (!editing && (!isEnabled() || MinecraftClientAccess.isHudHidden(client))) return;

        GuiGraphicsExtractor graphics = event.graphics();
        Bounds bounds = currentBounds();
        float scale = (float) scale();
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate((float) bounds.x(), (float) bounds.y());
            graphics.pose().scale(scale, scale);
            SmoothGui.roundedRect(graphics, 0, 0, WIDTH, HEIGHT, 6, 0xD80C0D10);

            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 9; column++) {
                    int slotX = 5 + column * 20;
                    int slotY = 5 + row * 20;
                    SmoothGui.roundedRect(graphics, slotX, slotY,
                            slotX + 18, slotY + 18, 3, 0x761F2025);

                    ItemStack stack = client.player.getInventory()
                            .getItem(9 + row * 9 + column);
                    if (stack.isEmpty()) continue;
                    graphics.item(stack, slotX + 1, slotY + 1);
                    graphics.itemDecorations(client.font, stack, slotX + 1, slotY + 1);
                }
            }
        } finally {
            graphics.pose().popMatrix();
        }
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
        double left = Math.max(0.0D, Math.min(POSITION_X.get(), client.getWindow().getGuiScaledWidth() - width));
        double top = Math.max(0.0D, Math.min(POSITION_Y.get(), client.getWindow().getGuiScaledHeight() - height));
        return new Bounds(left, top, width, height);
    }

    public static void setEditorPosition(double left, double top, int screenWidth, int screenHeight) {
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

    public static int setScale(Minecraft client, double value) {
        SCALE.set(crispScale(value));
        return 1;
    }

    public static void resizeForEditor(double scale, double left, double top,
                                       int screenWidth, int screenHeight) {
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
