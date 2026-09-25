package com.blanoir.moons.client.utils.prediction;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumMap;
import java.util.Set;

/** Uses real vanilla enchantment definitions with headless players and collision fixtures. */
public final class DamagePredictionVerification {
    private static sun.misc.Unsafe unsafe;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        unsafe = (sun.misc.Unsafe) field.get(null);
        verifyReductions(vanillaLookup());
        verifyCountdowns();
        verifyLanding();
        System.out.println("MOONS_DAMAGE_PREDICTION_VERIFIED");
    }

    private static HolderLookup.Provider vanillaLookup() throws ReflectiveOperationException {
        // 26.3 split world and reloadable data registries. Production uses synchronized items.
        java.lang.reflect.Method factory;
        try {
            factory = VanillaRegistries.class.getMethod("createLookup");
        } catch (NoSuchMethodException newerVersion) {
            factory = VanillaRegistries.class.getMethod("createWorldLookup");
        }
        return (HolderLookup.Provider) factory.invoke(null);
    }

    private static void verifyReductions(HolderLookup.Provider lookup) throws Exception {
        var player = (FixturePlayer) unsafe.allocateInstance(FixturePlayer.class);
        player.armor = new EnumMap<>(EquipmentSlot.class);
        player.abilities = new Abilities();
        var explosion =
                new FixtureSource(
                        lookup.lookupOrThrow(Registries.DAMAGE_TYPE)
                                .getOrThrow(DamageTypes.EXPLOSION),
                        Set.of(DamageTypeTags.IS_EXPLOSION));
        var fall =
                new FixtureSource(
                        lookup.lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(DamageTypes.FALL),
                        Set.of(DamageTypeTags.IS_FALL, DamageTypeTags.BYPASSES_ARMOR));
        close(
                DamagePrediction.effectiveDamage(player, explosion, 20),
                8,
                "Diamond armor and toughness");
        close(DamagePrediction.effectiveDamage(player, fall, 20), 20, "Fall damage bypasses armor");
        var enchantments = lookup.lookupOrThrow(Registries.ENCHANTMENT);
        Items.DIAMOND_BOOTS
                .builtInRegistryHolder()
                .bindComponents(
                        DataComponentMap.builder()
                                .set(DataComponents.MAX_STACK_SIZE, 1)
                                .set(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY)
                                .build());
        for (EquipmentSlot slot :
                new EquipmentSlot[] {
                    EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
                }) {
            var stack = new ItemStack(Items.DIAMOND_BOOTS);
            stack.enchant(enchantments.getOrThrow(Enchantments.PROTECTION), 4);
            player.armor.put(slot, stack);
        }
        close(
                DamagePrediction.effectiveDamage(player, explosion, 20),
                2.88F,
                "Protection from all four armor slots");
        player.resistance = new MobEffectInstance(MobEffects.RESISTANCE, 100, 1);
        close(
                DamagePrediction.effectiveDamage(player, explosion, 20),
                1.728F,
                "Resistance II and protection both apply");
        player.armor
                .get(EquipmentSlot.FEET)
                .enchant(enchantments.getOrThrow(Enchantments.FEATHER_FALLING), 4);
        close(
                DamagePrediction.effectiveDamage(player, fall, 20),
                2.4F,
                "Feather falling reaches the capped protection total");
        var bypass =
                new FixtureSource(
                        fall.typeHolder(),
                        Set.of(DamageTypeTags.BYPASSES_ARMOR, DamageTypeTags.BYPASSES_EFFECTS));
        close(
                DamagePrediction.effectiveDamage(player, bypass, 20),
                20,
                "Effect-bypassing damage ignores resistance and enchants");
        player.armor.clear();
        var boots = new ItemStack(Items.DIAMOND_BOOTS);
        boots.enchant(enchantments.getOrThrow(Enchantments.BLAST_PROTECTION), 4);
        player.armor.put(EquipmentSlot.FEET, boots);
        require(
                DamagePrediction.protection(player, explosion) == 8,
                "Blast protection uses its explosion-specific strength");
        require(
                DamagePrediction.protection(player, fall) == 0,
                "Blast protection does not protect falls");
        boots.enchant(enchantments.getOrThrow(Enchantments.FEATHER_FALLING), 4);
        player.armor.clear();
        player.armor.put(EquipmentSlot.MAINHAND, boots);
        require(
                DamagePrediction.protection(player, fall) == 0,
                "Holding enchanted armor does not equip its protection");
        player.abilities.invulnerable = true;
        close(
                DamagePrediction.effectiveDamage(player, explosion, 20),
                0,
                "Invulnerability is respected");
        close(
                DamagePrediction.scaleForDifficulty(20, Difficulty.PEACEFUL),
                0,
                "Peaceful explosion damage");
        close(
                DamagePrediction.scaleForDifficulty(20, Difficulty.EASY),
                11,
                "Easy explosion damage");
        close(
                DamagePrediction.scaleForDifficulty(20, Difficulty.HARD),
                30,
                "Hard explosion damage");
        for (double distance : new double[] {0, 3, 3.99, 4, 4.01, 11, 20, 50}) {
            for (float multiplier : new float[] {0, .2F, .5F, 1}) {
                require(
                        DamagePrediction.rawFallDamage(player, distance, multiplier)
                                == Math.max(0, player.vanillaFallDamage(distance, multiplier)),
                        "Fall rounding matches the actual vanilla LivingEntity calculation");
            }
        }
    }

    private static void verifyCountdowns() throws Exception {
        var tnt = (FixtureTnt) unsafe.allocateInstance(FixtureTnt.class);
        tnt.fuse = 11;
        require(DamagePrediction.explosionTicks(tnt) == 11, "TNT fuse is preserved");
        tnt.fuse = 10;
        require(DamagePrediction.explosionTicks(tnt) == 10, "TNT enters the prediction window");
        var creeper = (FixtureCreeper) unsafe.allocateInstance(FixtureCreeper.class);
        require(
                DamagePrediction.explosionTicks(creeper) == Integer.MAX_VALUE,
                "Calm creepers do not explode");
        creeper.swelling = true;
        require(
                DamagePrediction.explosionTicks(creeper) == 30,
                "Newly swelling creepers are not immediate explosions");
        creeper.progress = 20.0F / 28;
        require(
                DamagePrediction.explosionTicks(creeper) == 10,
                "Creeper countdown reaches the reaction window");
        creeper.swelling = false;
        require(
                DamagePrediction.explosionTicks(creeper) == Integer.MAX_VALUE,
                "Defusing creepers stop being imminent threats");
        var cart = (FixtureCart) unsafe.allocateInstance(FixtureCart.class);
        cart.fuse = -1;
        require(
                DamagePrediction.explosionTicks(cart) == Integer.MAX_VALUE,
                "Unprimed stationary carts do not threaten an explosion");
        cart.fuse = 7;
        require(DamagePrediction.explosionTicks(cart) == 7, "Primed cart fuse");
        cart.fuse = -1;
        cart.horizontalCollision = true;
        require(
                DamagePrediction.explosionTicks(cart) == 0,
                "A fast cart collision can explode without a fuse");
    }

    private static void verifyLanding() {
        AABB body = new AABB(-.3, 10, -.3, .3, 11.8, .3);
        var impact =
                LandingPrediction.fallImpact(
                        body,
                        new Vec3(0, -1, 0),
                        7,
                        10,
                        (box, velocity) ->
                                new Vec3(velocity.x, Math.max(velocity.y, -box.minY), velocity.z),
                        (box, movement) -> false);
        require(
                impact != null && impact.ticks() <= 10,
                "Predict a real floor collision within the window");
        close((float) impact.fallDistance(), 17, "Include the future drop before impact");
        require(
                LandingPrediction.fallImpact(
                                body.move(0, 100, 0),
                                new Vec3(0, -1, 0),
                                7,
                                10,
                                (box, velocity) ->
                                        new Vec3(
                                                velocity.x,
                                                Math.max(velocity.y, -box.minY),
                                                velocity.z),
                                (box, movement) -> false)
                        == null,
                "A distant floor must not cause an early swap");
        require(
                LandingPrediction.fallImpact(
                                body,
                                new Vec3(0, -1, 0),
                                7,
                                impact.ticks() - 1,
                                (box, velocity) ->
                                        new Vec3(
                                                velocity.x,
                                                Math.max(velocity.y, -box.minY),
                                                velocity.z),
                                (box, movement) -> false)
                        == null,
                "Do not leak collisions beyond the configured horizon");
        require(
                LandingPrediction.fallImpact(
                                body,
                                new Vec3(0, -1, 0),
                                7,
                                10,
                                (box, velocity) ->
                                        new Vec3(
                                                velocity.x,
                                                Math.max(velocity.y, -box.minY),
                                                velocity.z),
                                (box, movement) -> box.minY + movement.y < 1)
                        == null,
                "Water before impact cancels the predicted fall");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void close(float actual, float expected, String message) {
        require(Math.abs(actual - expected) < 1E-4, message + ": " + actual + " != " + expected);
    }

    private static final class FixtureSource extends DamageSource {
        private final Set<TagKey<DamageType>> tags;

        FixtureSource(Holder<DamageType> type, Set<TagKey<DamageType>> tags) {
            super(type);
            this.tags = tags;
        }

        @Override
        public boolean is(TagKey<DamageType> tag) {
            return tags.contains(tag);
        }

        @Override
        public boolean scalesWithDifficulty() {
            return false;
        }
    }

    private static final class FixturePlayer extends Player {
        EnumMap<EquipmentSlot, ItemStack> armor;
        Abilities abilities;
        MobEffectInstance resistance;

        private FixturePlayer() {
            super(null, null);
        }

        @Override
        public GameType gameMode() {
            return GameType.SURVIVAL;
        }

        @Override
        public boolean isDeadOrDying() {
            return false;
        }

        @Override
        public Abilities getAbilities() {
            return abilities;
        }

        @Override
        public int getArmorValue() {
            return 20;
        }

        @Override
        public boolean is(TagKey<EntityType<?>> tag) {
            return false;
        }

        @Override
        public double getAttributeValue(Holder<Attribute> attribute) {
            if (attribute == Attributes.ARMOR_TOUGHNESS) return 8;
            if (attribute == Attributes.SAFE_FALL_DISTANCE) return 3;
            if (attribute == Attributes.FALL_DAMAGE_MULTIPLIER) return 1;
            return 0;
        }

        int vanillaFallDamage(double distance, float multiplier) {
            return super.calculateFallDamage(distance, multiplier);
        }

        @Override
        public ItemStack getItemBySlot(EquipmentSlot slot) {
            return armor.getOrDefault(slot, ItemStack.EMPTY);
        }

        @Override
        public MobEffectInstance getEffect(Holder<MobEffect> effect) {
            return effect == MobEffects.RESISTANCE ? resistance : null;
        }
    }

    private static final class FixtureTnt extends PrimedTnt {
        int fuse;

        private FixtureTnt() {
            super(null, null);
        }

        @Override
        public int getFuse() {
            return fuse;
        }
    }

    private static final class FixtureCreeper extends Creeper {
        boolean swelling;
        float progress;

        private FixtureCreeper() {
            super(null, null);
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public boolean isIgnited() {
            return false;
        }

        @Override
        public int getSwellDir() {
            return swelling ? 1 : -1;
        }

        @Override
        public float getSwelling(float partialTick) {
            return progress;
        }
    }

    private static final class FixtureCart extends MinecartTNT {
        int fuse;

        private FixtureCart() {
            super(null, null);
        }

        @Override
        public int getFuse() {
            return fuse;
        }

        @Override
        public boolean isPrimed() {
            return fuse >= 0;
        }

        @Override
        public Vec3 getDeltaMovement() {
            return new Vec3(.2, 0, 0);
        }
    }
}
