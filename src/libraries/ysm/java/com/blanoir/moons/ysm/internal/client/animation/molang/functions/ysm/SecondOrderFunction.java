package com.blanoir.moons.ysm.internal.client.animation.molang.functions.ysm;

import com.blanoir.moons.ysm.internal.client.animation.molang.PhysicsManager;
import com.blanoir.moons.ysm.internal.client.animation.molang.functions.physics.IPhysics;
import com.blanoir.moons.ysm.internal.client.animation.molang.functions.physics.SecondOrder;
import com.blanoir.moons.ysm.internal.geckolib3.core.molang.context.IContext;
import com.blanoir.moons.ysm.internal.geckolib3.core.molang.funciton.ContextFunction;
import com.blanoir.moons.ysm.internal.geckolib3.core.molang.util.StringPool;
import com.blanoir.moons.ysm.internal.molang.runtime.ExecutionContext;

public class SecondOrderFunction extends ContextFunction<Object> {
    @Override
    public Object eval(ExecutionContext<IContext<Object>> context, ArgumentCollection arguments) {
        int name = arguments.getStringId(context, 0);
        if (name == StringPool.EMPTY_ID) {
            return 0;
        }
        int physicsKey = PhysicsManager.scopedKey(name);
        float input = arguments.getAsFloat(context, 1);
        int size = arguments.size();
        float frequency = 1.0f;
        float coefficient = 1.0f;
        float response = 1.0f;
        if (size >= 3) {
            frequency = arguments.getAsFloat(context, 2);
        }
        if (size >= 4) {
            coefficient = arguments.getAsFloat(context, 3);
        }
        if (size >= 5) {
            response = arguments.getAsFloat(context, 4);
        }
        PhysicsManager physicsManager = context.entity().geoInstance().getPhysicsManager();
        IPhysics physics = physicsManager.get(physicsKey);
        if (physics == null) {
            physicsManager.put(
                    physicsKey, new SecondOrder(input, frequency, coefficient, response));
            return input;
        }
        physics.setArgs(input, frequency, coefficient, response);
        return physics.getValue();
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size >= 2;
    }
}
