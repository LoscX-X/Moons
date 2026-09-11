package com.blanoir.moons.client.utils.world.placement;

import com.blanoir.moons.api.ScopedResources;

import net.minecraft.client.Minecraft;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Client-thread interlocks for placement state machines; each participant owns its cleanup. */
public final class PlacementCoordinator {
    public enum Owner {
        AUTO_WEB,
        AUTO_LAVA,
        ANTI_WEB,
        ANTI_LAVA,
        AUTO_BED
    }

    private record Participant(BooleanSupplier busy, Consumer<Minecraft> yield) {}

    private static final Map<Owner, Participant> participants = new EnumMap<>(Owner.class);

    private PlacementCoordinator() {}

    public static void register(Owner owner, BooleanSupplier busy) {
        register(owner, busy, client -> {});
    }

    public static synchronized void register(
            Owner owner, BooleanSupplier busy, Consumer<Minecraft> yield) {
        Participant participant = new Participant(busy, yield);
        participants.put(owner, participant);
        ScopedResources.own(
                (AutoCloseable)
                        () -> {
                            synchronized (PlacementCoordinator.class) {
                                participants.remove(owner, participant);
                            }
                        });
    }

    public static synchronized boolean busy(Owner owner) {
        Participant participant = participants.get(owner);
        return participant != null && participant.busy().getAsBoolean();
    }

    public static synchronized boolean busy() {
        return participants.values().stream()
                .anyMatch(participant -> participant.busy().getAsBoolean());
    }

    public static synchronized boolean busyFor(Owner owner) {
        for (var entry : participants.entrySet()) {
            if (entry.getKey() != owner
                    && !mayPreempt(owner, entry.getKey())
                    && entry.getValue().busy().getAsBoolean()) return true;
        }
        return false;
    }

    /** Called only after the requesting feature has a validated plan and materials. */
    public static synchronized void yieldTo(Owner owner, Minecraft client) {
        for (var entry : participants.entrySet()) {
            if (mayPreempt(owner, entry.getKey())) entry.getValue().yield().accept(client);
        }
    }

    private static boolean mayPreempt(Owner requester, Owner current) {
        return requester == Owner.ANTI_LAVA && current == Owner.AUTO_WEB;
    }
}
