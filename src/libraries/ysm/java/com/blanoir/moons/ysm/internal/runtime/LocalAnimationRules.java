package com.blanoir.moons.ysm.internal.runtime;

import com.blanoir.moons.ysm.internal.geckolib3.core.builder.ILoopType;
import com.blanoir.moons.ysm.internal.geckolib3.core.controller.PredicateBasedController;
import com.blanoir.moons.ysm.internal.geckolib3.core.enums.PlayState;

import java.util.*;

/** Portable counterpart of upstream ConditionHold/Use/Armor/Vehicle and their predicates. */
final class LocalAnimationRules {
    private final LocalRuntime runtime;
    private final int format;
    private final Map<String, Double> previousTicks = new HashMap<>();
    private final Map<String, String> previousItems = new HashMap<>();

    LocalAnimationRules(LocalRuntime runtime, int format) {
        this.runtime = runtime;
        this.format = format;
    }

    void reset() {
        previousTicks.clear();
        previousItems.clear();
    }

    String tagged(String prefix, String observation) {
        if (runtime.observation(observation) instanceof Collection<?> tags)
            for (String name : runtime.animationNames())
                if (name.startsWith(prefix + "#")
                        && tags.contains(name.substring(prefix.length() + 1))) return name;
        return null;
    }

    String armor(String slot) {
        if (!runtime.flag("has_" + slot)) return null;
        String id = runtime.choose(slot + "$" + runtime.text(slot + "_item"));
        if (id != null) return id;
        String tag = tagged(slot, slot + "_tags");
        return tag != null ? tag : runtime.choose(slot + ":default");
    }

    String passenger() {
        String specific = runtime.choose("passenger$" + runtime.text("passenger_id"));
        return specific != null ? specific : tagged("passenger", "passenger_tags");
    }

    String vehicle() {
        if (!runtime.flag("is_riding")) return null;
        String specific = runtime.choose("vehicle$" + runtime.text("vehicle_id"));
        if (specific != null) return specific;
        if (runtime.flag("vehicle_is_boat")) {
            specific =
                    runtime.choose(
                            runtime.flag("boat_is_chest") ? "vehicle$minecraft:chest_boat" : "",
                            "vehicle$minecraft:boat");
            if (specific != null) return specific;
        }
        specific = tagged("vehicle", "vehicle_tags");
        if (specific != null) return specific;
        return runtime.choose(
                runtime.flag("vehicle_is_boat")
                        ? "boat"
                        : runtime.text("vehicle_id").equals("minecraft:pig")
                                ? "ride_pig"
                                : runtime.text("vehicle_id").equals("minecraft:happy_ghast")
                                        ? "sit"
                                        : runtime.flag("vehicle_is_living")
                                                        || runtime.flag("vehicle_is_minecart")
                                                ? "ride"
                                                : "sit");
    }

    String held(String operation, String hand) {
        if (!hand.equals("offhand")) hand = "mainhand";
        String arm =
                operation.equals("use") && runtime.flag("left_handed")
                        ? (hand.equals("mainhand") ? "offhand" : "mainhand")
                        : hand;
        String prefix =
                operation.equals("swing") && arm.equals("mainhand")
                        ? "swing"
                        : operation + "_" + arm;
        String category = runtime.text(hand + "_category");
        String item = runtime.text(hand + "_item");
        String use = runtime.text(hand + "_use");
        if (operation.equals("use") && runtime.flag("is_sleeping")) return null;
        if (operation.equals("hold")) {
            if (category.equals("empty") || item.equals("minecraft:air"))
                return runtime.choose(prefix + ":empty");
            if (runtime.flag(hand + "_charged_crossbow"))
                return runtime.choose(prefix + ":charged_crossbow");
            if (hand.equals("mainhand")
                    && runtime.flag("is_fishing")
                    && category.equals("fishing_rod")) return runtime.choose(prefix + ":fishing");
        }
        String legacy =
                category.equals("trident") ? "spear" : category.equals("shovel") ? "spade" : "";
        String classified =
                runtime.choose(
                        prefix + ":" + category, legacy.isEmpty() ? "" : prefix + ":" + legacy);
        boolean weapon = List.of("trident", "lance", "mace").contains(category);
        if (!operation.equals("use") && weapon && classified != null) return classified;
        String identified = runtime.choose(prefix + "$" + item);
        if (identified != null) return identified;
        String tagged = tagged(prefix, hand + "_tags");
        if (tagged != null) return tagged;
        if (operation.equals("use") && category.equals("lance")) return charge();
        if (classified != null) return classified;
        String useName =
                use.equals("trident")
                        ? runtime.choose(prefix + ":trident", prefix + ":spear")
                        : use.equals("spear")
                                ? runtime.choose(prefix + ":lance")
                                : runtime.choose(use.equals("none") ? "" : prefix + ":" + use);
        if (useName != null) return useName;
        return operation.equals("hold")
                ? null
                : runtime.choose(
                        prefix,
                        operation.equals("swing")
                                ? (hand.equals("mainhand") ? "swing_hand" : "swing_offhand")
                                : "");
    }

    String charge() {
        return runtime.choose(
                runtime.flag("lance_riding_charge")
                        ? "lance_riding_charge"
                        : runtime.flag("lance_fall_flying") ? "lance_fall_flying_charge" : "",
                "lance_charge",
                "use_mainhand:lance");
    }

    String firstPersonLance() {
        if (!runtime.flag("weapon_is_spear") && !runtime.flag("weapon_is_lance")) return null;
        if (runtime.flag("lance_using")
                || runtime.flag("lance_charging")
                || runtime.flag("lance_riding_charge")) return charge();
        if (runtime.flag("lance_lunging") || runtime.flag("lance_jabbing"))
            return runtime.choose(
                    runtime.flag("lance_lunging") ? "lance_lunge" : "", "lance_jab", "swing:lance");
        if (!runtime.flag("lance_holding")) return null;
        return runtime.choose(
                runtime.flag("lance_riding") ? "lance_riding_idle" : "",
                "lance_stand",
                "hold_mainhand:lance");
    }

    PlayState apply(
            String domain, String slot, String selected, PredicateBasedController<?> controller) {
        if (domain.equals("player") && slot.startsWith("hold_")) {
            String hand = slot.substring(5), item = runtime.text(hand + "_item");
            String previous = previousItems.put(hand, item);
            if (!Objects.equals(previous, item)) controller.stopTransition();
            if ((runtime.flag("is_swinging") && runtime.text("swinging_hand").equals(hand))
                    || (runtime.flag("is_using_item") && runtime.text("using_hand").equals(hand)))
                return PlayState.PAUSE;
        }
        boolean swing =
                slot.equals("swing")
                        || slot.equals("lance")
                                && (runtime.flag("lance_jabbing") || runtime.flag("lance_lunging"));
        boolean use =
                slot.equals("use")
                        || slot.equals("lance")
                                && (runtime.flag("lance_using") || runtime.flag("lance_charging"));
        if (swing || use) {
            double tick = runtime.number(swing ? "swing_time" : "item_in_use_duration");
            Double previous = previousTicks.put(slot, tick);
            if (selected != null && (previous == null || tick < previous))
                controller.stopTransition();
        } else previousTicks.remove(slot);
        if (selected == null) return PlayState.STOP;
        ILoopType loop = null;
        if (slot.equals("main")
                || slot.equals("vehicle")
                || slot.equals("passenger")
                || slot.startsWith("armor_")
                || slot.equals("lance"))
            loop =
                    (slot.equals("main") && List.of("death", "attacked").contains(selected))
                                    || slot.equals("lance") && swing
                            ? ILoopType.EDefaultLoopTypes.PLAY_ONCE
                            : ILoopType.EDefaultLoopTypes.LOOP;
        else if (format < 19 && (slot.startsWith("hold_") || swing || use))
            loop =
                    swing
                            ? ILoopType.EDefaultLoopTypes.PLAY_ONCE
                            : selected.endsWith(":lance") && slot.startsWith("hold_")
                                    ? ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME
                                    : ILoopType.EDefaultLoopTypes.LOOP;
        controller.setAnimation(selected, loop);
        if ((swing || use) && runtime.flag("weapon_is_spear")) {
            var animation = runtime.getAnimation(selected);
            controller.setAnimationTickOverride(
                    (float)
                            (swing
                                    ? runtime.number("lance_attack_ticks")
                                    : runtime.number("lance_charge_progress")
                                            * animation.animationLength));
        }
        return PlayState.CONTINUE;
    }
}
