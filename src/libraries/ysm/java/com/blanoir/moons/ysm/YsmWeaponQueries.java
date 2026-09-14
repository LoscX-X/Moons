package com.blanoir.moons.ysm;

import com.blanoir.moons.ysm.internal.core.api.item.*;

import java.util.Map;

/** Upstream weapon-state Molang observations, independent of the game host. */
public final class YsmWeaponQueries {
    public static void write(Map<String, Object> result, WeaponActionState state) {
        result.put("weapon_type", getWeaponType(state));
        result.put("weapon_is_trident", state.kind() == WeaponKind.TRIDENT);
        result.put("weapon_is_lance", state.kind() == WeaponKind.LANCE);
        result.put("weapon_is_spear", state.kind() == WeaponKind.SPEAR);
        result.put("weapon_is_mace", state.kind() == WeaponKind.MACE);
        result.put("weapon_attacking", isWeaponAttacking(state));
        result.put("weapon_using", isWeaponUsing(state));
        result.put("weapon_riding", isWeaponRiding(state));
        result.put("weapon_fall_flying", isWeaponFallFlying(state));
        result.put("weapon_speed", state.speed());
        result.put("weapon_attack_ticks", getWeaponAttackTicks(state));
        result.put("weapon_use_ticks", getWeaponUseTicks(state));
        result.put("trident_holding", state.trident().holding());
        result.put("trident_using", state.trident().using());
        result.put("trident_throwing", state.trident().throwing());
        result.put("trident_riptide", state.trident().riptide());
        result.put("trident_attack", state.trident().attacking());
        result.put("trident_use_ticks", state.trident().useTicks());
        result.put("trident_attack_ticks", state.trident().attackTicks());
        result.put("lance_holding", state.lance().holding());
        result.put("lance_using", state.lance().using());
        result.put("lance_charging", state.lance().charging());
        result.put("lance_jabbing", state.lance().jabbing());
        result.put("lance_lunging", state.lance().lunging());
        result.put("lance_riding", state.lance().riding());
        result.put("lance_riding_charge", state.lance().ridingCharge());
        result.put("lance_fall_flying", state.lance().fallFlying());
        result.put("lance_use_ticks", state.lance().useTicks());
        result.put("lance_attack_ticks", state.lance().attackTicks());
        result.put("lance_speed", state.lance().speed());
        result.put("lance_charge_progress", state.lance().chargeProgress());
        result.put("mace_holding", state.mace().holding());
        result.put("mace_falling", state.mace().falling());
        result.put("mace_can_smash", state.mace().canSmash());
        result.put("mace_smashing", state.mace().smashing());
        result.put("mace_wind_bursting", state.mace().windBursting());
        result.put("mace_attacking", state.mace().attacking());
        result.put("mace_riding", state.mace().riding());
        result.put("mace_fall_flying", state.mace().fallFlying());
        result.put("mace_fall_distance", state.mace().fallDistance());
        result.put("mace_vertical_speed", state.mace().verticalSpeed());
        result.put("mace_attack_ticks", state.mace().attackTicks());
        result.put("mace_smash_progress", state.mace().smashProgress());
    }

    private static int getWeaponType(WeaponActionState state) {
        return switch (state.kind()) {
            case TRIDENT -> 1;
            case LANCE -> 2;
            case MACE -> 3;
            case SPEAR -> 4;
            case NONE -> 0;
        };
    }

    private static boolean isWeaponAttacking(WeaponActionState state) {
        return state.trident().attacking()
                || state.lance().jabbing()
                || state.lance().lunging()
                || state.mace().attacking();
    }

    private static boolean isWeaponUsing(WeaponActionState state) {
        return state.trident().using() || state.lance().using();
    }

    private static boolean isWeaponRiding(WeaponActionState state) {
        return state.lance().riding() || state.mace().riding();
    }

    private static boolean isWeaponFallFlying(WeaponActionState state) {
        return state.lance().fallFlying() || state.mace().fallFlying();
    }

    private static float getWeaponAttackTicks(WeaponActionState state) {
        TridentActionState trident = state.trident();
        LanceActionState lance = state.lance();
        MaceActionState mace = state.mace();
        return Math.max(trident.attackTicks(), Math.max(lance.attackTicks(), mace.attackTicks()));
    }

    private static float getWeaponUseTicks(WeaponActionState state) {
        return Math.max(state.trident().useTicks(), state.lance().useTicks());
    }
}
