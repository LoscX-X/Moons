package com.blanoir.tempos.client.module.impl.render;

import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.DoubleSetting;
import com.blanoir.tempos.client.config.settings.IntSetting;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.api.ClientUiScreen;
import com.blanoir.tempos.client.api.hud.HudElements;
import com.blanoir.tempos.client.api.hud.Bounds;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/** Inventory slots and the bounds used by both the game HUD and its editor. */
@ModuleInfo(
        category = ModuleCategory.RENDER,
        description = "Shows your inventory in a scalable HUD panel.")
public final class InventorySee extends ClientFeature implements HudElements.Element<HudElements.Inventory> {
    public static final InventorySee INSTANCE = new InventorySee();

    private static final int WIDTH = 164;
    private final BooleanSetting hotbar = new BooleanSetting.Builder()
            .name("inventorysee.showHotbar").defaultValue(false).build();
    private final BooleanSetting background = new BooleanSetting.Builder()
            .name("inventorysee.background").defaultValue(true).build();

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("inventorysee.enabled").defaultValue(false).build();
    private final IntSetting positionX =
            new IntSetting.Builder()
                    .name("inventorysee.x")
                    .defaultValue(16)
                    .range(0, 10000)
                    .build();
    private final IntSetting positionY =
            new IntSetting.Builder()
                    .name("inventorysee.y")
                    .defaultValue(42)
                    .range(0, 10000)
                    .build();
    private final DoubleSetting scale =
            new DoubleSetting.Builder()
                    .name("inventorysee.scale")
                    .defaultValue(1.0D)
                    .range(0.25D, 2.0D)
                    .build();

    private InventorySee() {
        super("inventorysee", "InventorySee");
    }

    private java.util.List<ItemStack> previous = java.util.List.of();
    private Bounds lastBounds = Bounds.EMPTY;
    private HudElements.Inventory lastSnapshot = HudElements.Inventory.HIDDEN;

    @Override
    public void registerEvents() {
        HudElements.INVENTORY.register(this);
    }

    /** Copies only changed inventory contents. Snapshots are never modified by the renderer. */
    public HudElements.Inventory snapshot(boolean editing) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.level == null) {
            this.previous = java.util.List.of();
            return HudElements.Inventory.HIDDEN;
        }
        if (!editing
                && (!isEnabled()
                        || MinecraftClientAccess.isHudHidden(client)
                        || MinecraftClientAccess.screen(client) instanceof ClientUiScreen))
            return HudElements.Inventory.HIDDEN;
        var inventory = client.player.getInventory();
        int slots = hotbar.get() ? 36 : 27;
        boolean changed = this.previous.size() != slots;
        for (int slot = 0; !changed && slot < slots; slot++) {
            changed = !ItemStack.matches(this.previous.get(slot), inventory.getItem((9 + slot) % 36));
        }
        if (changed) {
            var next = new java.util.ArrayList<ItemStack>(slots);
            for (int slot = 0; slot < slots; slot++) next.add(inventory.getItem((9 + slot) % 36).copy());
            this.previous = java.util.List.copyOf(next);
        }
        Bounds bounds = currentBounds();
        if (lastSnapshot.items() != previous || lastSnapshot.bounds() != bounds ||
                lastSnapshot.background() != background.get()) {
            lastSnapshot = new HudElements.Inventory(true, bounds, scale(), previous, background.get());
        }
        return lastSnapshot;
    }

    @Override
    public boolean isEnabled() {
        return this.enabled.get();
    }

    @Override
    public int setEnabled(Minecraft client, boolean enabled) {
        this.enabled.set(enabled);
        ClientChat.send(client, "InventorySee " + (enabled ? "enabled" : "disabled") + ".");
        return 1;
    }

    public Bounds currentBounds() {
        Minecraft client = Minecraft.getInstance();
        double scale = scale();
        double width = WIDTH * scale;
        double height = (hotbar.get() ? 74 : 56) * scale;
        double left =
                Math.max(
                        0.0D,
                        Math.min(
                                this.positionX.get(),
                                client.getWindow().getGuiScaledWidth() - width));
        double top =
                Math.max(
                        0.0D,
                        Math.min(
                                this.positionY.get(),
                                client.getWindow().getGuiScaledHeight() - height));
        if (lastBounds.x() != left || lastBounds.y() != top || lastBounds.width() != width || lastBounds.height() != height)
            lastBounds = new Bounds(left, top, width, height);
        return lastBounds;
    }

    public void setEditorPosition(double left, double top, int screenWidth, int screenHeight) {
        double width = WIDTH * scale();
        double height = (hotbar.get() ? 74 : 56) * scale();
        int x = (int) Math.round(Math.max(0.0D, Math.min(screenWidth - width, left)));
        int y = (int) Math.round(Math.max(0.0D, Math.min(screenHeight - height, top)));
        this.positionX.set(x);
        this.positionY.set(y);
    }

    public double scale() {
        return crispScale(this.scale.get());
    }

    public int setScale(Minecraft ignoredClient, double value) {
        this.scale.set(crispScale(value));
        return 1;
    }

    public void resizeForEditor(
            double scale, double left, double top, int screenWidth, int screenHeight) {
        this.scale.set(crispScale(scale));
        setEditorPosition(left, top, screenWidth, screenHeight);
    }

    public void resetEditorPosition() {
        this.positionX.set(16);
        this.positionY.set(42);
        this.scale.set(1.0D);
    }

    private static double crispScale(double requested) {
        int guiScale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        double physicalScale = Math.max(1.0D, Math.rint(requested * guiScale));
        return Math.min(2.0D, physicalScale / guiScale);
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
                scale.describeStored("scale", "Scale", .05, this::setScale),
                background.describeStored("background", "Render background", (client, value) -> { background.set(value); return 1; }),
                hotbar.describeStored("showHotbar", "Show hotbar", (client, value) -> { hotbar.set(value); return 1; })
        };
    }
}
