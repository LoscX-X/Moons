package com.blanoir.moons.ysm.internal.geckolib3.geo.animated;

import com.blanoir.moons.ysm.internal.geckolib3.core.processor.IBone;

import it.unimi.dsi.fastutil.ints.*;

import java.util.List;

/** Bone table consumed by the upstream processor. Geometry is owned by the portable mesh baker. */
public final class AnimatedGeoModel {
    private final Int2ReferenceMap<IBone> bones = new Int2ReferenceLinkedOpenHashMap<>();
    private final int root;

    public AnimatedGeoModel(List<? extends IBone> source) {
        for (IBone bone : source) bones.put(bone.getBoneId(), bone);
        root = source.isEmpty() ? -1 : source.getFirst().getBoneId();
    }

    public Int2ReferenceMap<IBone> bones() {
        return bones;
    }

    public int rootBoneId() {
        return root;
    }
}
