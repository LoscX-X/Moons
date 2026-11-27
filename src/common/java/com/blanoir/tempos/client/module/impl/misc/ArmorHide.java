package com.blanoir.tempos.client.module.impl.misc;

import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Hides the local player's worn armor, head items and wings without changing equipment state. */
@ModuleInfo(
        category = ModuleCategory.MISC,
        description = "Hides your worn armor, head items, and wings locally.")
public final class ArmorHide extends ClientFeature {
    public static final ArmorHide INSTANCE = new ArmorHide();

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("armorhide.enabled").defaultValue(false).build();
    private final ThreadLocal<AvatarRenderState> currentAvatar = new ThreadLocal<>();

    private ArmorHide() {
        super("armorhide", "ArmorHide");
    }

    @Override
    public boolean isEnabled() {
        return this.enabled.get();
    }

    public void beginAvatar(AvatarRenderState state) {
        if (state == null) {
            this.currentAvatar.remove();
        } else {
            this.currentAvatar.set(state);
        }
    }

    public void endAvatar() {
        this.currentAvatar.remove();
    }

    public boolean shouldRenderCurrentArmor() {
        if (!this.enabled.get()) {
            return true;
        }

        Minecraft client = Minecraft.getInstance();
        var currentPlayer = client.player;
        AvatarRenderState state = this.currentAvatar.get();
        return currentPlayer == null || state == null || state.id != currentPlayer.getId();
    }

    /** Covers head-slot items submitted directly by replacement player renderers. */
    public boolean shouldRenderHeadItem(
            LivingEntity wearer, ItemStack item, ItemDisplayContext context) {
        if (!this.enabled.get() || context != ItemDisplayContext.HEAD) return true;
        var player = Minecraft.getInstance().player;
        return player == null
                || wearer != player
                || !ItemStack.isSameItemSameComponents(
                        item, player.getItemBySlot(EquipmentSlot.HEAD));
    }

    @Override
    public int setEnabled(Minecraft client, boolean enabled) {
        this.enabled.set(enabled);
        if (!enabled) {
            this.currentAvatar.remove();
        }
        ClientChat.send(client, "ArmorHide " + (enabled ? "enabled" : "disabled") + ".");
        return 1;
    }

    @Override
    protected String statusTag() {
        return "Self";
    }
}
