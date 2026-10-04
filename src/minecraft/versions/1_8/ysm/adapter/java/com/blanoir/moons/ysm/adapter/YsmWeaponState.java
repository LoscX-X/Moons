package com.blanoir.moons.ysm.adapter;

import com.blanoir.moons.ysm.internal.core.api.item.WeaponActionState;

import net.minecraft.entity.EntityLivingBase;

/** The portable fields describe tridents, maces and lances, none of which exist in 1.8.9. */
public final class YsmWeaponState {
    private YsmWeaponState() {}

    public static WeaponActionState get(EntityLivingBase entity, float partialTick) {
        return WeaponActionState.EMPTY;
    }
}
