package com.blanoir.moons.agent.core;

import java.util.List;

public record TargetMethod(
        String id,
        List<String> classNames,
        List<String> methodNames,
        String descriptor,
        HookKind hook,
        java.util.function.BiFunction<org.objectweb.asm.tree.MethodNode, String, Boolean>
                installer) {
    TargetMethod(
            String id,
            List<String> classNames,
            List<String> methodNames,
            String descriptor,
            HookKind hook) {
        this(id, classNames, methodNames, descriptor, hook, null);
    }

    boolean matchesClass(String internalName) {
        return classNames.contains(internalName);
    }

    boolean matchesMethod(String name, String methodDescriptor) {
        return methodNames.contains(name) && descriptor.equals(methodDescriptor);
    }

    boolean required() {
        return !id.startsWith("optional.");
    }

    /** Release-specific implementations belong in version mappings via VERSION_SPECIFIC. */
    public enum HookKind {
        VERSION_SPECIFIC,
        CLIENT_TICK,
        FRAME,
        HUD,
        SCOREBOARD,
        WORLD_RENDER,
        KEY,
        MOUSE_BUTTON,
        MOUSE_SCROLL,
        MOUSE_MOVEMENT,
        ATTACK,
        USE,
        PACKET_SEND,
        PACKET_RECEIVE,
        PACKET_APPLY,
        BLOCK_SET,
        BLOCK_SERVER_SET,
        PLAYER_UPDATE,
        MOVE_INPUT,
        PLAYER_MOVE,
        PLAYER_POSITION,
        RENDER_STATE,
        RENDERER_CLOSE,
        VOID_HEAD,
        VOID_RETURN,
        PRESENT_BEFORE_GPU_PRESENT,
        FLOAT_RETURN,
        FLOAT_RETURN_ARG,
        FLOAT_RETURN_OBJECT_ARG,
        FLOAT_ARGUMENTS,
        FLOAT_HEAD_BOOLEAN_GATE,
        BOOLEAN_RETURN_ARG,
        OBJECT_RETURN,
        OBJECT_ARGUMENT,
        OBJECT_INVOKE_RETURN,
        NAMED_FLOAT_LOCAL,
        SPRINT_DECISIONS,
        YAW_RESULT,
        BOOLEAN_GATE,
        STATIC_BOOLEAN_RETURN_ARG1,
        XRAY_TESSELLATE,
        XRAY_QUAD,
        VOID_START_END_ARG0,
        BOXED_ARGS_VOID_GATE,
        CHAMS_FRAME,
        SODIUM_RENDER_MODEL,
        SODIUM_PROCESS_QUAD,
        CHAMS_REMAP_SUBMIT_MODEL,
        CHAMS_MARK_ITEM,
        CHAMS_ITEM_RENDER,
        CHAMS_FOIL_BUFFER,
        HAND_ANIMATION
    }
}
