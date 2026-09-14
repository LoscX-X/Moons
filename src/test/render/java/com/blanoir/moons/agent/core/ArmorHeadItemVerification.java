package com.blanoir.moons.agent.core;

import com.blanoir.moons.api.bridge.AgentBridge;
import com.blanoir.moons.api.bridge.RuntimeBridge;
import com.blanoir.moons.client.module.impl.misc.ArmorHide;
import com.blanoir.moons.features.FeatureHooks;
import com.blanoir.moons.runtime.RuntimeEvents;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.level.Level;

import java.lang.reflect.Field;
import java.util.List;

/** Exercises the real feature handler and transformed item resolver without a game window. */
public final class ArmorHeadItemVerification {
    private static final String HOOK = "render.armor-hide.head-item";

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // A real client binds item components while loading registry data from its world/server.
        Items.COAL.builtInRegistryHolder().bindComponents(
                DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build());
        // Only the fields read by this render policy are needed; no graphics or world is created.
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        var client = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        var player = (LocalPlayer) unsafe.allocateInstance(LocalPlayer.class);
        var other = (LocalPlayer) unsafe.allocateInstance(LocalPlayer.class);
        Field singleton = Minecraft.class.getDeclaredField("instance");
        singleton.setAccessible(true);
        Object previousClient = singleton.get(null);
        client.player = player;
        singleton.set(null, client);
        var equipment = new EntityEquipment();
        Field equipmentField = LivingEntity.class.getDeclaredField("equipment");
        equipmentField.setAccessible(true);
        equipmentField.set(player, equipment);
        var coal = new ItemStack(Items.COAL);
        coal.set(DataComponents.CUSTOM_MODEL_DATA,
                new CustomModelData(List.of(17f), List.of(), List.of("helmet"), List.of()));
        equipment.set(EquipmentSlot.HEAD, coal);
        RuntimeBridge bridge = new RuntimeBridge() {
            @Override
            public boolean isHookActive(String id) {
                return FeatureHooks.isActive(id);
            }

            @Override
            public boolean onBooleanValue(String id, Object owner, Object argument, boolean value) {
                var event = new RuntimeEvents.MethodHook(id, owner, argument, value);
                FeatureHooks.apply(event);
                return (Boolean) event.value();
            }
        };
        RuntimeBridge previousBridge = AgentBridge.install(bridge);
        try {
            ArmorHide.endAvatar();
            ArmorHide.setEnabled(null, false);
            check(player, coal, ItemDisplayContext.HEAD, true, "disabled head item");
            ArmorHide.setEnabled(null, true);
            check(player, coal, ItemDisplayContext.HEAD, false, "custom-model coal in head slot");
            check(player, coal.copy(), ItemDisplayContext.HEAD, false, "copied head item");
            check(other, coal, ItemDisplayContext.HEAD, true, "other player");
            check(player, new ItemStack(Items.COAL), ItemDisplayContext.HEAD, true, "different components");
            for (var context : ItemDisplayContext.values()) {
                if (context != ItemDisplayContext.HEAD)
                    check(player, coal, context, true, "non-head context " + context);
            }
            verifyInjectedResolver(unsafe, player, coal);
            if (player.getItemBySlot(EquipmentSlot.HEAD) != coal
                    || coal.get(DataComponents.CUSTOM_MODEL_DATA).floats().getFirst() != 17f)
                throw new AssertionError("Rendering changed equipped item/components");
            ArmorHide.setEnabled(null, false);
            check(player, coal, ItemDisplayContext.HEAD, true, "toggle restored head item");
            System.out.println("ARMOR_HEAD_ITEM_VERIFIED custom-model-coal=hidden stale-state=cleared"
                    + " hands+gui+other-players=preserved injected-resolver=executed toggle=restored");
        } finally {
            AgentBridge.install(previousBridge);
            singleton.set(null, previousClient);
        }
    }

    private static void check(
            LocalPlayer player, ItemStack stack, ItemDisplayContext context, boolean expected,
            String description) {
        var state = new ItemStackRenderState();
        state.newLayer();
        var event = new RuntimeEvents.MethodHook(HOOK, null,
                new Object[] {state, stack, context, null, player, 0}, true);
        FeatureHooks.apply(event);
        if (!Boolean.valueOf(expected).equals(event.value()) || state.isEmpty() == expected)
            throw new AssertionError(description + ": gate=" + event.value() + ", empty=" + state.isEmpty());
    }

    private static void verifyInjectedResolver(
            sun.misc.Unsafe unsafe, LocalPlayer player, ItemStack stack) throws Exception {
        String name = ItemModelResolver.class.getName();
        byte[] original;
        try (var input = ItemModelResolver.class.getResourceAsStream("ItemModelResolver.class")) {
            original = input.readAllBytes();
        }
        var transformer = new MoonsTransformer(VersionMappings.create());
        byte[] transformed = transformer.transform(
                ItemModelResolver.class.getClassLoader(), name.replace('.', '/'), original);
        if (transformed == null || !transformer.installedHooks().contains(HOOK))
            throw new AssertionError("Head-item resolver hook was not installed");
        Class<?> resolver = new ClassLoader(ItemModelResolver.class.getClassLoader()) {
            Class<?> define() { return defineClass(name, transformed, 0, transformed.length); }
        }.define();
        var state = new ItemStackRenderState();
        state.newLayer();
        resolver.getMethod("updateForTopItem", ItemStackRenderState.class, ItemStack.class,
                ItemDisplayContext.class, Level.class, ItemOwner.class, int.class)
                .invoke(unsafe.allocateInstance(resolver), state, stack, ItemDisplayContext.HEAD,
                        null, player, 0);
        if (!state.isEmpty()) throw new AssertionError("Injected resolver retained the visible item");
    }
}
