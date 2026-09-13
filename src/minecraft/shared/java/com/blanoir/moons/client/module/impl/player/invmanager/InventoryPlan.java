package com.blanoir.moons.client.module.impl.player.invmanager;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** Pure planner. It produces only cursor-free SWAP operations, never throws owned items. */
public final class InventoryPlan {
    private InventoryPlan() {}

    public static List<InventoryAction> build(
            InventorySnapshot snapshot,
            List<InventoryRole> roles,
            BitSet protectedSlots,
            boolean sort,
            boolean refill,
            boolean protectSpecial) {
        List<InventoryRules.Rule> rules = new ArrayList<>();
        for (int i = 0; i < 10; i++) rules.add(InventoryRules.resolve(i, roles.get(i)));
        return build(snapshot, roles, rules, protectedSlots, sort, refill, protectSpecial);
    }

    public static List<InventoryAction> build(
            InventorySnapshot snapshot,
            List<InventoryRole> roles,
            List<InventoryRules.Rule> rules,
            BitSet protectedSlots,
            boolean sort,
            boolean refill,
            boolean protectSpecial) {
        var blocked = (BitSet) protectedSlots.clone();
        for (int index = 0; index < 41; index++) {
            if (index >= 36 && index < 40
                    || snapshot.menuSlot(index) < 0
                    || role(roles, index) == InventoryRole.LOCKED) blocked.set(index);
        }
        // Reserve an already satisfied role, including duplicate roles. Filling the second
        // block slot must never empty the first one and oscillate on the following scan.
        BitSet reserved = new BitSet(41);
        var swaps = new ArrayList<InventoryAction>();
        var working = new ArrayList<>(snapshot.items());
        var ordered =
                java.util.Arrays.stream(targets())
                        .boxed()
                        .sorted(
                                java.util.Comparator.<Integer>comparingInt(
                                                target -> rule(rules, target).specificity())
                                        .reversed())
                        .toList();
        for (int target : ordered) {
            var wanted = role(roles, target);
            var matcher = rule(rules, target);
            if (!wanted.managed() || blocked.get(target)) continue;
            ItemStack current = working.get(target);
            if (!matcher.mayMove(current, protectSpecial)) continue;
            if (matcher.matches(current)) reserved.set(target);
            if (current.isEmpty() ? !refill : !sort) continue;
            int best = matcher.matches(current) ? target : -1;
            for (int source = 0; source < 41; source++) {
                if (source == target || blocked.get(source) || reserved.get(source)) continue;
                ItemStack candidate = working.get(source);
                // Preserve an equally or more specific satisfied slot, while allowing an exact
                // rule to claim its only item from a broad category that can use alternatives.
                if ((source < 9 || source == 40)
                        && role(roles, source).managed()
                        && rule(rules, source).specificity() >= matcher.specificity()
                        && rule(rules, source).matches(candidate)) continue;
                if (!matcher.matches(candidate) || !matcher.mayMove(candidate, protectSpecial))
                    continue;
                if (best < 0
                        || better(wanted, matcher, candidate, working.get(best), best == target))
                    best = source;
            }
            if (best < 0 || best == target) continue;
            ItemStack chosen = working.get(best);
            swaps.add(
                    new InventoryAction(
                            best,
                            target,
                            chosen.copy(),
                            current.copy(),
                            (current.isEmpty() ? "Refill " : "Sort ")
                                    + label(target)
                                    + " · "
                                    + matcher.name()));
            working.set(target, chosen);
            working.set(best, current);
            reserved.set(target);
        }
        return List.copyOf(swaps);
    }

    private static boolean better(
            InventoryRole role,
            InventoryRules.Rule rule,
            ItemStack candidate,
            ItemStack current,
            boolean alreadyPlaced) {
        if (alreadyPlaced && !rule.include().isEmpty() && !rule.preferFirst()) return false;
        if (!rule.include().isEmpty()) {
            int preference = Integer.compare(rule.rank(candidate), rule.rank(current));
            if (preference != 0) return preference < 0;
        }
        boolean candidateWorn = InventoryItems.nearlyBroken(candidate);
        boolean currentWorn = InventoryItems.nearlyBroken(current);
        if (candidateWorn != currentWorn) return !candidateWorn;
        // Do not replace a usable chosen food/block variant, or promote enchanted apples
        // into a slot occupied by ordinary apples. Refill waits until that slot is empty.
        if (alreadyPlaced && !current.isDamageableItem()) return false;
        int quality =
                Double.compare(
                        InventoryItems.quality(role, candidate),
                        InventoryItems.quality(role, current));
        if (quality != 0) return quality > 0;
        if (alreadyPlaced) return false;
        if (candidate.getCount() != current.getCount())
            return candidate.getCount() > current.getCount();
        return candidate.getMaxDamage() - candidate.getDamageValue()
                > current.getMaxDamage() - current.getDamageValue();
    }

    public static InventoryRole role(List<InventoryRole> roles, int inventorySlot) {
        return inventorySlot == 40
                ? roles.get(9)
                : inventorySlot < 9 ? roles.get(inventorySlot) : InventoryRole.FREE;
    }

    public static String label(int inventorySlot) {
        return inventorySlot == 40 ? "offhand" : "slot " + (inventorySlot + 1);
    }

    private static InventoryRules.Rule rule(List<InventoryRules.Rule> rules, int slot) {
        return rules.get(slot == 40 ? 9 : slot);
    }

    private static int[] targets() {
        return new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 40};
    }
}
