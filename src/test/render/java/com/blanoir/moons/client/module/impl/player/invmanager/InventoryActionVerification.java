package com.blanoir.moons.client.module.impl.player.invmanager;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Headless regression cases for missing server tags and inventory-session retry exhaustion. */
public final class InventoryActionVerification {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        for (var item :
                new net.minecraft.world.item.Item[] {
                    Items.IRON_PICKAXE,
                    Items.IRON_AXE,
                    Items.IRON_SHOVEL,
                    Items.IRON_SWORD,
                    Items.STICK
                }) {
            item.builtInRegistryHolder()
                    .bindComponents(
                            DataComponentMap.builder()
                                    .set(DataComponents.MAX_STACK_SIZE, 1)
                                    .build());
        }
        var pickaxe = new ItemStack(Items.IRON_PICKAXE);
        require(!pickaxe.is(ItemTags.PICKAXES), "Fixture must have no server item tags");
        require(
                InventoryItems.matches(InventoryRole.PICKAXE, pickaxe),
                "Vanilla pickaxe survives missing tags");
        require(!InventoryItems.matches(InventoryRole.AXE, pickaxe), "Pickaxe is not an axe");
        require(
                InventoryItems.matches(InventoryRole.AXE, new ItemStack(Items.IRON_AXE)),
                "Axe fallback");
        require(
                InventoryItems.matches(InventoryRole.SHOVEL, new ItemStack(Items.IRON_SHOVEL)),
                "Shovel fallback");
        require(
                InventoryItems.matches(InventoryRole.SWORD, new ItemStack(Items.IRON_SWORD)),
                "Sword fallback");
        var named = new ItemStack(Items.STICK);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Iron Sword"));
        require(
                !InventoryItems.matches(InventoryRole.SWORD, named),
                "Display names cannot spoof category");
        require(
                !InventoryItems.protection(named).isEmpty(),
                "Explicit special-item protection stays intact");
        verifyRules(pickaxe, named);

        var session = new InventorySession();
        var action = new InventoryAction(10, 0, pickaxe, ItemStack.EMPTY, "test");
        require(session.reserve(action, 0), "First attempt");
        require(session.reserve(action, 1), "Second attempt");
        require(!session.reserve(action, 2), "Repeated rejection is throttled");
        require(!session.reserve(action, 4_999_999_999L), "Cooldown cannot be bypassed");
        require(
                session.reserve(action, 5_000_000_001L),
                "Retry recovers without reopening inventory");
        for (int i = 1; i <= 150; i++)
            require(
                    session.reserve(action, (i + 1) * 5_000_000_001L),
                    "Long sessions never exhaust lifetime quota");
        System.out.println("MOONS_INVENTORY_ACTIONS_VERIFIED");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void verifyRules(ItemStack pickaxe, ItemStack named) {
        var entries =
                java.util.List.of(
                        new InventoryRules.Entry(
                                "minecraft:iron_pickaxe", java.util.Map.of(), false, null),
                        new InventoryRules.Entry("minecraft:stick", java.util.Map.of(), true, null),
                        new InventoryRules.Entry("missing:item", java.util.Map.of(), false, null),
                        new InventoryRules.Entry(
                                "minecraft:stick",
                                java.util.Map.of(
                                        "missing:component", com.google.gson.JsonNull.INSTANCE),
                                true,
                                null));
        var stacks = java.util.List.of(pickaxe, named, new ItemStack(Items.STICK), ItemStack.EMPTY);
        for (int include = 0; include < 16; include++)
            for (int exclude = 0; exclude < 16; exclude++)
                for (boolean only : new boolean[] {false, true}) {
                    var allowed = new java.util.ArrayList<InventoryRules.Entry>();
                    var denied = new java.util.ArrayList<InventoryRules.Entry>();
                    for (int i = 0; i < entries.size(); i++) {
                        if ((include & (1 << i)) != 0) allowed.add(entries.get(i));
                        if ((exclude & (1 << i)) != 0) denied.add(entries.get(i));
                    }
                    var rule =
                            new InventoryRules.Rule(
                                    "parity",
                                    "parity",
                                    InventoryRole.PICKAXE,
                                    only,
                                    true,
                                    allowed,
                                    denied);
                    for (var stack : stacks) {
                        // Original short-circuit predicates, including unknown-ID fail-closed
                        // rules.
                        boolean expected =
                                !stack.isEmpty()
                                        && !denied.stream()
                                                .anyMatch(
                                                        e ->
                                                                e.matches(stack)
                                                                        || e.item()
                                                                                        .equals(
                                                                                                InventoryRules
                                                                                                        .itemId(
                                                                                                                stack))
                                                                                && !e.available())
                                        && !allowed.stream()
                                                .anyMatch(
                                                        e ->
                                                                e.item()
                                                                                .equals(
                                                                                        InventoryRules
                                                                                                .itemId(
                                                                                                        stack))
                                                                        && !e.available())
                                        && (allowed.stream().anyMatch(e -> e.matches(stack))
                                                || !only
                                                        && InventoryItems.matches(
                                                                InventoryRole.PICKAXE, stack));
                        require(rule.matches(stack) == expected, "Inventory matching parity");
                        for (boolean protect : new boolean[] {false, true}) {
                            boolean mayMove =
                                    stack.isEmpty()
                                            || !protect
                                            || InventoryItems.protection(stack).isEmpty()
                                            || allowed.stream()
                                                    .anyMatch(e -> e.authorizesSpecial(stack));
                            require(
                                    rule.mayMove(stack, protect) == mayMove,
                                    "Special protection parity");
                        }
                    }
                    int specificity =
                            !only
                                    ? 0
                                    : allowed.stream().anyMatch(e -> !e.components().isEmpty())
                                            ? 2
                                            : 1;
                    require(rule.specificity() == specificity, "Specificity parity");
                }
        System.out.println("INVENTORY_RULE_PARITY rules=512 stacks=2048 protection=4096");
    }
}
