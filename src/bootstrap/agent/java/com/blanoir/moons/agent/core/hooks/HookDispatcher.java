package com.blanoir.moons.agent.core.hooks;

import static com.blanoir.moons.agent.core.hooks.HookInstructions.BRIDGE;
import static com.blanoir.moons.agent.core.hooks.HookInstructions.containsIdentifiedHook;

import com.blanoir.moons.agent.core.TargetMethod;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

/** Selects hook installers, detects existing hooks and declares frame requirements. */
public final class HookDispatcher {
    private HookDispatcher() {}

    public static boolean install(MethodNode method, TargetMethod target) {
        return switch (target.hook()) {
            case VERSION_SPECIFIC -> {
                boolean loaded = target.installer().apply(method, target.id());
                if (loaded) {
                    InsnList marker = new InsnList();
                    marker.add(new LdcInsnNode("moons.hook:" + target.id()));
                    marker.add(new InsnNode(Opcodes.POP));
                    method.instructions.insert(marker);
                }
                yield loaded;
            }
            case CLIENT_TICK -> ClientHooks.loadClientTick(method);
            case FRAME -> ClientHooks.loadFrame(method);
            case HUD -> RenderHooks.loadHud(method);
            case SCOREBOARD -> RenderHooks.loadScoreboard(method, target.id());
            case WORLD_RENDER -> RenderHooks.loadWorldRender(method);
            case KEY -> ClientHooks.loadKey(method);
            case MOUSE_BUTTON -> ClientHooks.loadMouseButton(method);
            case MOUSE_SCROLL -> ClientHooks.loadMouseScroll(method);
            case MOUSE_MOVEMENT ->
                    ValueHooks.loadSimpleStartEnd(method, "onMouseMoveStart", "onMouseMoveEnd");
            case ATTACK -> ClientHooks.loadBooleanAction(method, "onAttack", "onAttackEnd");
            case USE -> ClientHooks.loadVoidAction(method, "onUse", "onUseEnd");
            case PACKET_SEND -> WorldHooks.loadPacketSend(method);
            case PACKET_RECEIVE -> WorldHooks.loadPacketReceive(method);
            case PACKET_APPLY -> WorldHooks.loadPacketApply(method);
            case BLOCK_SET -> WorldHooks.loadBlockSet(method);
            case BLOCK_SERVER_SET -> WorldHooks.loadBlockServerSet(method);
            case PLAYER_UPDATE -> PlayerHooks.loadPlayerUpdate(method);
            case MOVE_INPUT -> PlayerHooks.loadMoveInput(method);
            case PLAYER_MOVE -> PlayerHooks.loadPlayerMove(method);
            case PLAYER_POSITION ->
                    ValueHooks.loadSimpleStartEnd(
                            method, "onPlayerPositionStart", "onPlayerPositionEnd");
            case RENDER_STATE -> RenderHooks.loadRenderState(method);
            case RENDERER_CLOSE -> RenderHooks.loadRendererClose(method);
            case VOID_HEAD -> ValueHooks.loadVoidHook(method, target.id(), false);
            case VOID_RETURN -> ValueHooks.loadVoidHook(method, target.id(), true);
            case PRESENT_BEFORE_GPU_PRESENT ->
                    RenderHooks.loadBeforeGpuPresent(method, target.id());
            case FLOAT_RETURN -> ValueHooks.loadFloatReturn(method, target.id(), false);
            case FLOAT_RETURN_ARG -> ValueHooks.loadFloatReturn(method, target.id());
            case FLOAT_RETURN_OBJECT_ARG -> ValueHooks.loadFloatObjectReturn(method, target.id());
            case FLOAT_HEAD_BOOLEAN_GATE ->
                    ValueHooks.loadFloatHeadBooleanGate(method, target.id());
            case BOOLEAN_RETURN_ARG -> ValueHooks.loadBooleanReturn(method, target.id());
            case OBJECT_RETURN -> ValueHooks.loadObjectReturn(method, target.id());
            case OBJECT_ARGUMENT -> ValueHooks.loadObjectArgument(method, target.id());
            case ITEM_STACK_ARGUMENT_5 -> RenderHooks.loadItemStackArgument5(method, target.id());
            case TRIM_RENDER -> RenderHooks.loadTrimRender(method, target.id());
            case OBJECT_INVOKE_RETURN ->
                    RenderHooks.loadObjectInvocationReturn(method, target.id());
            case NAMED_FLOAT_LOCAL ->
                    ValueHooks.loadNamedFloatLocal(method, target.id(), "brightnessOption");
            case SPRINT_DECISIONS -> PlayerHooks.loadSprintDecisions(method);
            case YAW_RESULT -> PlayerHooks.loadYawResult(method, target.id());
            case BOOLEAN_GATE -> ValueHooks.loadBooleanGate(method, target.id());
            case STATIC_BOOLEAN_RETURN_ARG1 ->
                    ValueHooks.loadStaticBooleanReturn(method, target.id());
            case XRAY_TESSELLATE -> XrayHooks.loadXrayTessellate(method, target.id());
            case XRAY_QUAD -> XrayHooks.loadXrayQuad(method, target.id());
            case VOID_START_END_ARG0 -> ValueHooks.loadVoidStartEndArgument(method, target.id());
            case BOXED_ARGS_VOID_GATE -> ValueHooks.loadBoxedArgumentsGate(method, target.id());
            case CHAMS_FRAME -> ChamsHooks.loadChamsFrame(method, target.id());
            case SODIUM_RENDER_MODEL -> XrayHooks.loadSodiumRenderModel(method, target.id());
            case SODIUM_PROCESS_QUAD -> XrayHooks.loadSodiumProcessQuad(method, target.id());
            case CHAMS_REMAP_SUBMIT_MODEL ->
                    ValueHooks.loadInvocationArgumentRemap(method, target.id(), "submitModel", 3);
            case CHAMS_MARK_ITEM -> ValueHooks.loadVoidHook(method, target.id(), true);
            case CHAMS_ITEM_RENDER -> ChamsHooks.loadChamsItemRender(method, target.id());
            case CHAMS_FOIL_BUFFER ->
                    ValueHooks.loadInvocationArgumentRemap(
                            method, target.id() + ".type", "getBuffer", 0);
            case HAND_ANIMATION -> PlayerHooks.loadHandAnimation(method, target.id());
        };
    }

    public static boolean contains(MethodNode method, TargetMethod target) {
        if (target.hook() == TargetMethod.HookKind.VERSION_SPECIFIC) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof LdcInsnNode constant
                        && ("moons.hook:" + target.id()).equals(constant.cst)) return true;
            }
            return false;
        }
        if (target.hook() == TargetMethod.HookKind.ITEM_STACK_ARGUMENT_5) {
            return containsIdentifiedHook(method, target.id(), "onObjectValue");
        }
        if (target.hook() == TargetMethod.HookKind.TRIM_RENDER
                || target.hook() == TargetMethod.HookKind.BOXED_ARGS_VOID_GATE) {
            return containsIdentifiedHook(method, target.id(), "onBooleanValue");
        }
        String hookName =
                switch (target.hook()) {
                    case VERSION_SPECIFIC -> throw new AssertionError("Handled above");
                    case CLIENT_TICK -> "onClientTickStart";
                    case FRAME -> "onFrame";
                    case HUD -> "onHudRender";
                    case SCOREBOARD -> "onBooleanValue";
                    case WORLD_RENDER -> "onWorldRender";
                    case KEY -> "onKey";
                    case MOUSE_BUTTON -> "onMouse";
                    case MOUSE_SCROLL -> "onMouseScroll";
                    case MOUSE_MOVEMENT -> "onMouseMoveStart";
                    case ATTACK -> "onAttack";
                    case USE -> "onUse";
                    case PACKET_SEND -> "onPacketSend";
                    case PACKET_RECEIVE -> "onPacketReceive";
                    case PACKET_APPLY -> "onPacketApply";
                    case BLOCK_SET, BLOCK_SERVER_SET -> "onBlockUpdate";
                    case PLAYER_UPDATE -> "onPlayerUpdate";
                    case MOVE_INPUT -> "onMoveInput";
                    case PLAYER_MOVE -> "onPlayerMove";
                    case PLAYER_POSITION -> "onPlayerPositionStart";
                    case RENDER_STATE -> "onRenderState";
                    case RENDERER_CLOSE -> "onRendererClose";
                    case VOID_HEAD, VOID_RETURN, PRESENT_BEFORE_GPU_PRESENT -> "onVoidHook";
                    case FLOAT_RETURN, FLOAT_RETURN_ARG -> "onFloatValue";
                    case FLOAT_HEAD_BOOLEAN_GATE -> "onBooleanValue";
                    case BOOLEAN_RETURN_ARG -> "onBooleanValue";
                    case OBJECT_RETURN,
                            FLOAT_RETURN_OBJECT_ARG,
                            OBJECT_ARGUMENT,
                            ITEM_STACK_ARGUMENT_5,
                            OBJECT_INVOKE_RETURN ->
                            "onObjectValue";
                    case NAMED_FLOAT_LOCAL -> "onFloatValue";
                    case SPRINT_DECISIONS -> "onBooleanValue";
                    case YAW_RESULT -> "onFloatValue";
                    case BOOLEAN_GATE, STATIC_BOOLEAN_RETURN_ARG1, XRAY_TESSELLATE, TRIM_RENDER ->
                            "onBooleanValue";
                    case XRAY_QUAD -> "onVoidHook";
                    case VOID_START_END_ARG0 -> "onVoidHook";
                    case BOXED_ARGS_VOID_GATE -> "onBooleanValue";
                    case CHAMS_FRAME -> "onVoidHook";
                    case SODIUM_RENDER_MODEL -> "onBooleanValue";
                    case SODIUM_PROCESS_QUAD -> "onBooleanValue";
                    case CHAMS_REMAP_SUBMIT_MODEL, CHAMS_FOIL_BUFFER -> "onObjectValue";
                    case CHAMS_MARK_ITEM, CHAMS_ITEM_RENDER, HAND_ANIMATION -> "onVoidHook";
                };
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals(BRIDGE)
                    && call.name.equals(hookName)) return true;
        }
        return false;
    }

    /** Branch-producing hooks need fresh stack-map frames and local-variable sizes. */
    public static boolean requiresFrames(List<TargetMethod> targets) {
        return targets.stream()
                .anyMatch(
                        target ->
                                switch (target.hook()) {
                                    case HAND_ANIMATION,
                                            BOXED_ARGS_VOID_GATE,
                                            SCOREBOARD,
                                            TRIM_RENDER,
                                            XRAY_TESSELLATE,
                                            SODIUM_RENDER_MODEL,
                                            VERSION_SPECIFIC ->
                                            true;
                                    default -> false;
                                });
    }
}
