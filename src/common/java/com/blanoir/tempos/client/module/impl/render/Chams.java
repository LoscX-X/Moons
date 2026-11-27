package com.blanoir.tempos.client.module.impl.render;

import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.render.VisualModelCapture;
import com.blanoir.tempos.client.render.model.ModelOverlayRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.player.Player;

import java.util.function.BiPredicate;

/** Player highlighting policy. GPU capture and additional models belong to the visual backend. */
@ModuleInfo(category = ModuleCategory.RENDER, description = "Changes how players are highlighted.")
public final class Chams extends ClientFeature {
    public static final Chams INSTANCE = new Chams();

    public static final float CHAMS_RED = 1.0f;
    public static final float CHAMS_GREEN = 0.56f;
    public static final float CHAMS_BLUE = 0.84f;
    public static final float HIGHLIGHTER_ALPHA = 0.46f;
    private final BooleanSetting highlighterEnabled =
            new BooleanSetting.Builder()
                    .name("chams.players.highlighter.enabled")
                    .defaultValue(true)
                    .build();
    private final BooleanSetting chamsEnabled =
            new BooleanSetting.Builder().name("chams.players.enabled").defaultValue(true).build();
    private BiPredicate<Minecraft, Player> playerFilter = (client, player) -> false;

    {
        ModelOverlayRenderer.setNormalEnabled(this.chamsEnabled.get());
    }

    private Chams() {
        super("chams", "Chams");
    }

    public void bindPlayerFilter(BiPredicate<Minecraft, Player> filter) {
        this.playerFilter = filter;
    }

    public boolean shouldRenderEntityChamsFor(AvatarRenderState state) {
        if (VisualModelCapture.active()) return !state.isSpectator;
        if (!this.chamsEnabled.get() || state.isSpectator) return false;
        Minecraft client = Minecraft.getInstance();
        if (client == null
                || client.level == null
                || client.player == null
                || MinecraftClientAccess.isHudHidden(client)) return false;
        var entity = client.level.getEntity(state.id);
        return entity != client.player
                && entity instanceof Player player
                && this.playerFilter.test(client, player);
    }

    public void beginPlayerChams(AvatarRenderState state) {
        ModelOverlayRenderer.beginPlayer(shouldRenderEntityChamsFor(state));
    }

    public void setHighlighterEnabled(boolean value) {
        this.highlighterEnabled.set(value);
    }

    public void setChamsEnabled(boolean value) {
        this.chamsEnabled.set(value);
        ModelOverlayRenderer.setNormalEnabled(this.chamsEnabled.get());
    }

    public boolean isHighlighterEnabled() {
        return this.highlighterEnabled.get();
    }

    public boolean isChamsEnabled() {
        return this.chamsEnabled.get();
    }

    public String highlighterStatusText() {
        return isHighlighterEnabled() ? "enabled" : "disabled";
    }

    public String chamsStatusText() {
        return isChamsEnabled() ? "enabled" : "disabled";
    }

    public static void close() {
        ModelOverlayRenderer.close();
    }

    @Override
    public boolean isEnabled() {
        return this.isHighlighterEnabled() || this.isChamsEnabled();
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        this.setHighlighterEnabled(value);
        this.setChamsEnabled(value);
        return 1;
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            highlighterEnabled.describeStored(
                    "highlighter",
                    "Highlighter",
                    (client, value) -> {
                        this.setHighlighterEnabled(value);
                        return 1;
                    }),
            chamsEnabled.describeStored(
                    "through_walls",
                    "Through walls",
                    (client, value) -> {
                        this.setChamsEnabled(value);
                        return 1;
                    })
        };
    }
}
