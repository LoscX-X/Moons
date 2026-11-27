package com.blanoir.tempos.client.module.impl.combat;

import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.DoubleSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.lifecycle.ClientReady;
import com.blanoir.tempos.client.utils.math.RandomChance;
import com.blanoir.tempos.client.utils.raytrace.EntityRaycast;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/** Extends manual entity picking with a configurable per-tick chance. */
@ModuleInfo(
        category = ModuleCategory.COMBAT,
        description = "Sets the distance and chance for selecting an entity with a manual attack.")
public final class Reach extends ClientFeature {
    public static final Reach INSTANCE = new Reach();

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("reach.enabled").defaultValue(false).build();

    private final DoubleSetting range =
            new DoubleSetting.Builder()
                    .name("reach.range")
                    .defaultValue(3.1D)
                    .range(3.0D, 6.0D)
                    .build();

    private final DoubleSetting chance =
            new DoubleSetting.Builder()
                    .name("reach.normal.chance")
                    .defaultValue(100.0D)
                    .range(0.0D, 100.0D)
                    .build();

    private boolean initialized;
    private boolean expanding = true;

    private Reach() {
        super("reach", "Reach");
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            range.describeStored("range", "Target range", .05D, this::setRange),
            chance.describeStored("chance", "Chance %", 1.0D, this::setChance)
        };
    }

    @Override
    public void registerEvents() {
        if (initialized) {
            return;
        }
        initialized = true;
        EventBus.TICK.register(
                "Reach.sampleExpansionChance",
                event -> {
                    expanding = RandomChance.chancePercent(chance.get());
                });
    }

    /** Uses Range for entity picking and Range + 0.5 for the bounding ray/block query. */
    public void applyNormalPick(Minecraft client) {
        if (!ClientReady.gameplay(client) || !enabled.get() || !expanding) {
            return;
        }
        double entityRange = range.get();
        Vec3 start = client.player.getEyePosition();
        Vec3 look = client.player.getViewVector(1.0F);
        EntityHitResult extendedHit =
                EntityRaycast.findEntity(
                        client,
                        start,
                        start.add(look.scale(entityRange + 0.5D)),
                        entityRange,
                        false,
                        EntitySelector.CAN_BE_PICKED);
        if (extendedHit != null) {
            client.hitResult = extendedHit;
            client.crosshairPickEntity = extendedHit.getEntity();
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled.get();
    }

    @Override
    public String statusTag() {
        return format(range.get());
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        enabled.set(value);
        return showStatus(client);
    }

    public int setRange(Minecraft client, double value) {
        range.set(value);
        return showStatus(client);
    }

    public int setChance(Minecraft client, double value) {
        chance.set(value);
        return showStatus(client);
    }

    public int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "Reach: "
                        + (enabled.get() ? "enabled" : "disabled")
                        + ", range: "
                        + format(range.get())
                        + ", chance: "
                        + format(chance.get())
                        + "%. Usage: .tempos reach <enable|disable|range 3-6|chance 0-100>.");
        return 1;
    }

    private static String format(double value) {
        return Math.rint(value) == value
                ? Long.toString(Math.round(value))
                : Double.toString(value);
    }
}
