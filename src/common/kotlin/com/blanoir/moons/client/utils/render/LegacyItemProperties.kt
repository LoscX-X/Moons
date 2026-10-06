package com.blanoir.moons.client.utils.render

import net.minecraft.item.ItemStack

/** 1.8 inventories represent empty slots with null. */
internal val ItemStack?.isEmpty: Boolean
    get() = this == null || stackSize <= 0 || item == null
