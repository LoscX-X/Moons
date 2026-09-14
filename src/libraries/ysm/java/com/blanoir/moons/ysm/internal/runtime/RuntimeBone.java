package com.blanoir.moons.ysm.internal.runtime;

import com.blanoir.moons.ysm.internal.geckolib3.core.molang.util.StringPool;
import com.blanoir.moons.ysm.internal.geckolib3.core.processor.IBone;
import com.blanoir.moons.ysm.internal.resource.pojo.RawYsmModel.RawBone;

import org.joml.Vector3f;

public final class RuntimeBone implements IBone {
    public final RawBone raw;
    public final Vector3f rotation,
            position = new Vector3f(),
            scale = new Vector3f(1),
            absolutePivot = new Vector3f();
    private final Vector3f initial;
    private boolean hidden, childrenHidden, tracking;

    public RuntimeBone(RawBone raw) {
        this.raw = raw;
        initial = new Vector3f(raw.rotation);
        rotation = new Vector3f(initial);
    }

    public String getName() {
        return raw.name;
    }

    public int getBoneId() {
        return StringPool.computeIfAbsent(raw.name);
    }

    public Vector3f getInitialRotation() {
        return initial;
    }

    public boolean isHidden() {
        return hidden;
    }

    public void setHidden(boolean v) {
        hidden = v;
    }

    public boolean childBonesAreHiddenToo() {
        return childrenHidden;
    }

    public void setHidden(boolean v, boolean children) {
        hidden = v;
        childrenHidden = children;
    }

    public boolean isTrackingXform() {
        return tracking;
    }

    public void setTrackXform(boolean v) {
        tracking = v;
    }

    public float getRotationX() {
        return rotation.x;
    }

    public void setRotationX(float v) {
        rotation.x = v;
    }

    public float getRotationY() {
        return rotation.y;
    }

    public void setRotationY(float v) {
        rotation.y = v;
    }

    public float getRotationZ() {
        return rotation.z;
    }

    public void setRotationZ(float v) {
        rotation.z = v;
    }

    public float getPositionX() {
        return position.x;
    }

    public void setPositionX(float v) {
        position.x = v;
    }

    public float getPositionY() {
        return position.y;
    }

    public void setPositionY(float v) {
        position.y = v;
    }

    public float getPositionZ() {
        return position.z;
    }

    public void setPositionZ(float v) {
        position.z = v;
    }

    public float getScaleX() {
        return scale.x;
    }

    public void setScaleX(float v) {
        scale.x = v;
    }

    public float getScaleY() {
        return scale.y;
    }

    public void setScaleY(float v) {
        scale.y = v;
    }

    public float getScaleZ() {
        return scale.z;
    }

    public void setScaleZ(float v) {
        scale.z = v;
    }

    public float getPivotX() {
        return raw.pivot[0];
    }

    public float getPivotAbsX() {
        return absolutePivot.x;
    }

    public float getPivotY() {
        return raw.pivot[1];
    }

    public float getPivotAbsY() {
        return absolutePivot.y;
    }

    public float getPivotZ() {
        return raw.pivot[2];
    }

    public float getPivotAbsZ() {
        return absolutePivot.z;
    }
}
