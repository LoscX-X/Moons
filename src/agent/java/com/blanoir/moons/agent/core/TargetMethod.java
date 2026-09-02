package com.blanoir.moons.agent.core;

import java.util.List;

record TargetMethod(
        String id,
        List<String> classNames,
        List<String> methodNames,
        String descriptor,
        HookKind hook
) {
    boolean matchesClass(String internalName) {
        return classNames.contains(internalName);
    }

    boolean matchesMethod(String name, String methodDescriptor) {
        return methodNames.contains(name) && descriptor.equals(methodDescriptor);
    }

    boolean required() {
        return !id.startsWith("optional.");
    }

    enum HookKind {
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
        RENDERER_CLOSE
        , VOID_HEAD
        , VOID_RETURN
        , PRESENT_BEFORE_GPU_PRESENT
        , FLOAT_RETURN
        , FLOAT_RETURN_ARG
        , FLOAT_HEAD_BOOLEAN_GATE
        , BOOLEAN_RETURN_ARG
        , OBJECT_RETURN
        , OBJECT_ARGUMENT
        , ITEM_STACK_ARGUMENT_5
        , OBJECT_INVOKE_RETURN
        , NAMED_FLOAT_LOCAL
        , SPRINT_DECISIONS
        , YAW_RESULT
        , BOOLEAN_GATE
        , STATIC_BOOLEAN_RETURN_ARG1
        , XRAY_TESSELLATE
        , XRAY_QUAD
        , XRAY_SECTION_QUAD_26_2
        , VOID_START_END_ARG0
        , CHAMS_FRAME
        , SODIUM_RENDER_MODEL
        , SODIUM_PROCESS_QUAD
        , CHAMS_REMAP_SUBMIT_MODEL
        , CHAMS_MARK_ITEM
        , CHAMS_ITEM_RENDER
        , CHAMS_FOIL_BUFFER
        , CHAMS_MARK_ITEM_26_2
        , CHAMS_ITEM_PREPARE_26_2
        , CHAMS_FOIL_BUFFER_26_2
        , HAND_ANIMATION
    }
}
