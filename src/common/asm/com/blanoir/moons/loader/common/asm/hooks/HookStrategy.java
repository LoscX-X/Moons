package com.blanoir.moons.loader.common.asm.hooks;

import static com.blanoir.moons.loader.common.asm.hooks.HookInstructions.BRIDGE;
import static com.blanoir.moons.loader.common.asm.hooks.HookInstructions.containsIdentifiedHook;

import com.blanoir.moons.loader.common.mapping.TargetMethod;
import com.blanoir.moons.loader.sodium.asm.SodiumHooks;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.EnumMap;
import java.util.function.BiPredicate;

/** One definition owns installation, existing-hook detection and frame requirements. */
record HookStrategy(
        BiPredicate<MethodNode, TargetMethod> installer,
        BiPredicate<MethodNode, TargetMethod> detector,
        boolean requiresFrames) {
    private static final EnumMap<TargetMethod.HookKind, HookStrategy> STRATEGIES =
            createStrategies();

    static HookStrategy forTarget(TargetMethod target) {
        return STRATEGIES.get(target.hook());
    }

    private static EnumMap<TargetMethod.HookKind, HookStrategy> createStrategies() {
        var strategies =
                new EnumMap<TargetMethod.HookKind, HookStrategy>(TargetMethod.HookKind.class);
        for (var kind : TargetMethod.HookKind.values()) strategies.put(kind, create(kind));
        return strategies;
    }

    private static HookStrategy create(TargetMethod.HookKind kind) {
        return switch (kind) {
            case VERSION_SPECIFIC ->
                    new HookStrategy(
                            HookStrategy::installVersionSpecific,
                            HookStrategy::containsVersionSpecific,
                            true);
            case CLIENT_TICK ->
                    new HookStrategy(
                            (method, target) -> ClientHooks.loadClientTick(method),
                            bridgeCall("onClientTickStart", false),
                            false);
            case FRAME ->
                    new HookStrategy(
                            (method, target) -> ClientHooks.loadFrame(method),
                            bridgeCall("onFrame", false),
                            false);
            case HUD ->
                    new HookStrategy(
                            (method, target) -> RenderHooks.loadHud(method),
                            bridgeCall("onHudRender", false),
                            false);
            case SCOREBOARD ->
                    new HookStrategy(
                            (method, target) -> RenderHooks.loadScoreboard(method, target.id()),
                            bridgeCall("onBooleanValue", false),
                            true);
            case WORLD_RENDER ->
                    new HookStrategy(
                            (method, target) -> RenderHooks.loadWorldRender(method),
                            bridgeCall("onWorldRender", false),
                            false);
            case KEY ->
                    new HookStrategy(
                            (method, target) -> ClientHooks.loadKey(method),
                            bridgeCall("onKey", false),
                            false);
            case MOUSE_BUTTON ->
                    new HookStrategy(
                            (method, target) -> ClientHooks.loadMouseButton(method),
                            bridgeCall("onMouse", false),
                            false);
            case MOUSE_SCROLL ->
                    new HookStrategy(
                            (method, target) -> ClientHooks.loadMouseScroll(method),
                            bridgeCall("onMouseScroll", false),
                            false);
            case MOUSE_MOVEMENT ->
                    new HookStrategy(
                            (method, target) ->
                                    ValueHooks.loadSimpleStartEnd(
                                            method, "onMouseMoveStart", "onMouseMoveEnd"),
                            bridgeCall("onMouseMoveStart", false),
                            false);
            case ATTACK ->
                    new HookStrategy(
                            (method, target) ->
                                    ClientHooks.loadBooleanAction(
                                            method, "onAttack", "onAttackEnd"),
                            bridgeCall("onAttack", false),
                            false);
            case USE ->
                    new HookStrategy(
                            (method, target) ->
                                    ClientHooks.loadVoidAction(method, "onUse", "onUseEnd"),
                            bridgeCall("onUse", false),
                            false);
            case PACKET_SEND ->
                    new HookStrategy(
                            (method, target) -> WorldHooks.loadPacketSend(method),
                            bridgeCall("onPacketSend", false),
                            false);
            case PACKET_RECEIVE ->
                    new HookStrategy(
                            (method, target) -> WorldHooks.loadPacketReceive(method),
                            bridgeCall("onPacketReceive", false),
                            false);
            case PACKET_APPLY ->
                    new HookStrategy(
                            (method, target) -> WorldHooks.loadPacketApply(method),
                            bridgeCall("onPacketApply", false),
                            false);
            case BLOCK_SET ->
                    new HookStrategy(
                            (method, target) -> WorldHooks.loadBlockSet(method),
                            bridgeCall("onBlockUpdate", false),
                            false);
            case BLOCK_SERVER_SET ->
                    new HookStrategy(
                            (method, target) -> WorldHooks.loadBlockServerSet(method),
                            bridgeCall("onBlockUpdate", false),
                            false);
            case PLAYER_UPDATE ->
                    new HookStrategy(
                            (method, target) -> PlayerHooks.loadPlayerUpdate(method),
                            bridgeCall("onPlayerUpdate", false),
                            false);
            case MOVE_INPUT ->
                    new HookStrategy(
                            (method, target) -> PlayerHooks.loadMoveInput(method),
                            bridgeCall("onMoveInput", false),
                            false);
            case PLAYER_MOVE ->
                    new HookStrategy(
                            (method, target) -> PlayerHooks.loadPlayerMove(method),
                            bridgeCall("onPlayerMove", false),
                            false);
            case PLAYER_POSITION ->
                    new HookStrategy(
                            (method, target) ->
                                    ValueHooks.loadSimpleStartEnd(
                                            method, "onPlayerPositionStart", "onPlayerPositionEnd"),
                            bridgeCall("onPlayerPositionStart", false),
                            false);
            case RENDER_STATE ->
                    new HookStrategy(
                            (method, target) -> RenderHooks.loadRenderState(method),
                            bridgeCall("onRenderState", false),
                            false);
            case RENDERER_CLOSE ->
                    new HookStrategy(
                            (method, target) -> RenderHooks.loadRendererClose(method),
                            bridgeCall("onRendererClose", false),
                            false);
            case VOID_HEAD ->
                    new HookStrategy(
                            (method, target) -> ValueHooks.loadVoidHook(method, target.id(), false),
                            bridgeCall("onVoidHook", true),
                            false);
            case VOID_RETURN ->
                    new HookStrategy(
                            (method, target) -> ValueHooks.loadVoidHook(method, target.id(), true),
                            bridgeCall("onVoidHook", true),
                            false);
            case PRESENT_BEFORE_GPU_PRESENT ->
                    new HookStrategy(
                            (method, target) ->
                                    RenderHooks.loadBeforeGpuPresent(method, target.id()),
                            bridgeCall("onVoidHook", false),
                            false);
            case FLOAT_RETURN ->
                    new HookStrategy(
                            (method, target) ->
                                    ValueHooks.loadFloatReturn(method, target.id(), false),
                            bridgeCall("onFloatValue", false),
                            false);
            case FLOAT_RETURN_ARG ->
                    new HookStrategy(
                            (method, target) -> ValueHooks.loadFloatReturn(method, target.id()),
                            bridgeCall("onFloatValue", false),
                            false);
            case FLOAT_RETURN_OBJECT_ARG ->
                    new HookStrategy(
                            (method, target) ->
                                    ValueHooks.loadFloatObjectReturn(method, target.id()),
                            bridgeCall("onObjectValue", false),
                            false);
            case FLOAT_ARGUMENTS ->
                    new HookStrategy(
                            (method, target) -> ValueHooks.loadFloatArguments(method, target.id()),
                            bridgeCall("onFloatValue", true),
                            true);
            case FLOAT_HEAD_BOOLEAN_GATE ->
                    new HookStrategy(
                            (method, target) ->
                                    ValueHooks.loadFloatHeadBooleanGate(method, target.id()),
                            bridgeCall("onBooleanValue", false),
                            false);
            case BOOLEAN_RETURN_ARG ->
                    new HookStrategy(
                            (method, target) -> ValueHooks.loadBooleanReturn(method, target.id()),
                            bridgeCall("onBooleanValue", false),
                            false);
            case OBJECT_RETURN ->
                    new HookStrategy(
                            (method, target) -> ValueHooks.loadObjectReturn(method, target.id()),
                            bridgeCall("onObjectValue", false),
                            false);
            case OBJECT_ARGUMENT ->
                    new HookStrategy(
                            (method, target) -> ValueHooks.loadObjectArgument(method, target.id()),
                            bridgeCall("onObjectValue", false),
                            false);
            case OBJECT_INVOKE_RETURN ->
                    new HookStrategy(
                            (method, target) ->
                                    RenderHooks.loadObjectInvocationReturn(method, target.id()),
                            bridgeCall("onObjectValue", false),
                            false);
            case NAMED_FLOAT_LOCAL ->
                    new HookStrategy(
                            (method, target) ->
                                    ValueHooks.loadNamedFloatLocal(
                                            method, target.id(), "brightnessOption"),
                            bridgeCall("onFloatValue", false),
                            false);
            case SPRINT_DECISIONS ->
                    new HookStrategy(
                            (method, target) -> PlayerHooks.loadSprintDecisions(method),
                            bridgeCall("onBooleanValue", false),
                            false);
            case YAW_RESULT ->
                    new HookStrategy(
                            (method, target) -> PlayerHooks.loadYawResult(method, target.id()),
                            bridgeCall("onFloatValue", false),
                            false);
            case BOOLEAN_GATE ->
                    new HookStrategy(
                            (method, target) -> ValueHooks.loadBooleanGate(method, target.id()),
                            bridgeCall("onBooleanValue", false),
                            false);
            case STATIC_BOOLEAN_RETURN_ARG1 ->
                    new HookStrategy(
                            (method, target) ->
                                    ValueHooks.loadStaticBooleanReturn(method, target.id()),
                            bridgeCall("onBooleanValue", false),
                            false);
            case XRAY_TESSELLATE ->
                    new HookStrategy(
                            (method, target) -> XrayHooks.loadXrayTessellate(method, target.id()),
                            bridgeCall("onBooleanValue", false),
                            true);
            case XRAY_QUAD ->
                    new HookStrategy(
                            (method, target) -> XrayHooks.loadXrayQuad(method, target.id()),
                            bridgeCall("onVoidHook", false),
                            false);
            case VOID_START_END_ARG0 ->
                    new HookStrategy(
                            (method, target) ->
                                    ValueHooks.loadVoidStartEndArgument(method, target.id()),
                            bridgeCall("onVoidHook", false),
                            false);
            case BOXED_ARGS_VOID_GATE ->
                    new HookStrategy(
                            (method, target) ->
                                    ValueHooks.loadBoxedArgumentsGate(method, target.id()),
                            bridgeCall("onBooleanValue", true),
                            true);
            case CHAMS_FRAME ->
                    new HookStrategy(
                            (method, target) -> ChamsHooks.loadChamsFrame(method, target.id()),
                            bridgeCall("onVoidHook", false),
                            false);
            case SODIUM_RENDER_MODEL ->
                    new HookStrategy(
                            (method, target) ->
                                    SodiumHooks.loadSodiumRenderModel(method, target.id()),
                            bridgeCall("onBooleanValue", false),
                            true);
            case SODIUM_PROCESS_QUAD ->
                    new HookStrategy(
                            (method, target) ->
                                    SodiumHooks.loadSodiumProcessQuad(method, target.id()),
                            bridgeCall("onBooleanValue", false),
                            false);
            case CHAMS_REMAP_SUBMIT_MODEL ->
                    new HookStrategy(
                            (method, target) ->
                                    ValueHooks.loadInvocationArgumentRemap(
                                            method, target.id(), "submitModel", 3),
                            bridgeCall("onObjectValue", false),
                            false);
            case CHAMS_MARK_ITEM ->
                    new HookStrategy(
                            (method, target) -> ValueHooks.loadVoidHook(method, target.id(), true),
                            bridgeCall("onVoidHook", false),
                            false);
            case CHAMS_ITEM_RENDER ->
                    new HookStrategy(
                            (method, target) -> ChamsHooks.loadChamsItemRender(method, target.id()),
                            bridgeCall("onVoidHook", false),
                            false);
            case CHAMS_FOIL_BUFFER ->
                    new HookStrategy(
                            (method, target) ->
                                    ValueHooks.loadInvocationArgumentRemap(
                                            method, target.id() + ".type", "getBuffer", 0),
                            bridgeCall("onObjectValue", false),
                            false);
            case HAND_ANIMATION ->
                    new HookStrategy(
                            (method, target) -> PlayerHooks.loadHandAnimation(method, target.id()),
                            bridgeCall("onVoidHook", false),
                            true);
        };
    }

    private static boolean installVersionSpecific(MethodNode method, TargetMethod target) {
        boolean loaded = target.installer().apply(method, target.id());
        if (loaded) {
            InsnList marker = new InsnList();
            marker.add(new LdcInsnNode("moons.hook:" + target.id()));
            marker.add(new InsnNode(Opcodes.POP));
            method.instructions.insert(marker);
        }
        return loaded;
    }

    private static boolean containsVersionSpecific(MethodNode method, TargetMethod target) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LdcInsnNode constant
                    && ("moons.hook:" + target.id()).equals(constant.cst)) return true;
        }
        return false;
    }

    private static BiPredicate<MethodNode, TargetMethod> bridgeCall(
            String name, boolean identified) {
        return (method, target) -> {
            if (identified) return containsIdentifiedHook(method, target.id(), name);
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals(BRIDGE)
                        && call.name.equals(name)) return true;
            }
            return false;
        };
    }
}
