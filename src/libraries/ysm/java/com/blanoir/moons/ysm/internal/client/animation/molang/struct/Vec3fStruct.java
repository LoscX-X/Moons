package com.blanoir.moons.ysm.internal.client.animation.molang.struct;

import com.blanoir.moons.ysm.internal.geckolib3.core.molang.util.StringPool;
import com.blanoir.moons.ysm.internal.molang.runtime.Struct;

public abstract class Vec3fStruct implements Struct {

    private static final int NAME_X = StringPool.computeIfAbsent("x");

    private static final int NAME_Y = StringPool.computeIfAbsent("y");

    private static final int NAME_Z = StringPool.computeIfAbsent("z");

    @Override
    public Object getProperty(int i) {
        if (i == NAME_X) {
            return getX();
        }
        if (i == NAME_Y) {
            return getY();
        }
        if (i == NAME_Z) {
            return getZ();
        }
        return null;
    }

    public abstract float getX();

    public abstract float getY();

    public abstract float getZ();

    @Override
    public void putProperty(int name, Object value) {}

    @Override
    public String toString() {
        return String.format("vec3{x=%.2f, y=%.2f, z=%.2f}", getX(), getY(), getZ());
    }

    @Override
    public Struct copy() {
        return this;
    }
}
