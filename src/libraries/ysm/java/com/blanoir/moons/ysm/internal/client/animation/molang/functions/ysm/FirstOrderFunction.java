package com.blanoir.moons.ysm.internal.client.animation.molang.functions.ysm;

import com.blanoir.moons.ysm.internal.client.animation.molang.PhysicsManager;
import com.blanoir.moons.ysm.internal.client.animation.molang.functions.physics.FirstOrder;
import com.blanoir.moons.ysm.internal.client.animation.molang.functions.physics.IPhysics;
import com.blanoir.moons.ysm.internal.geckolib3.core.molang.context.IContext;
import com.blanoir.moons.ysm.internal.geckolib3.core.molang.funciton.ContextFunction;
import com.blanoir.moons.ysm.internal.geckolib3.core.molang.util.StringPool;
import com.blanoir.moons.ysm.internal.molang.runtime.ExecutionContext;

public class FirstOrderFunction extends ContextFunction<Object> {
    @Override
    public Object eval(ExecutionContext<IContext<Object>> context, ArgumentCollection arguments) {
        int name = arguments.getStringId(context, 0);
        if (name == StringPool.EMPTY_ID) {
            return 0;
        }
        float input = arguments.getAsFloat(context, 1);
        float response = 1.0f;
        if (arguments.size() >= 3) {
            response = arguments.getAsFloat(context, 2);
        }
        PhysicsManager physicsManager = context.entity().geoInstance().getPhysicsManager();
        IPhysics physics = physicsManager.get(name);
        if (physics == null) {
            physicsManager.put(name, new FirstOrder(input, response));
            return input;
        }
        physics.setArgs(input, response, 0.0f, 0.0f);
        return physics.getValue();
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size >= 2;
    }
}
