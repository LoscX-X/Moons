package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.world.phys.BlockHitResult;

/** A validated block aim retains the actual hit, including block, face and inside flag. */
public record BlockAim(BlockTarget target, Rotation rotation, BlockHitResult hit) {}
