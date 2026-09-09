package com.blanoir.moons.agent.core;

import static com.blanoir.moons.agent.core.HookInstructions.BRIDGE;
import static com.blanoir.moons.agent.core.HookInstructions.appendVoidCancellation;
import static com.blanoir.moons.agent.core.HookInstructions.beforeReturns;
import static com.blanoir.moons.agent.core.HookInstructions.call;
import static com.blanoir.moons.agent.core.HookInstructions.containsIdentifiedHook;
import static com.blanoir.moons.agent.core.HookInstructions.guardHook;
import static com.blanoir.moons.agent.core.HookInstructions.loadThisAndCall;
import static com.blanoir.moons.agent.core.HookInstructions.thisCall;

import com.blanoir.moons.api.Branding;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Retransformation-safe method-body hooks. No fields, methods or interfaces are added. */
final class MoonsTransformer {

    private final MappingService mappings;
    private final Set<String> installedHooks = ConcurrentHashMap.newKeySet();
    private final Set<String> failedHooks = ConcurrentHashMap.newKeySet();

    MoonsTransformer(MappingService mappings) {
        this.mappings = mappings;
    }

    boolean targets(String className) {
        return mappings.targetsClass(className);
    }

    String[] targetClassNames() {
        return mappings.targetClassNames();
    }

    Set<String> installedHooks() {
        return Set.copyOf(installedHooks);
    }

    Set<String> failedHooks() {
        return Set.copyOf(failedHooks);
    }

    byte[] transform(ClassLoader loader, String className, byte[] classfileBuffer) {
        if (className == null || !mappings.targetsClass(className)) return null;
        List<TargetMethod> targets = mappings.targetsForClass(className);
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassNode node = new ClassNode(Opcodes.ASM9);
            reader.accept(node, 0);
            boolean changed = false;
            for (TargetMethod target : targets) {
                boolean matched = false;
                for (MethodNode method : node.methods) {
                    if (!target.matchesMethod(method.name, method.desc)) continue;
                    matched = true;
                    boolean hooked = containsHook(method, target);
                    if (!hooked) {
                        hooked = load(method, target);
                        changed |= hooked;
                    }
                    if (hooked) installedHooks.add(target.id());
                    else failedHooks.add(target.id() + ":load-point-not-found");
                }
                if (!matched) failedHooks.add(target.id() + ":target-not-found");
            }
            if (!changed) return null;
            // Animation hooks and allocation guards introduce new branch targets.
            // Rebuild StackMapTable entries for these classes rather than preserving
            // vanilla frames that do not describe the added branches and locals.
            boolean recomputeFrames =
                    targets.stream()
                            .anyMatch(
                                    target ->
                                            target.hook() == TargetMethod.HookKind.HAND_ANIMATION
                                                    || target.hook()
                                                            == TargetMethod.HookKind.SCOREBOARD
                                                    || target.hook()
                                                            == TargetMethod.HookKind.TRIM_RENDER
                                                    || target.hook()
                                                            == TargetMethod.HookKind.XRAY_TESSELLATE
                                                    || target.hook()
                                                            == TargetMethod.HookKind
                                                                    .SODIUM_RENDER_MODEL
                                                    || target.hook()
                                                            == TargetMethod.HookKind
                                                                    .VERSION_SPECIFIC);
            ClassWriter writer =
                    recomputeFrames
                            ? new LoaderAwareClassWriter(
                                    reader,
                                    ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS,
                                    loader)
                            : new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable failure) {
            failedHooks.add(className + ":" + failure.getClass().getSimpleName());
            System.err.println(
                    Branding.prefix()
                            + " Skipping failed transformation for "
                            + className
                            + ": "
                            + failure);
            return null;
        }
    }

    private static boolean load(MethodNode method, TargetMethod target) {
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
            case CLIENT_TICK -> loadClientTick(method);
            case FRAME -> loadFrame(method);
            case HUD -> loadHud(method);
            case SCOREBOARD -> loadScoreboard(method, target.id());
            case WORLD_RENDER -> loadWorldRender(method);
            case KEY -> loadKey(method);
            case MOUSE_BUTTON -> loadMouseButton(method);
            case MOUSE_SCROLL -> loadMouseScroll(method);
            case MOUSE_MOVEMENT -> loadSimpleStartEnd(method, "onMouseMoveStart", "onMouseMoveEnd");
            case ATTACK -> loadBooleanAction(method, "onAttack", "onAttackEnd");
            case USE -> loadVoidAction(method, "onUse", "onUseEnd");
            case PACKET_SEND -> loadPacketSend(method);
            case PACKET_RECEIVE -> loadPacketReceive(method);
            case PACKET_APPLY -> loadPacketApply(method);
            case BLOCK_SET -> loadBlockSet(method);
            case BLOCK_SERVER_SET -> loadBlockServerSet(method);
            case PLAYER_UPDATE -> loadPlayerUpdate(method);
            case MOVE_INPUT -> loadMoveInput(method);
            case PLAYER_MOVE -> loadPlayerMove(method);
            case PLAYER_POSITION ->
                    loadSimpleStartEnd(method, "onPlayerPositionStart", "onPlayerPositionEnd");
            case RENDER_STATE -> loadRenderState(method);
            case RENDERER_CLOSE -> loadRendererClose(method);
            case VOID_HEAD -> loadVoidHook(method, target.id(), false);
            case VOID_RETURN -> loadVoidHook(method, target.id(), true);
            case PRESENT_BEFORE_GPU_PRESENT -> loadBeforeGpuPresent(method, target.id());
            case FLOAT_RETURN -> loadFloatReturn(method, target.id(), false);
            case FLOAT_RETURN_ARG -> loadFloatReturn(method, target.id());
            case FLOAT_HEAD_BOOLEAN_GATE -> loadFloatHeadBooleanGate(method, target.id());
            case BOOLEAN_RETURN_ARG -> loadBooleanReturn(method, target.id());
            case OBJECT_RETURN -> loadObjectReturn(method, target.id());
            case OBJECT_ARGUMENT -> loadObjectArgument(method, target.id());
            case ITEM_STACK_ARGUMENT_5 -> loadItemStackArgument5(method, target.id());
            case TRIM_RENDER -> loadTrimRender(method, target.id());
            case OBJECT_INVOKE_RETURN -> loadObjectInvocationReturn(method, target.id());
            case NAMED_FLOAT_LOCAL -> loadNamedFloatLocal(method, target.id(), "brightnessOption");
            case SPRINT_DECISIONS -> loadSprintDecisions(method);
            case YAW_RESULT -> loadYawResult(method, target.id());
            case BOOLEAN_GATE -> loadBooleanGate(method, target.id());
            case STATIC_BOOLEAN_RETURN_ARG1 -> loadStaticBooleanReturn(method, target.id());
            case XRAY_TESSELLATE -> loadXrayTessellate(method, target.id());
            case XRAY_QUAD -> loadXrayQuad(method, target.id());
            case XRAY_SECTION_QUAD_26_2 -> loadXraySectionQuad26_2(method, target.id());
            case VOID_START_END_ARG0 -> loadVoidStartEndArgument(method, target.id());
            case CHAMS_FRAME -> loadChamsFrame(method, target.id());
            case SODIUM_RENDER_MODEL -> loadSodiumRenderModel(method, target.id());
            case SODIUM_PROCESS_QUAD -> loadSodiumProcessQuad(method, target.id());
            case CHAMS_REMAP_SUBMIT_MODEL ->
                    loadInvocationArgumentRemap(method, target.id(), "submitModel", 3);
            case CHAMS_MARK_ITEM -> loadVoidHook(method, target.id(), true);
            case CHAMS_ITEM_RENDER -> loadChamsItemRender(method, target.id());
            case CHAMS_FOIL_BUFFER ->
                    loadInvocationArgumentRemap(method, target.id() + ".type", "getBuffer", 0);
            case CHAMS_MARK_ITEM_26_2 -> loadChamsMarkItem26_2(method, target.id());
            case CHAMS_ITEM_PREPARE_26_2 -> loadChamsItemPrepare26_2(method, target.id());
            case CHAMS_FOIL_BUFFER_26_2 ->
                    loadInvocationArgumentRemap(
                            method, target.id() + ".type", "getVertexBuilder", 0);
            case HAND_ANIMATION -> loadHandAnimation(method, target.id());
        };
    }

    private static boolean loadClientTick(MethodNode method) {
        InsnList start = new InsnList();
        loadThisAndCall(start, "onClientTickStart");
        method.instructions.insert(start);
        beforeReturns(method, Opcodes.RETURN, () -> thisCall("onClientTickEnd"));
        return true;
    }

    private static boolean loadFrame(MethodNode method) {
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        hook.add(call("onFrame", "(Ljava/lang/Object;Ljava/lang/Object;)V"));
        method.instructions.insert(hook);
        return true;
    }

    private static boolean loadHud(MethodNode method) {
        int extractorSlot = -1;
        if (method.desc.equals("(Lnet/minecraft/client/DeltaTracker;ZZ)V")) {
            boolean constructingExtractor = false;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof TypeInsnNode type
                        && instruction.getOpcode() == Opcodes.NEW
                        && type.desc.equals("net/minecraft/client/gui/GuiGraphicsExtractor")) {
                    constructingExtractor = true;
                    continue;
                }
                if (constructingExtractor
                        && instruction instanceof VarInsnNode variable
                        && instruction.getOpcode() == Opcodes.ASTORE) {
                    extractorSlot = variable.var;
                    break;
                }
            }
            if (extractorSlot < 0) return false;
        }
        int resolvedExtractorSlot = extractorSlot;
        beforeReturns(
                method,
                Opcodes.RETURN,
                () -> {
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    if (method.desc.equals("(Lnet/minecraft/client/DeltaTracker;ZZ)V")) {
                        // 26.2 moved GuiGraphicsExtractor from a method parameter to a
                        // local created inside Gui.extractRenderState.
                        hook.add(new VarInsnNode(Opcodes.ALOAD, resolvedExtractorSlot));
                        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    } else {
                        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
                    }
                    hook.add(
                            call(
                                    "onHudRender",
                                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"));
                    return hook;
                });
        return true;
    }

    private static boolean loadScoreboard(MethodNode method, String id) {
        LabelNode proceed = new LabelNode();
        InsnList hook = new InsnList();
        hook.add(new LdcInsnNode(id));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new InsnNode(Opcodes.ICONST_2));
        hook.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        hook.add(new InsnNode(Opcodes.DUP));
        hook.add(new InsnNode(Opcodes.ICONST_0));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        hook.add(new InsnNode(Opcodes.AASTORE));
        hook.add(new InsnNode(Opcodes.DUP));
        hook.add(new InsnNode(Opcodes.ICONST_1));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
        hook.add(new InsnNode(Opcodes.AASTORE));
        hook.add(new InsnNode(Opcodes.ICONST_0));
        hook.add(
                call(
                        "onBooleanValue",
                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Z)Z"));
        hook.add(new JumpInsnNode(Opcodes.IFEQ, proceed));
        hook.add(new InsnNode(Opcodes.RETURN));
        hook.add(proceed);
        hook.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.insert(guardHook(id, hook));
        return true;
    }

    private static boolean loadMouseScroll(MethodNode method) {
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new VarInsnNode(Opcodes.LLOAD, 1));
        hook.add(new VarInsnNode(Opcodes.DLOAD, 3));
        hook.add(new VarInsnNode(Opcodes.DLOAD, 5));
        hook.add(call("onMouseScroll", "(Ljava/lang/Object;JDD)Z"));
        appendVoidCancellation(hook);
        method.instructions.insert(hook);
        return true;
    }

    private static boolean loadBeforeGpuPresent(MethodNode method, String id) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode invocation
                    && invocation.owner.equals("com/mojang/blaze3d/systems/GpuSurface")
                    && invocation.name.equals("present")
                    && invocation.desc.equals("()V")) {
                InsnList hook = new InsnList();
                hook.add(new LdcInsnNode(id));
                hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                hook.add(new InsnNode(Opcodes.ACONST_NULL));
                hook.add(
                        call(
                                "onVoidHook",
                                "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"));
                method.instructions.insertBefore(instruction, hook);
                return true;
            }
        }
        return false;
    }

    private static boolean loadWorldRender(MethodNode method) {
        int poseStackSlot = -1;
        AbstractInsnNode poseCreation = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof TypeInsnNode type
                    && instruction.getOpcode() == Opcodes.NEW
                    && type.desc.equals("com/mojang/blaze3d/vertex/PoseStack")) {
                poseCreation = instruction;
                continue;
            }
            if (poseCreation != null
                    && instruction instanceof VarInsnNode variable
                    && instruction.getOpcode() == Opcodes.ASTORE) {
                poseStackSlot = variable.var;
                break;
            }
        }
        boolean collectLoaded = false;
        boolean translucentLoaded = false;
        int renderGroupOrdinal = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!collectLoaded
                    && instruction instanceof LdcInsnNode constant
                    && "renderSolidFeatures".equals(constant.cst)) {
                AbstractInsnNode insertionPoint = constant.getPrevious();
                while (insertionPoint != null
                        && (insertionPoint.getType() == AbstractInsnNode.LABEL
                                || insertionPoint.getType() == AbstractInsnNode.LINE
                                || insertionPoint.getType() == AbstractInsnNode.FRAME)) {
                    insertionPoint = insertionPoint.getPrevious();
                }
                if (insertionPoint == null) insertionPoint = constant;
                else if (insertionPoint.getOpcode() == Opcodes.ALOAD) {
                    // Insert before the profiler load so the operand stack is empty.
                } else {
                    insertionPoint = constant;
                }
                method.instructions.insertBefore(insertionPoint, worldRenderCall(poseStackSlot, 0));
                collectLoaded = true;
            }
            if (instruction instanceof MethodInsnNode invocation
                    && invocation.owner.equals(
                            "net/minecraft/client/renderer/chunk/ChunkSectionsToRender")
                    && invocation.name.equals("renderGroup")) {
                if (renderGroupOrdinal++ == 1) {
                    method.instructions.insert(instruction, worldRenderCall(poseStackSlot, 1));
                    translucentLoaded = true;
                }
            }
        }
        return collectLoaded && translucentLoaded;
    }

    private static InsnList worldRenderCall(int poseStackSlot, int stage) {
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (poseStackSlot >= 0) {
            hook.add(new VarInsnNode(Opcodes.ALOAD, poseStackSlot));
        } else {
            hook.add(new TypeInsnNode(Opcodes.NEW, "com/mojang/blaze3d/vertex/PoseStack"));
            hook.add(new InsnNode(Opcodes.DUP));
            hook.add(
                    new MethodInsnNode(
                            Opcodes.INVOKESPECIAL,
                            "com/mojang/blaze3d/vertex/PoseStack",
                            "<init>",
                            "()V",
                            false));
        }
        hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
        hook.add(new InsnNode(stage == 0 ? Opcodes.ICONST_0 : Opcodes.ICONST_1));
        hook.add(
                call(
                        "onWorldRender",
                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;I)V"));
        return hook;
    }

    private static boolean loadKey(MethodNode method) {
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new VarInsnNode(Opcodes.LLOAD, 1));
        hook.add(new VarInsnNode(Opcodes.ILOAD, 3));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 4));
        hook.add(call("onKey", "(Ljava/lang/Object;JILjava/lang/Object;)Z"));
        appendVoidCancellation(hook);
        method.instructions.insert(hook);
        return true;
    }

    private static boolean loadMouseButton(MethodNode method) {
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new VarInsnNode(Opcodes.LLOAD, 1));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 3));
        hook.add(new VarInsnNode(Opcodes.ILOAD, 4));
        hook.add(call("onMouse", "(Ljava/lang/Object;JLjava/lang/Object;I)Z"));
        appendVoidCancellation(hook);
        method.instructions.insert(hook);
        return true;
    }

    private static boolean loadBooleanAction(
            MethodNode method, String hookName, String endHookName) {
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(call(hookName, "(Ljava/lang/Object;)Z"));
        LabelNode proceed = new LabelNode();
        hook.add(new JumpInsnNode(Opcodes.IFNE, proceed));
        hook.add(new InsnNode(Opcodes.ICONST_0));
        hook.add(new InsnNode(Opcodes.IRETURN));
        hook.add(proceed);
        hook.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.insert(hook);
        beforeReturns(method, Opcodes.IRETURN, () -> thisCall(endHookName));
        return true;
    }

    private static boolean loadVoidAction(MethodNode method, String hookName, String endHookName) {
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(call(hookName, "(Ljava/lang/Object;)Z"));
        appendVoidCancellation(hook);
        method.instructions.insert(hook);
        beforeReturns(method, Opcodes.RETURN, () -> thisCall(endHookName));
        return true;
    }

    private static boolean loadPacketSend(MethodNode method) {
        // Instrument original returns only: the PRE cancellation must not
        // report a packet as sent to rotation and position observers.
        beforeReturns(
                method,
                Opcodes.RETURN,
                () -> {
                    InsnList end = new InsnList();
                    end.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    end.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    end.add(call("onPacketSent", "(Ljava/lang/Object;Ljava/lang/Object;)V"));
                    return end;
                });
        InsnList start = new InsnList();
        start.add(new VarInsnNode(Opcodes.ALOAD, 0));
        start.add(new VarInsnNode(Opcodes.ALOAD, 1));
        start.add(call("onPacketSend", "(Ljava/lang/Object;Ljava/lang/Object;)Z"));
        appendVoidCancellation(start);
        method.instructions.insert(start);
        return true;
    }

    private static boolean loadPacketReceive(MethodNode method) {
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        hook.add(call("onPacketReceive", "(Ljava/lang/Object;Ljava/lang/Object;)Z"));
        appendVoidCancellation(hook);
        method.instructions.insert(hook);
        return true;
    }

    private static boolean loadPacketApply(MethodNode method) {
        beforeReturns(
                method,
                Opcodes.RETURN,
                () -> {
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    hook.add(call("onPacketApply", "(Ljava/lang/Object;Ljava/lang/Object;)V"));
                    return hook;
                });
        return true;
    }

    private static boolean loadBlockSet(MethodNode method) {
        method.instructions.insert(blockUpdateStart());
        int resultLocal = method.maxLocals++;
        beforeReturns(
                method,
                Opcodes.IRETURN,
                () -> {
                    InsnList hook = new InsnList();
                    hook.add(new InsnNode(Opcodes.DUP));
                    hook.add(new VarInsnNode(Opcodes.ISTORE, resultLocal));
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
                    hook.add(new VarInsnNode(Opcodes.ILOAD, resultLocal));
                    hook.add(
                            call(
                                    "onBlockUpdate",
                                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Z)V"));
                    return hook;
                });
        return true;
    }

    private static boolean loadBlockServerSet(MethodNode method) {
        method.instructions.insert(blockUpdateStart());
        beforeReturns(
                method,
                Opcodes.RETURN,
                () -> {
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
                    hook.add(new InsnNode(Opcodes.ICONST_1));
                    hook.add(
                            call(
                                    "onBlockUpdate",
                                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Z)V"));
                    return hook;
                });
        return true;
    }

    private static InsnList blockUpdateStart() {
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
        hook.add(
                call(
                        "onBlockUpdateStart",
                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"));
        return hook;
    }

    private static boolean loadMoveInput(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode invocation)
                    || !invocation.name.equals("tick")
                    || !invocation.desc.equals("()V")
                    || !invocation.owner.equals("net/minecraft/client/player/ClientInput"))
                continue;
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
            hook.add(call("onMoveInput", "(Ljava/lang/Object;)V"));
            method.instructions.insert(invocation, hook);
            return true;
        }
        return false;
    }

    private static boolean loadPlayerUpdate(MethodNode method) {
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(call("onPlayerUpdate", "(Ljava/lang/Object;)Z"));
        appendVoidCancellation(hook);
        method.instructions.insert(hook);
        return true;
    }

    private static boolean loadPlayerMove(MethodNode method) {
        int result = method.maxLocals++;
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new VarInsnNode(Opcodes.FLOAD, 1));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
        hook.add(
                call("onPlayerMove", "(Ljava/lang/Object;FLjava/lang/Object;)[Ljava/lang/Object;"));
        hook.add(new VarInsnNode(Opcodes.ASTORE, result));
        hook.add(new VarInsnNode(Opcodes.ALOAD, result));
        hook.add(new InsnNode(Opcodes.ICONST_0));
        hook.add(new InsnNode(Opcodes.AALOAD));
        hook.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Number"));
        hook.add(
                new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL, "java/lang/Number", "floatValue", "()F", false));
        hook.add(new VarInsnNode(Opcodes.FSTORE, 1));
        hook.add(new VarInsnNode(Opcodes.ALOAD, result));
        hook.add(new InsnNode(Opcodes.ICONST_1));
        hook.add(new InsnNode(Opcodes.AALOAD));
        hook.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/world/phys/Vec3"));
        hook.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.insert(hook);
        beforeReturns(method, Opcodes.RETURN, () -> thisCall("onPlayerMoveEnd"));
        return true;
    }

    private static boolean loadRenderState(MethodNode method) {
        beforeReturns(
                method,
                Opcodes.RETURN,
                () -> {
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
                    hook.add(new VarInsnNode(Opcodes.FLOAD, 3));
                    hook.add(call("onRenderState", "(Ljava/lang/Object;Ljava/lang/Object;F)V"));
                    return hook;
                });
        return true;
    }

    private static boolean loadRendererClose(MethodNode method) {
        beforeReturns(method, Opcodes.RETURN, () -> thisCall("onRendererClose"));
        return true;
    }

    private static boolean loadVoidHook(MethodNode method, String id, boolean atReturn) {
        Supplier<InsnList> factory =
                () -> {
                    InsnList hook = new InsnList();
                    hook.add(new LdcInsnNode(id));
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    hook.add(new InsnNode(Opcodes.ACONST_NULL));
                    hook.add(
                            call(
                                    "onVoidHook",
                                    "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"));
                    return hook;
                };
        if (atReturn) beforeReturns(method, Opcodes.RETURN, factory);
        else method.instructions.insert(factory.get());
        return true;
    }

    private static boolean loadFloatReturn(MethodNode method, String id) {
        return loadFloatReturn(method, id, true);
    }

    private static boolean loadFloatReturn(MethodNode method, String id, boolean hasFloatArgument) {
        int result = method.maxLocals++;
        beforeReturns(
                method,
                Opcodes.FRETURN,
                () -> {
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(Opcodes.FSTORE, result));
                    hook.add(new LdcInsnNode(id));
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    hook.add(
                            hasFloatArgument
                                    ? new VarInsnNode(Opcodes.FLOAD, 1)
                                    : new InsnNode(Opcodes.FCONST_0));
                    hook.add(new VarInsnNode(Opcodes.FLOAD, result));
                    hook.add(call("onFloatValue", "(Ljava/lang/String;Ljava/lang/Object;FF)F"));
                    return hook;
                });
        return true;
    }

    /** Returns the requested float argument immediately when the runtime gate is enabled. */
    private static boolean loadFloatHeadBooleanGate(MethodNode method, String id) {
        LabelNode proceed = new LabelNode();
        InsnList hook = new InsnList();
        hook.add(new LdcInsnNode(id));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new InsnNode(Opcodes.ACONST_NULL));
        hook.add(new InsnNode(Opcodes.ICONST_0));
        hook.add(
                call(
                        "onBooleanValue",
                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Z)Z"));
        hook.add(new JumpInsnNode(Opcodes.IFEQ, proceed));
        hook.add(new VarInsnNode(Opcodes.FLOAD, 1));
        hook.add(new InsnNode(Opcodes.FRETURN));
        hook.add(proceed);
        hook.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.insert(hook);
        return true;
    }

    private static boolean loadBooleanReturn(MethodNode method, String id) {
        int result = method.maxLocals++;
        beforeReturns(
                method,
                Opcodes.IRETURN,
                () -> {
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(Opcodes.ISTORE, result));
                    hook.add(new LdcInsnNode(id));
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    hook.add(new VarInsnNode(Opcodes.ILOAD, result));
                    hook.add(
                            call(
                                    "onBooleanValue",
                                    "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Z)Z"));
                    return hook;
                });
        return true;
    }

    private static boolean loadObjectReturn(MethodNode method, String id) {
        int result = method.maxLocals++;
        String returnType = org.objectweb.asm.Type.getReturnType(method.desc).getInternalName();
        boolean hasArgument = org.objectweb.asm.Type.getArgumentTypes(method.desc).length > 0;
        beforeReturns(
                method,
                Opcodes.ARETURN,
                () -> {
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(Opcodes.ASTORE, result));
                    hook.add(new LdcInsnNode(id));
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    hook.add(
                            hasArgument
                                    ? new VarInsnNode(Opcodes.ALOAD, 1)
                                    : new InsnNode(Opcodes.ACONST_NULL));
                    hook.add(new VarInsnNode(Opcodes.ALOAD, result));
                    hook.add(
                            call(
                                    "onObjectValue",
                                    "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
                    hook.add(new TypeInsnNode(Opcodes.CHECKCAST, returnType));
                    return hook;
                });
        return true;
    }

    private static boolean loadObjectArgument(MethodNode method, String id) {
        InsnList hook = new InsnList();
        hook.add(new LdcInsnNode(id));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new InsnNode(Opcodes.ACONST_NULL));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        hook.add(
                call(
                        "onObjectValue",
                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
        hook.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/network/chat/Component"));
        hook.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.insert(hook);
        return true;
    }

    private static boolean loadNamedFloatLocal(MethodNode method, String id, String localName) {
        LocalVariableNode local =
                method.localVariables == null
                        ? null
                        : method.localVariables.stream()
                                .filter(
                                        candidate ->
                                                candidate.name.equals(localName)
                                                        && candidate.desc.equals("F"))
                                .findFirst()
                                .orElse(null);
        if (local == null) return false;
        AbstractInsnNode store = local.start.getPrevious();
        while (store != null
                && !(store instanceof VarInsnNode variable
                        && variable.getOpcode() == Opcodes.FSTORE
                        && variable.var == local.index)) {
            store = store.getPrevious();
        }
        if (store == null) return false;
        InsnList hook = new InsnList();
        hook.add(new LdcInsnNode(id));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new InsnNode(Opcodes.FCONST_0));
        hook.add(new VarInsnNode(Opcodes.FLOAD, local.index));
        hook.add(call("onFloatValue", "(Ljava/lang/String;Ljava/lang/Object;FF)F"));
        hook.add(new VarInsnNode(Opcodes.FSTORE, local.index));
        method.instructions.insert(store, hook);
        return true;
    }

    private static boolean loadSprintDecisions(MethodNode method) {
        boolean changed = false;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode invocation)
                    || !invocation.desc.equals("()Z")) continue;
            String id =
                    switch (invocation.name) {
                        case "canStartSprinting" -> "movement.sprint-start";
                        case "sprint" -> "movement.sprint-input";
                        case "shouldStopRunSprinting" -> "movement.sprint-stop";
                        default -> null;
                    };
            if (id == null) continue;
            InsnList hook = new InsnList();
            int result = method.maxLocals++;
            hook.add(new VarInsnNode(Opcodes.ISTORE, result));
            hook.add(new LdcInsnNode(id));
            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
            hook.add(new InsnNode(Opcodes.ACONST_NULL));
            hook.add(new VarInsnNode(Opcodes.ILOAD, result));
            hook.add(
                    call(
                            "onBooleanValue",
                            "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Z)Z"));
            method.instructions.insert(instruction, hook);
            changed = true;
        }
        return changed;
    }

    private static boolean loadYawResult(MethodNode method, String id) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode invocation
                    && invocation.name.equals("getYRot")
                    && invocation.desc.equals("()F")) {
                int result = method.maxLocals++;
                InsnList hook = new InsnList();
                hook.add(new VarInsnNode(Opcodes.FSTORE, result));
                hook.add(new LdcInsnNode(id));
                hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                hook.add(new InsnNode(Opcodes.FCONST_0));
                hook.add(new VarInsnNode(Opcodes.FLOAD, result));
                hook.add(call("onFloatValue", "(Ljava/lang/String;Ljava/lang/Object;FF)F"));
                method.instructions.insert(instruction, hook);
                return true;
            }
        }
        return false;
    }

    private static boolean loadHandAnimation(MethodNode method, String id) {
        // renderArmWithItem: hand=4, swingProgress=5, equipProgress=7, PoseStack=8.
        LabelNode continueVanilla = new LabelNode();
        InsnList start = new InsnList();
        start.add(new VarInsnNode(Opcodes.ALOAD, 8));
        start.add(
                new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "com/mojang/blaze3d/vertex/PoseStack",
                        "pushPose",
                        "()V",
                        false));
        start.add(new LdcInsnNode(id));
        start.add(call("isHookActive", "(Ljava/lang/String;)Z"));
        start.add(new JumpInsnNode(Opcodes.IFEQ, continueVanilla));
        start.add(new LdcInsnNode(id));
        start.add(new VarInsnNode(Opcodes.ALOAD, 0));
        start.add(new InsnNode(Opcodes.ICONST_4));
        start.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        start.add(new InsnNode(Opcodes.DUP));
        start.add(new InsnNode(Opcodes.ICONST_0));
        start.add(new VarInsnNode(Opcodes.ALOAD, 8));
        start.add(new InsnNode(Opcodes.AASTORE));
        start.add(new InsnNode(Opcodes.DUP));
        start.add(new InsnNode(Opcodes.ICONST_1));
        start.add(new VarInsnNode(Opcodes.FLOAD, 5));
        start.add(
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "java/lang/Float",
                        "valueOf",
                        "(F)Ljava/lang/Float;",
                        false));
        start.add(new InsnNode(Opcodes.AASTORE));
        start.add(new InsnNode(Opcodes.DUP));
        start.add(new InsnNode(Opcodes.ICONST_2));
        start.add(new VarInsnNode(Opcodes.FLOAD, 7));
        start.add(
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "java/lang/Float",
                        "valueOf",
                        "(F)Ljava/lang/Float;",
                        false));
        start.add(new InsnNode(Opcodes.AASTORE));
        start.add(new InsnNode(Opcodes.DUP));
        start.add(new InsnNode(Opcodes.ICONST_3));
        start.add(new VarInsnNode(Opcodes.ALOAD, 4));
        start.add(new InsnNode(Opcodes.AASTORE));
        start.add(call("onVoidHook", "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"));

        // Submit the completed item pose directly to avoid composing a
        // second modern arm/use-animation transform over it.
        start.add(new LdcInsnNode(id + ".replace-vanilla"));
        start.add(new VarInsnNode(Opcodes.ALOAD, 0));
        start.add(new VarInsnNode(Opcodes.ALOAD, 4));
        start.add(new InsnNode(Opcodes.ICONST_0));
        start.add(
                call(
                        "onBooleanValue",
                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Z)Z"));
        start.add(new JumpInsnNode(Opcodes.IFEQ, continueVanilla));

        start.add(new VarInsnNode(Opcodes.ALOAD, 0));
        start.add(new VarInsnNode(Opcodes.ALOAD, 1));
        start.add(new VarInsnNode(Opcodes.ALOAD, 6));

        LabelNode leftHandContext = new LabelNode();
        LabelNode contextReady = new LabelNode();
        start.add(new VarInsnNode(Opcodes.ALOAD, 1));
        start.add(
                new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "net/minecraft/client/player/AbstractClientPlayer",
                        "getMainArm",
                        "()Lnet/minecraft/world/entity/HumanoidArm;",
                        false));
        start.add(
                new FieldInsnNode(
                        Opcodes.GETSTATIC,
                        "net/minecraft/world/entity/HumanoidArm",
                        "RIGHT",
                        "Lnet/minecraft/world/entity/HumanoidArm;"));
        start.add(new JumpInsnNode(Opcodes.IF_ACMPNE, leftHandContext));
        start.add(
                new FieldInsnNode(
                        Opcodes.GETSTATIC,
                        "net/minecraft/world/item/ItemDisplayContext",
                        "FIRST_PERSON_RIGHT_HAND",
                        "Lnet/minecraft/world/item/ItemDisplayContext;"));
        start.add(new JumpInsnNode(Opcodes.GOTO, contextReady));
        start.add(leftHandContext);
        start.add(
                new FieldInsnNode(
                        Opcodes.GETSTATIC,
                        "net/minecraft/world/item/ItemDisplayContext",
                        "FIRST_PERSON_LEFT_HAND",
                        "Lnet/minecraft/world/item/ItemDisplayContext;"));
        start.add(contextReady);

        start.add(new VarInsnNode(Opcodes.ALOAD, 8));
        start.add(new VarInsnNode(Opcodes.ALOAD, 9));
        start.add(new VarInsnNode(Opcodes.ILOAD, 10));
        start.add(
                new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "net/minecraft/client/renderer/ItemInHandRenderer",
                        "renderItem",
                        "(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;"
                                + "Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;"
                                + "Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V",
                        false));
        start.add(new InsnNode(Opcodes.RETURN));
        start.add(continueVanilla);
        method.instructions.insert(start);

        beforeReturns(
                method,
                Opcodes.RETURN,
                () -> {
                    InsnList end = new InsnList();
                    end.add(new LdcInsnNode(id + ".end"));
                    end.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    end.add(new InsnNode(Opcodes.ACONST_NULL));
                    end.add(
                            call(
                                    "onVoidHook",
                                    "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"));
                    end.add(new VarInsnNode(Opcodes.ALOAD, 8));
                    end.add(
                            new MethodInsnNode(
                                    Opcodes.INVOKEVIRTUAL,
                                    "com/mojang/blaze3d/vertex/PoseStack",
                                    "popPose",
                                    "()V",
                                    false));
                    return end;
                });
        return true;
    }

    /** Draws bundled trims after the armor layers, before the vanilla atlas lookup. */
    private static boolean loadTrimRender(MethodNode method, String id) {
        AbstractInsnNode lookup = null;
        int orderSlot = -1;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.name.equals("trimSpriteLookup")
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.getPrevious() instanceof VarInsnNode owner
                    && owner.getOpcode() == Opcodes.ALOAD
                    && owner.var == 0) {
                lookup = owner;
            }
            if (lookup != null
                    && instruction instanceof MethodInsnNode invocation
                    && invocation.owner.equals("net/minecraft/client/renderer/SubmitNodeCollector")
                    && invocation.name.equals("order")
                    && invocation.getPrevious() instanceof IincInsnNode increment) {
                orderSlot = increment.var;
                break;
            }
        }
        if (lookup == null || orderSlot < 0) return false;

        InsnList hook = new InsnList();
        hook.add(new LdcInsnNode(id));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new IntInsnNode(Opcodes.BIPUSH, 10));
        hook.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        int[] slots = {1, 2, 3, 4, 5, 6, 7, 8, 10, orderSlot};
        for (int index = 0; index < slots.length; index++) {
            hook.add(new InsnNode(Opcodes.DUP));
            hook.add(new IntInsnNode(Opcodes.BIPUSH, index));
            hook.add(new VarInsnNode(index < 7 ? Opcodes.ALOAD : Opcodes.ILOAD, slots[index]));
            if (index >= 7) {
                hook.add(
                        new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                "java/lang/Integer",
                                "valueOf",
                                "(I)Ljava/lang/Integer;",
                                false));
            }
            hook.add(new InsnNode(Opcodes.AASTORE));
        }
        hook.add(new InsnNode(Opcodes.ICONST_1));
        hook.add(
                call(
                        "onBooleanValue",
                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Z)Z"));
        appendVoidCancellation(hook);
        method.instructions.insertBefore(lookup, guardHook(id, hook));
        return true;
    }

    /** Replaces the fifth declared argument of EquipmentLayerRenderer#renderLayers. */
    private static boolean loadItemStackArgument5(MethodNode method, String id) {
        org.objectweb.asm.Type[] arguments = org.objectweb.asm.Type.getArgumentTypes(method.desc);
        if (arguments.length < 5
                || !arguments[4].getDescriptor().equals("Lnet/minecraft/world/item/ItemStack;")) {
            return false;
        }

        int itemStackSlot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (int index = 0; index < 4; index++) itemStackSlot += arguments[index].getSize();
        int firstArgumentSlot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;

        InsnList hook = new InsnList();
        hook.add(new LdcInsnNode(id));
        hook.add(
                (method.access & Opcodes.ACC_STATIC) == 0
                        ? new VarInsnNode(Opcodes.ALOAD, 0)
                        : new InsnNode(Opcodes.ACONST_NULL));
        hook.add(new VarInsnNode(Opcodes.ALOAD, firstArgumentSlot));
        hook.add(new VarInsnNode(Opcodes.ALOAD, itemStackSlot));
        hook.add(
                call(
                        "onObjectValue",
                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
        hook.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/world/item/ItemStack"));
        hook.add(new VarInsnNode(Opcodes.ASTORE, itemStackSlot));
        method.instructions.insert(hook);
        return true;
    }

    /** Replaces the ItemStack returned by LocalPlayer#getMainHandItem inside a target method. */
    private static boolean loadObjectInvocationReturn(MethodNode method, String id) {
        String invocationName = id.contains("hud") ? "getSelectedItem" : "getMainHandItem";
        boolean changed = false;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode invocation)
                    || !invocation.name.equals(invocationName)
                    || !invocation.desc.equals("()Lnet/minecraft/world/item/ItemStack;")) continue;
            int result = method.maxLocals++;
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ASTORE, result));
            hook.add(new LdcInsnNode(id));
            hook.add(
                    (method.access & Opcodes.ACC_STATIC) == 0
                            ? new VarInsnNode(Opcodes.ALOAD, 0)
                            : new InsnNode(Opcodes.ACONST_NULL));
            hook.add(new InsnNode(Opcodes.ACONST_NULL));
            hook.add(new VarInsnNode(Opcodes.ALOAD, result));
            hook.add(
                    call(
                            "onObjectValue",
                            "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
            hook.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/world/item/ItemStack"));
            method.instructions.insert(instruction, hook);
            changed = true;
        }
        return changed;
    }

    private static boolean loadBooleanGate(MethodNode method, String id) {
        InsnList hook = new InsnList();
        hook.add(new LdcInsnNode(id));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        hook.add(new InsnNode(Opcodes.ICONST_1));
        hook.add(
                call(
                        "onBooleanValue",
                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Z)Z"));
        if (org.objectweb.asm.Type.getReturnType(method.desc).getSort()
                == org.objectweb.asm.Type.VOID) {
            appendVoidCancellation(hook);
        } else {
            LabelNode proceed = new LabelNode();
            hook.add(new JumpInsnNode(Opcodes.IFNE, proceed));
            hook.add(new InsnNode(Opcodes.ICONST_0));
            hook.add(new InsnNode(Opcodes.IRETURN));
            hook.add(proceed);
            hook.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        }
        method.instructions.insert(hook);
        return true;
    }

    private static boolean loadStaticBooleanReturn(MethodNode method, String id) {
        int result = method.maxLocals++;
        beforeReturns(
                method,
                Opcodes.IRETURN,
                () -> {
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(Opcodes.ISTORE, result));
                    hook.add(new LdcInsnNode(id));
                    hook.add(new InsnNode(Opcodes.ACONST_NULL));
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    hook.add(new VarInsnNode(Opcodes.ILOAD, result));
                    hook.add(
                            call(
                                    "onBooleanValue",
                                    "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Z)Z"));
                    return hook;
                });
        return true;
    }

    private static boolean loadXrayTessellate(MethodNode method, String id) {
        InsnList hook = new InsnList();
        hook.add(new LdcInsnNode(id));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new InsnNode(Opcodes.ICONST_3));
        hook.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        addArrayValue(hook, 0, 5);
        addArrayValue(hook, 1, 6);
        addArrayValue(hook, 2, 7);
        hook.add(new InsnNode(Opcodes.ICONST_1));
        hook.add(
                call(
                        "onBooleanValue",
                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Z)Z"));
        appendVoidCancellation(hook);
        method.instructions.insert(guardHook(id, hook));
        beforeReturns(
                method,
                Opcodes.RETURN,
                () -> {
                    InsnList end = new InsnList();
                    end.add(new LdcInsnNode(id + ".end"));
                    end.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    end.add(new InsnNode(Opcodes.ACONST_NULL));
                    end.add(
                            call(
                                    "onVoidHook",
                                    "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"));
                    return end;
                });
        return true;
    }

    private static void addArrayValue(InsnList instructions, int arrayIndex, int localIndex) {
        instructions.add(new InsnNode(Opcodes.DUP));
        instructions.add(new InsnNode(Opcodes.ICONST_0 + arrayIndex));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, localIndex));
        instructions.add(new InsnNode(Opcodes.AASTORE));
    }

    private static boolean loadXrayQuad(MethodNode method, String id) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode invocation
                    && invocation.owner.equals(
                            "net/minecraft/client/renderer/block/BlockQuadOutput")
                    && invocation.name.equals("put")) {
                InsnList hook = new InsnList();
                hook.add(new LdcInsnNode(id));
                hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                hook.add(new InsnNode(Opcodes.ACONST_NULL));
                hook.add(
                        call(
                                "onVoidHook",
                                "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"));
                method.instructions.insertBefore(instruction, hook);
                return true;
            }
        }
        return false;
    }

    private static boolean loadVoidStartEndArgument(MethodNode method, String id) {
        InsnList start = new InsnList();
        start.add(new LdcInsnNode(id));
        start.add(new VarInsnNode(Opcodes.ALOAD, 0));
        start.add(new VarInsnNode(Opcodes.ALOAD, 1));
        start.add(call("onVoidHook", "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"));
        method.instructions.insert(start);
        beforeReturns(
                method,
                Opcodes.RETURN,
                () -> {
                    InsnList end = new InsnList();
                    end.add(new LdcInsnNode(id + ".end"));
                    end.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    end.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    end.add(
                            call(
                                    "onVoidHook",
                                    "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"));
                    return end;
                });
        return true;
    }

    private static boolean loadChamsFrame(MethodNode method, String id) {
        MethodInsnNode beginPoint = null;
        MethodInsnNode oldEndPoint = null;
        MethodInsnNode secondRenderGroup = null;
        int renderGroupOrdinal = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode invocation)) continue;
            if (invocation.name.equals("renderSolidFeatures")) beginPoint = invocation;
            if (invocation.name.equals("renderTranslucentParticles")) oldEndPoint = invocation;
            if (invocation.owner.equals(
                            "net/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame")
                    && invocation.name.equals("executeSolid")) {
                beginPoint = invocation;
            }
            if (invocation.owner.equals(
                            "net/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame")
                    && invocation.name.equals("executeTranslucentAfterTerrain")) {
                oldEndPoint = invocation;
            }
            if (invocation.owner.equals("net/minecraft/client/renderer/chunk/ChunkSectionsToRender")
                    && invocation.name.equals("renderGroup")
                    && renderGroupOrdinal++ == 1) {
                secondRenderGroup = invocation;
            }
        }
        MethodInsnNode endPoint = oldEndPoint != null ? oldEndPoint : secondRenderGroup;
        if (beginPoint == null || endPoint == null) return false;
        method.instructions.insertBefore(beginPoint, methodHook(id + ".begin"));
        method.instructions.insert(endPoint, methodHook(id + ".end"));
        return true;
    }

    private static InsnList methodHook(String id) {
        InsnList hook = new InsnList();
        hook.add(new LdcInsnNode(id));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new InsnNode(Opcodes.ACONST_NULL));
        hook.add(call("onVoidHook", "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"));
        return hook;
    }

    private static boolean loadSodiumRenderModel(MethodNode method, String id) {
        InsnList start = new InsnList();
        start.add(new LdcInsnNode(id));
        start.add(new VarInsnNode(Opcodes.ALOAD, 0));
        start.add(new InsnNode(Opcodes.ICONST_3));
        start.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        start.add(new InsnNode(Opcodes.DUP));
        start.add(new InsnNode(Opcodes.ICONST_0));
        start.add(new InsnNode(Opcodes.ACONST_NULL));
        start.add(new InsnNode(Opcodes.AASTORE));
        addArrayValue(start, 1, 3);
        addArrayValue(start, 2, 2);
        start.add(new InsnNode(Opcodes.ICONST_1));
        start.add(
                call(
                        "onBooleanValue",
                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Z)Z"));
        appendVoidCancellation(start);
        method.instructions.insert(guardHook(id, start));
        beforeReturns(
                method,
                Opcodes.RETURN,
                () -> {
                    InsnList end = new InsnList();
                    end.add(new LdcInsnNode(id + ".end"));
                    end.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    end.add(new InsnNode(Opcodes.ACONST_NULL));
                    end.add(
                            call(
                                    "onVoidHook",
                                    "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"));
                    return end;
                });
        return true;
    }

    private static boolean loadSodiumProcessQuad(MethodNode method, String id) {
        FieldInsnNode forceOpaque = null;
        MethodInsnNode renderType = null;
        MethodInsnNode shadeQuad = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.name.equals("forceOpaque")
                    && field.desc.equals("Z")) {
                forceOpaque = field;
            } else if (instruction instanceof MethodInsnNode invocation
                    && invocation.name.equals("getRenderType")
                    && invocation.desc.equals(
                            "()Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;")) {
                renderType = invocation;
            } else if (instruction instanceof MethodInsnNode invocation
                    && invocation.name.equals("shadeQuad")) {
                shadeQuad = invocation;
            }
        }
        if (forceOpaque == null || renderType == null || shadeQuad == null) return false;

        int booleanLocal = method.maxLocals++;
        InsnList opaqueHook = new InsnList();
        opaqueHook.add(new VarInsnNode(Opcodes.ISTORE, booleanLocal));
        opaqueHook.add(new LdcInsnNode(id + ".force-opaque"));
        opaqueHook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        opaqueHook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        opaqueHook.add(new VarInsnNode(Opcodes.ILOAD, booleanLocal));
        opaqueHook.add(
                call(
                        "onBooleanValue",
                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Z)Z"));
        method.instructions.insert(forceOpaque, opaqueHook);

        int objectLocal = method.maxLocals++;
        InsnList layerHook = new InsnList();
        layerHook.add(new VarInsnNode(Opcodes.ASTORE, objectLocal));
        layerHook.add(new LdcInsnNode(id + ".layer"));
        layerHook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        layerHook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        layerHook.add(new VarInsnNode(Opcodes.ALOAD, objectLocal));
        layerHook.add(
                call(
                        "onObjectValue",
                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
        layerHook.add(
                new TypeInsnNode(
                        Opcodes.CHECKCAST,
                        "net/minecraft/client/renderer/chunk/ChunkSectionLayer"));
        method.instructions.insert(renderType, layerHook);

        InsnList alphaHook = new InsnList();
        alphaHook.add(new LdcInsnNode(id + ".alpha"));
        alphaHook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        alphaHook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        alphaHook.add(
                call("onVoidHook", "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"));
        method.instructions.insert(shadeQuad, alphaHook);
        return true;
    }

    private static boolean loadChamsItemRender(MethodNode method, String id) {
        InsnList start = new InsnList();
        start.add(new LdcInsnNode(id));
        start.add(new VarInsnNode(Opcodes.ALOAD, 0));
        start.add(new VarInsnNode(Opcodes.ALOAD, 3));
        start.add(call("onVoidHook", "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"));
        method.instructions.insert(start);
        beforeReturns(
                method,
                Opcodes.RETURN,
                () -> {
                    InsnList end = new InsnList();
                    end.add(new LdcInsnNode(id + ".end"));
                    end.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    end.add(new VarInsnNode(Opcodes.ALOAD, 3));
                    end.add(
                            call(
                                    "onVoidHook",
                                    "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"));
                    return end;
                });
        return loadInvocationArgumentRemap(method, id + ".type", "getBuffer", 0);
    }

    private static boolean loadChamsMarkItem26_2(MethodNode method, String id) {
        boolean sawSubmit = false;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof TypeInsnNode type
                    && instruction.getOpcode() == Opcodes.NEW
                    && type.desc.equals(
                            "net/minecraft/client/renderer/feature/ItemFeatureRenderer$Submit")) {
                sawSubmit = true;
                continue;
            }
            if (sawSubmit
                    && instruction instanceof VarInsnNode variable
                    && instruction.getOpcode() == Opcodes.ASTORE) {
                InsnList hook = new InsnList();
                hook.add(new LdcInsnNode(id));
                hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                hook.add(new VarInsnNode(Opcodes.ALOAD, variable.var));
                hook.add(
                        call(
                                "onVoidHook",
                                "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"));
                method.instructions.insert(instruction, hook);
                return true;
            }
        }
        return false;
    }

    /**
     * Minecraft 26.2 chooses the terrain buffer inside SectionCompiler's
     * BlockQuadOutput lambda. Keep both the alpha and layer rewrite at that
     * final output boundary instead of changing BakedQuad material globally.
     */
    private static boolean loadXraySectionQuad26_2(MethodNode method, String id) {
        MethodInsnNode layerCall = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode invocation
                    && invocation.owner.equals(
                            "net/minecraft/client/resources/model/geometry/BakedQuad$MaterialInfo")
                    && invocation.name.equals("layer")
                    && invocation.desc.equals(
                            "()Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;")) {
                layerCall = invocation;
                break;
            }
        }
        if (layerCall == null) return false;

        InsnList alphaHook = new InsnList();
        alphaHook.add(new LdcInsnNode(id + ".alpha"));
        alphaHook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        alphaHook.add(new VarInsnNode(Opcodes.ALOAD, 7));
        alphaHook.add(
                call("onVoidHook", "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"));
        method.instructions.insert(alphaHook);

        int layerLocal = method.maxLocals++;
        InsnList layerHook = new InsnList();
        layerHook.add(new VarInsnNode(Opcodes.ASTORE, layerLocal));
        layerHook.add(new LdcInsnNode(id + ".layer"));
        layerHook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        layerHook.add(new VarInsnNode(Opcodes.ALOAD, 6));
        layerHook.add(new VarInsnNode(Opcodes.ALOAD, layerLocal));
        layerHook.add(
                call(
                        "onObjectValue",
                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
        layerHook.add(
                new TypeInsnNode(
                        Opcodes.CHECKCAST,
                        "net/minecraft/client/renderer/chunk/ChunkSectionLayer"));
        method.instructions.insert(layerCall, layerHook);
        return true;
    }

    private static boolean loadChamsItemPrepare26_2(MethodNode method, String id) {
        boolean remapped = loadInvocationArgumentRemap(method, id + ".type", "getVertexBuilder", 0);
        remapped |= loadInvocationArgumentRemap(method, id + ".type", "getFoilBuffer", 0);
        if (!remapped) return false;
        loadVoidStartEndArgument(method, id);
        return true;
    }

    /** Rewrites one reference argument immediately before a selected invocation. */
    private static boolean loadInvocationArgumentRemap(
            MethodNode method, String id, String invocationName, int argumentIndex) {
        boolean changed = false;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode invocation)
                    || !invocation.name.equals(invocationName)) continue;
            org.objectweb.asm.Type[] arguments =
                    org.objectweb.asm.Type.getArgumentTypes(invocation.desc);
            if (argumentIndex < 0
                    || argumentIndex >= arguments.length
                    || arguments[argumentIndex].getSort() != org.objectweb.asm.Type.OBJECT)
                continue;

            int[] locals = new int[arguments.length];
            InsnList hook = new InsnList();
            for (int index = arguments.length - 1; index >= argumentIndex; index--) {
                locals[index] = method.maxLocals;
                method.maxLocals += arguments[index].getSize();
                hook.add(
                        new VarInsnNode(arguments[index].getOpcode(Opcodes.ISTORE), locals[index]));
            }
            hook.add(new LdcInsnNode(id));
            if ((method.access & Opcodes.ACC_STATIC) == 0) {
                hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
            } else {
                hook.add(new InsnNode(Opcodes.ACONST_NULL));
            }
            hook.add(new InsnNode(Opcodes.ACONST_NULL));
            hook.add(new VarInsnNode(Opcodes.ALOAD, locals[argumentIndex]));
            hook.add(
                    call(
                            "onObjectValue",
                            "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
            hook.add(
                    new TypeInsnNode(
                            Opcodes.CHECKCAST, arguments[argumentIndex].getInternalName()));
            for (int index = argumentIndex + 1; index < arguments.length; index++) {
                hook.add(new VarInsnNode(arguments[index].getOpcode(Opcodes.ILOAD), locals[index]));
            }
            method.instructions.insertBefore(invocation, hook);
            changed = true;
        }
        return changed;
    }

    private static boolean loadSimpleStartEnd(MethodNode method, String startName, String endName) {
        InsnList start = new InsnList();
        loadThisAndCall(start, startName);
        method.instructions.insert(start);
        beforeReturns(method, Opcodes.RETURN, () -> thisCall(endName));
        return true;
    }

    /** Resolves frame merges through the actual Genesis game loader without initializing classes. */
    private static final class LoaderAwareClassWriter extends ClassWriter {
        private final ClassLoader loader;

        private LoaderAwareClassWriter(ClassReader reader, int flags, ClassLoader loader) {
            super(reader, flags);
            this.loader = loader;
        }

        @Override
        protected String getCommonSuperClass(String firstName, String secondName) {
            if (loader == null) return "java/lang/Object";
            try {
                Class<?> first = Class.forName(firstName.replace('/', '.'), false, loader);
                Class<?> second = Class.forName(secondName.replace('/', '.'), false, loader);
                if (first.isAssignableFrom(second)) return firstName;
                if (second.isAssignableFrom(first)) return secondName;
                if (first.isInterface() || second.isInterface()) return "java/lang/Object";
                do {
                    first = first.getSuperclass();
                } while (first != null && !first.isAssignableFrom(second));
                return first == null ? "java/lang/Object" : first.getName().replace('.', '/');
            } catch (ClassNotFoundException | LinkageError unavailable) {
                // Object is a conservative verifier-safe merge when a host-generated
                // helper is not visible through the game loader.
                return "java/lang/Object";
            }
        }
    }

    private static boolean containsHook(MethodNode method, TargetMethod target) {
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
        if (target.hook() == TargetMethod.HookKind.TRIM_RENDER) {
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
                            OBJECT_ARGUMENT,
                            ITEM_STACK_ARGUMENT_5,
                            OBJECT_INVOKE_RETURN ->
                            "onObjectValue";
                    case NAMED_FLOAT_LOCAL -> "onFloatValue";
                    case SPRINT_DECISIONS -> "onBooleanValue";
                    case YAW_RESULT -> "onFloatValue";
                    case BOOLEAN_GATE, STATIC_BOOLEAN_RETURN_ARG1, XRAY_TESSELLATE, TRIM_RENDER ->
                            "onBooleanValue";
                    case XRAY_QUAD, XRAY_SECTION_QUAD_26_2 -> "onVoidHook";
                    case VOID_START_END_ARG0 -> "onVoidHook";
                    case CHAMS_FRAME -> "onVoidHook";
                    case SODIUM_RENDER_MODEL -> "onBooleanValue";
                    case SODIUM_PROCESS_QUAD -> "onBooleanValue";
                    case CHAMS_REMAP_SUBMIT_MODEL, CHAMS_FOIL_BUFFER, CHAMS_FOIL_BUFFER_26_2 ->
                            "onObjectValue";
                    case CHAMS_MARK_ITEM,
                            CHAMS_ITEM_RENDER,
                            CHAMS_MARK_ITEM_26_2,
                            CHAMS_ITEM_PREPARE_26_2,
                            HAND_ANIMATION ->
                            "onVoidHook";
                };
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals(BRIDGE)
                    && call.name.equals(hookName)) return true;
        }
        return false;
    }
}
