package com.blanoir.moons.client.module.impl.misc.antibot;

import com.mojang.authlib.GameProfile;
import com.blanoir.moons.client.utils.player.PlayerListUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Mutable observations used by the LiquidBounce-compatible AntiBot modes. */
final class AntiBotState {
    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    private final Map<Integer, Integer> invalidGroundVl = new HashMap<>();
    private final Set<Integer> leftRadius = new HashSet<>();
    private final Set<Integer> hit = new HashSet<>();
    private final Set<Integer> swung = new HashSet<>();
    private final Set<Integer> critted = new HashSet<>();
    private final Set<Integer> attributes = new HashSet<>();

    private final Map<UUID, MatrixSuspect> matrixSuspects = new HashMap<>();
    private final Set<UUID> matrixBots = new HashSet<>();
    private final Map<UUID, IntaveSuspect> intaveSuspects = new HashMap<>();
    private final Set<UUID> intaveBots = new HashSet<>();
    private final Set<UUID> horizonBots = new HashSet<>();

    private Object levelIdentity;

    void tick(Minecraft client) {
        Object currentLevel = client == null ? null : client.level;
        if (currentLevel != levelIdentity) {
            reset();
            levelIdentity = currentLevel;
        }
        if (!AntiBot.isEnabled() || client == null || client.player == null || client.level == null) return;

        double radiusSquared = AntiBot.radius() * AntiBot.radius();
        for (Player player : client.level.players()) {
            if (player != client.player && client.player.distanceToSqr(player) > radiusSquared) {
                leftRadius.add(player.getId());
            }
        }
        updateMatrix(client);
    }

    void handlePacket(Minecraft client, Packet<?> packet) {
        if (!AntiBot.isEnabled() || client == null || packet == null) return;
        if (packet instanceof ClientboundMoveEntityPacket movement) {
            updateInvalidGround(client, movement);
        } else if (packet instanceof ClientboundUpdateAttributesPacket update) {
            attributes.add(update.getEntityId());
        } else if (packet instanceof ClientboundAnimatePacket animation) {
            if (animation.getAction() == ClientboundAnimatePacket.SWING_MAIN_HAND
                    || animation.getAction() == ClientboundAnimatePacket.SWING_OFF_HAND) {
                swung.add(animation.getId());
            } else if (animation.getAction() == ClientboundAnimatePacket.CRITICAL_HIT
                    || animation.getAction() == ClientboundAnimatePacket.MAGIC_CRITICAL_HIT) {
                critted.add(animation.getId());
            }
        } else if (packet instanceof ClientboundRemoveEntitiesPacket remove) {
            remove.getEntityIds().forEach((int id) -> clearEntity(id));
        } else if (packet instanceof ClientboundPlayerInfoUpdatePacket update) {
            updatePlayerInfo(client, update);
        } else if (packet instanceof ClientboundPlayerInfoRemovePacket remove) {
            remove.profileIds().forEach(this::clearProfile);
        }
    }

    void markHit(Entity entity) {
        if (entity != null) hit.add(entity.getId());
    }

    boolean isCustomBot(Minecraft client, Player player) {
        int id = player.getId();
        if (AntiBot.invalidGround() && invalidGroundVl.getOrDefault(id, 0) >= AntiBot.invalidGroundVl()) return true;
        if (AntiBot.alwaysInRadius() && !leftRadius.contains(id)) return true;
        if (AntiBot.ageCheck() && player.tickCount < AntiBot.minimumAge()) return true;
        if (AntiBot.nameCheck() && invalidName(player.getScoreboardName())) return true;
        if (AntiBot.duplicateCheck()
                && PlayerListUtils.hasSingleDifferentIdDuplicate(client, player.getGameProfile())) return true;
        if (AntiBot.noGameMode() && PlayerListUtils.missingGameMode(client, player)) return true;
        if (AntiBot.illegalPitch() && Math.abs(player.getXRot()) > 90.0F) return true;
        if (AntiBot.fakeEntityId() && (id < 0 || id > 1_000_000_000)) return true;
        if (AntiBot.needHit() && !hit.contains(id)) return true;
        if (AntiBot.illegalHealth() && client.player != null
                && player.getHealth() > client.player.getMaxHealth()) return true;
        if (AntiBot.needSwing() && !swung.contains(id)) return true;
        if (AntiBot.needCrit() && !critted.contains(id)) return true;
        if (AntiBot.needAttributes() && !attributes.contains(id)) return true;
        return AntiBot.illegalScale()
                && Math.abs(player.getAttributeValue(Attributes.SCALE) - 1.0D) > 1.0E-6D;
    }

    boolean isModeBot(AntiBotMode mode, Minecraft client, Player player) {
        return switch (mode) {
            case CUSTOM -> isCustomBot(client, player);
            case MATRIX -> matrixBots.contains(player.getUUID());
            case INTAVE_HEAVY -> intaveBots.contains(player.getUUID());
            case HORIZON -> horizonBots.contains(player.getUUID());
        };
    }

    void reset() {
        invalidGroundVl.clear();
        leftRadius.clear();
        hit.clear();
        swung.clear();
        critted.clear();
        attributes.clear();
        matrixSuspects.clear();
        matrixBots.clear();
        intaveSuspects.clear();
        intaveBots.clear();
        horizonBots.clear();
    }

    private void updateInvalidGround(Minecraft client, ClientboundMoveEntityPacket packet) {
        if (!packet.hasPosition() || client.level == null) return;
        Entity entity = packet.getEntity(client.level);
        if (!(entity instanceof Player player)) return;
        int id = player.getId();
        int current = invalidGroundVl.getOrDefault(id, 0);
        if (packet.isOnGround() && packet.getYa() != 0) {
            invalidGroundVl.put(id, current + 1);
        } else if (!packet.isOnGround() && current > 0) {
            int decayed = current / 2;
            if (decayed == 0) invalidGroundVl.remove(id);
            else invalidGroundVl.put(id, decayed);
        }
    }

    private void updatePlayerInfo(Minecraft client, ClientboundPlayerInfoUpdatePacket packet) {
        if (packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) {
            for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.newEntries()) {
                GameProfile profile = entry.profile();
                if (profile == null) continue;
                if (entry.gameMode() == null) horizonBots.add(entry.profileId());

                if (entry.latency() >= 2 && !PlayerListUtils.isUniqueExactProfile(client, profile)) {
                    intaveSuspects.put(entry.profileId(),
                            new IntaveSuspect(entry.latency(), System.currentTimeMillis()));
                }

                if (entry.latency() < 2 || !profile.properties().isEmpty()
                        || PlayerListUtils.isUniqueExactProfile(client, profile)) continue;
                if (PlayerListUtils.hasSingleDifferentIdDuplicate(client, profile)) {
                    matrixBots.add(entry.profileId());
                } else {
                    matrixSuspects.put(entry.profileId(), new MatrixSuspect(0, null));
                }
            }
        }

        if (packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY)) {
            long now = System.currentTimeMillis();
            for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries()) {
                IntaveSuspect suspect = intaveSuspects.remove(entry.profileId());
                if (suspect == null) continue;
                if (suspect.latency() - entry.latency() == suspect.latency()
                        && now - suspect.timestamp() <= 15L) {
                    intaveBots.add(entry.profileId());
                }
            }
        }
    }

    private void updateMatrix(Minecraft client) {
        if (matrixSuspects.isEmpty()) return;
        for (Player player : client.level.players()) {
            MatrixSuspect suspect = matrixSuspects.get(player.getUUID());
            if (suspect == null) continue;
            List<ItemStack> armor = armorSnapshot(player);
            if (suspect.firstSeenTick() == 0) {
                matrixSuspects.put(player.getUUID(),
                        new MatrixSuspect(client.player.tickCount, armor));
                continue;
            }
            if (client.player.tickCount <= suspect.firstSeenTick()) continue;
            boolean changed = !ItemStack.listMatches(suspect.armor(), armor);
            if ((fullyArmored(player) || changed) && player.getGameProfile().properties().isEmpty()) {
                matrixBots.add(player.getUUID());
            }
            matrixSuspects.remove(player.getUUID());
        }
    }

    private boolean invalidName(String name) {
        if (name == null || name.length() < AntiBot.nameMinLength()
                || name.length() > AntiBot.nameMaxLength()) return true;
        return name.codePoints().anyMatch(code -> !(code >= '0' && code <= '9')
                && !(code >= 'a' && code <= 'z')
                && !(code >= 'A' && code <= 'Z') && code != '_');
    }

    private static boolean fullyArmored(Player player) {
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty() || !stack.isEnchanted()) return false;
        }
        return true;
    }

    private static List<ItemStack> armorSnapshot(Player player) {
        List<ItemStack> result = new ArrayList<>(4);
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            result.add(player.getItemBySlot(slot).copy());
        }
        return result;
    }

    private void clearEntity(int id) {
        invalidGroundVl.remove(id);
        leftRadius.remove(id);
        hit.remove(id);
        swung.remove(id);
        critted.remove(id);
        attributes.remove(id);
    }

    private void clearProfile(UUID id) {
        matrixSuspects.remove(id);
        matrixBots.remove(id);
        intaveSuspects.remove(id);
        intaveBots.remove(id);
        horizonBots.remove(id);
    }

    private record MatrixSuspect(int firstSeenTick, List<ItemStack> armor) {
    }

    private record IntaveSuspect(int latency, long timestamp) {
    }
}
