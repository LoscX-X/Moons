package com.blanoir.moons.client.module.impl.render.nametags;

import com.blanoir.moons.client.render.WorldLabelRenderer.Span;
import com.blanoir.moons.client.utils.combat.damage.PlayerHitEstimator;
import com.blanoir.moons.client.utils.player.PlayerHealthResolver;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Tick-sampled display statistics and frame-interpolated distance, independent of combat queries. */
public final class NametagTextCache {
    private static final int NAME_COLOR = 0xFFF5F7FF;
    private static final int DISTANCE_COLOR = 0xFFD0BE90;
    private static final int HEALTH_GOOD = 0xFF8FE3AB;
    private static final int HEALTH_WARNING = 0xFFFFD47B;
    private static final int HEALTH_LOW = 0xFFFF8F9E;
    private static final int HIT_COLOR = 0xFFFFD75A;

    // Only the render thread accesses this cache; values never retain their player keys.
    private static final Map<Player, Entry> ENTRIES = new WeakHashMap<>();

    private NametagTextCache() {}

    public static List<Span> format(
            Minecraft client,
            Player player,
            double distance,
            boolean showDistance,
            boolean safeMode) {
        Entry entry = ENTRIES.computeIfAbsent(player, ignored -> new Entry());
        boolean changed =
                entry.text == null
                        || entry.showDistance != showDistance
                        || entry.safeMode != safeMode;
        long distanceTenths = Math.round(Math.max(0, distance) * 10);
        if (showDistance && (entry.text == null || distanceTenths != entry.distanceTenths)) {
            entry.distanceTenths = distanceTenths;
            changed = true;
        }

        int tick = client.player.tickCount;
        var weapon = client.player.getWeaponItem();
        // UI estimates need one sample per game tick, not one enchantment/scoreboard scan
        // per rendered frame. A hotbar switch still refreshes the displayed estimate immediately.
        if (entry.text == null
                || entry.sampleTick != tick
                || entry.safeMode != safeMode
                || entry.weapon != weapon) {
            String name = player.getName().getString();
            changed |= !name.equals(entry.name);
            entry.name = name;
            if (!safeMode) {
                float health = PlayerHealthResolver.resolve(player);
                float maxHealth = PlayerHealthResolver.max(player);
                long healthTenths = Math.round(Math.max(0, health) * 10.0);
                int healthColor =
                        health > maxHealth * .6F
                                ? HEALTH_GOOD
                                : health > maxHealth * .3F ? HEALTH_WARNING : HEALTH_LOW;
                int hits = PlayerHitEstimator.hitsToKill(client, player, health);
                changed |=
                        healthTenths != entry.healthTenths
                                || healthColor != entry.healthColor
                                || hits != entry.hits;
                entry.healthTenths = healthTenths;
                entry.healthColor = healthColor;
                entry.hits = hits;
            }
            entry.sampleTick = tick;
            entry.weapon = weapon;
        }

        if (changed) {
            List<Span> text = new ArrayList<>();
            text.add(new Span(entry.name, NAME_COLOR));
            if (showDistance) {
                text.add(new Span("  ", DISTANCE_COLOR));
                appendNumber(text, decimal(entry.distanceTenths), DISTANCE_COLOR);
                text.add(new Span("m", DISTANCE_COLOR));
            }
            if (!safeMode) {
                text.add(new Span("  ", entry.healthColor));
                appendNumber(text, decimal(entry.healthTenths), entry.healthColor);
                text.add(new Span(" HP", entry.healthColor));
                text.add(new Span("  Hit: ", HIT_COLOR));
                String hits =
                        entry.hits == PlayerHitEstimator.UNKNOWN
                                ? "?"
                                : entry.hits == PlayerHitEstimator.UNREACHABLE
                                        ? "∞"
                                        : Integer.toString(entry.hits);
                appendNumber(text, hits, HIT_COLOR);
            }
            entry.showDistance = showDistance;
            entry.safeMode = safeMode;
            entry.text = List.copyOf(text);
        }
        return entry.text;
    }

    private static String decimal(long tenths) {
        return tenths / 10 + "." + tenths % 10;
    }

    private static void appendNumber(List<Span> text, String number, int color) {
        // A fixed alphabet reuses atlas tiles for every distance/HP value. Player names
        // remain whole font runs, preserving their fallback-font layout.
        for (int i = 0; i < number.length(); i++) {
            text.add(new Span(number.substring(i, i + 1), color));
        }
    }

    public static void clear() {
        ENTRIES.clear();
    }

    private static final class Entry {
        private String name;
        private long distanceTenths;
        private long healthTenths;
        private int healthColor;
        private int hits;
        private int sampleTick;
        private Object weapon;
        private boolean showDistance;
        private boolean safeMode;
        private List<Span> text;
    }
}
