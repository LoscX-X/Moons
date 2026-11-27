package com.blanoir.tempos.client.module.impl.misc.antibot;

import static com.blanoir.tempos.client.module.framework.SettingFactory.customChoice;

import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.ModeSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.manager.targeting.Targeting;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.List;

/** Unified bot filtering shared by every combat target selector. */
@ModuleInfo(
        category = ModuleCategory.MISC,
        description = "Filters suspected bots from target selection.")
public final class AntiBot extends ClientFeature {
    public static final AntiBot INSTANCE = new AntiBot();

    private final AntiBotState state = new AntiBotState();
    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("antibot.enabled").defaultValue(false).build();
    private final BooleanSetting hideBot =
            new BooleanSetting.Builder().name("antibot.hideBot").defaultValue(false).build();
    // All retired mode IDs, including Custom, resolve to the single Advanced option.
    private final ModeSetting<String> mode =
            new ModeSetting.Builder<String>()
                    .name("antibot.mode")
                    .defaultValue("advanced")
                    .option("advanced", "advanced")
                    .build();
    private boolean initialized;

    private AntiBot() {
        super("antibot", "AntiBot");
    }

    @Override
    public synchronized void registerEvents() {
        if (this.initialized) return;
        this.initialized = true;
        Targeting.setIgnoreCheck(this::isBot);
        Targeting.setHiddenEntityCheck(this::shouldHide);
        EventBus.TICK.register("AntiBot.tick", event -> this.state.tick(event.client()));
        EventBus.PACKET_RECEIVE_APPLY.register(
                "AntiBot.packetReceiveApply",
                event -> this.state.handlePacket(Minecraft.getInstance(), event.packet()));
    }

    public boolean isBot(Entity entity) {
        Minecraft client = Minecraft.getInstance();
        if (!isEnabled()
                || client == null
                || client.player == null
                || entity == client.player
                || !(entity instanceof Player player)) return false;
        return this.state.isBot(client, player);
    }

    @Override
    public boolean isEnabled() {
        return this.enabled.get();
    }

    public boolean isHideBotActive() {
        return isEnabled() && this.hideBot.get();
    }

    public boolean shouldHide(Entity entity) {
        return isHideBotActive() && isBot(entity);
    }

    public boolean shouldHideRenderState(Object state) {
        if (!isHideBotActive() || !(state instanceof AvatarRenderState avatar)) return false;
        Minecraft client = Minecraft.getInstance();
        return client != null
                && client.level != null
                && shouldHide(client.level.getEntity(avatar.id));
    }

    public int setHideBot(Minecraft ignoredClient, boolean value) {
        this.hideBot.set(value);
        return 1;
    }

    public String hudTag() {
        return this.mode.serialized();
    }

    public List<String> modeOptions() {
        return this.mode.optionIds();
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        if (this.enabled.get() != value) this.state.reset();
        this.enabled.set(value);
        ClientChat.send(client, "AntiBot " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public int setMode(Minecraft ignoredClient, String value) {
        this.mode.deserialize(value);
        return 1;
    }

    @Override
    protected String statusTag() {
        return this.hudTag();
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            customChoice("mode", "Mode", this::hudTag, this.modeOptions(), this::setMode)
                    .withDefault("advanced"),
            hideBot.describeStored("hide_bot", "HideBot", this::setHideBot)
        };
    }
}
