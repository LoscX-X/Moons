package com.blanoir.moons.client.module.impl.render.nametags;

import com.blanoir.moons.client.utils.combat.damage.PlayerHitEstimator;
import com.blanoir.moons.client.utils.player.PlayerHealthResolver;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;

import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/** Reuses nametag text without delaying updates to any of its displayed inputs. */
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

    public static Component format(
            Minecraft client,
            Player player,
            double distance,
            boolean showDistance,
            boolean safeMode) {
        Entry entry = ENTRIES.computeIfAbsent(player, ignored -> new Entry());
        String name = player.getName().getString();
        boolean changed =
                entry.component == null
                        || !name.equals(entry.name)
                        || entry.showDistance != showDistance
                        || entry.safeMode != safeMode;

        if (showDistance
                && (entry.distanceText == null
                        || Double.doubleToLongBits(entry.distance)
                                != Double.doubleToLongBits(distance))) {
            String distanceText = String.format(Locale.ROOT, "%.1fm", distance);
            changed |= !distanceText.equals(entry.distanceText);
            entry.distance = distance;
            entry.distanceText = distanceText;
        }

        if (!safeMode) {
            float health = PlayerHealthResolver.resolve(player);
            float maxHealth = PlayerHealthResolver.max(player);
            int healthColor =
                    health > maxHealth * 0.6F
                            ? HEALTH_GOOD
                            : health > maxHealth * 0.3F ? HEALTH_WARNING : HEALTH_LOW;
            if (entry.healthText == null
                    || Float.floatToIntBits(entry.health) != Float.floatToIntBits(health)) {
                String healthText = String.format(Locale.ROOT, "%.1f HP", health);
                changed |= !healthText.equals(entry.healthText);
                entry.health = health;
                entry.healthText = healthText;
            }

            // Equipment, enchantments, absorption, effects and attack attributes can
            // change between ticks. Keep evaluating them before comparing the result.
            int hits = PlayerHitEstimator.hitsToKill(client, player, health);
            changed |= healthColor != entry.healthColor || hits != entry.hits;
            entry.healthColor = healthColor;
            entry.hits = hits;
        }

        if (changed) {
            MutableComponent text = Component.literal(name).withColor(NAME_COLOR);
            if (showDistance) {
                text.append(Component.literal("  " + entry.distanceText).withColor(DISTANCE_COLOR));
            }
            if (!safeMode) {
                text.append(
                        Component.literal("  " + entry.healthText).withColor(entry.healthColor));
                String hits =
                        entry.hits == PlayerHitEstimator.UNKNOWN
                                ? "?"
                                : entry.hits == PlayerHitEstimator.UNREACHABLE
                                        ? "∞"
                                        : Integer.toString(entry.hits);
                text.append(Component.literal("  Hit: " + hits).withColor(HIT_COLOR));
            }
            entry.name = name;
            entry.showDistance = showDistance;
            entry.safeMode = safeMode;
            entry.component = text;
        }
        return entry.component;
    }

    public static void clear() {
        ENTRIES.clear();
    }

    private static final class Entry {
        private String name;
        private double distance;
        private String distanceText;
        private float health;
        private String healthText;
        private int healthColor;
        private int hits;
        private boolean showDistance;
        private boolean safeMode;
        private Component component;
    }
}
