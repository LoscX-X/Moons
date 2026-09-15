package com.blanoir.moons.agent.core.hooks;

import static com.blanoir.moons.agent.core.hooks.HookInstructions.appendVoidCancellation;
import static com.blanoir.moons.agent.core.hooks.HookInstructions.beforeReturns;
import static com.blanoir.moons.agent.core.hooks.HookInstructions.call;
import static com.blanoir.moons.agent.core.hooks.HookInstructions.guardHook;
import static com.blanoir.moons.agent.core.hooks.HookInstructions.loadThisAndCall;
import static com.blanoir.moons.agent.core.hooks.HookInstructions.thisCall;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.function.Supplier;

/** Reusable method gates, argument rewrites and return-value hooks. */
final class ValueHooks {
    private ValueHooks() {}

    /** A cancellable render entrypoint using method-body changes only; safe for already loaded classes. */
    static boolean loadBoxedArgumentsGate(MethodNode method, String id) {
        var arguments = org.objectweb.asm.Type.getArgumentTypes(method.desc);
        if ((method.access & Opcodes.ACC_STATIC) != 0 || !method.desc.endsWith(")V")) return false;
        InsnList hook = new InsnList();
        hook.add(new LdcInsnNode(id));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new LdcInsnNode(arguments.length));
        hook.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        int slot = 1;
        for (int index = 0; index < arguments.length; index++) {
            hook.add(new InsnNode(Opcodes.DUP));
            hook.add(new LdcInsnNode(index));
            var argument = arguments[index];
            hook.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slot));
            slot += argument.getSize();
            String wrapper =
                    switch (argument.getSort()) {
                        case org.objectweb.asm.Type.BOOLEAN -> "java/lang/Boolean";
                        case org.objectweb.asm.Type.BYTE -> "java/lang/Byte";
                        case org.objectweb.asm.Type.CHAR -> "java/lang/Character";
                        case org.objectweb.asm.Type.SHORT -> "java/lang/Short";
                        case org.objectweb.asm.Type.INT -> "java/lang/Integer";
                        case org.objectweb.asm.Type.FLOAT -> "java/lang/Float";
                        case org.objectweb.asm.Type.LONG -> "java/lang/Long";
                        case org.objectweb.asm.Type.DOUBLE -> "java/lang/Double";
                        default -> null;
                    };
            if (wrapper != null)
                hook.add(
                        new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                wrapper,
                                "valueOf",
                                "(" + argument.getDescriptor() + ")L" + wrapper + ";",
                                false));
            hook.add(new InsnNode(Opcodes.AASTORE));
        }
        hook.add(new InsnNode(Opcodes.ICONST_1));
        hook.add(
                call(
                        "onBooleanValue",
                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Z)Z"));
        appendVoidCancellation(hook);
        method.instructions.insert(guardHook(id, hook));
        return true;
    }

    static boolean loadVoidHook(MethodNode method, String id, boolean atReturn) {
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

    static boolean loadFloatObjectReturn(MethodNode method, String id) {
        int result = method.maxLocals++;
        beforeReturns(
                method,
                Opcodes.FRETURN,
                () -> {
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(Opcodes.FSTORE, result));
                    hook.add(new LdcInsnNode(id));
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    hook.add(new VarInsnNode(Opcodes.FLOAD, result));
                    hook.add(
                            new MethodInsnNode(
                                    Opcodes.INVOKESTATIC,
                                    "java/lang/Float",
                                    "valueOf",
                                    "(F)Ljava/lang/Float;",
                                    false));
                    hook.add(
                            call(
                                    "onObjectValue",
                                    "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
                    hook.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Float"));
                    hook.add(
                            new MethodInsnNode(
                                    Opcodes.INVOKEVIRTUAL,
                                    "java/lang/Float",
                                    "floatValue",
                                    "()F",
                                    false));
                    return hook;
                });
        return true;
    }

    static boolean loadFloatReturn(MethodNode method, String id) {
        return loadFloatReturn(method, id, true);
    }

    static boolean loadFloatReturn(MethodNode method, String id, boolean hasFloatArgument) {
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
    static boolean loadFloatHeadBooleanGate(MethodNode method, String id) {
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

    static boolean loadBooleanReturn(MethodNode method, String id) {
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

    static boolean loadObjectReturn(MethodNode method, String id) {
        int result = method.maxLocals++;
        String returnType = org.objectweb.asm.Type.getReturnType(method.desc).getInternalName();
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        var arguments = org.objectweb.asm.Type.getArgumentTypes(method.desc);
        boolean hasArgument =
                arguments.length > 0
                        && (arguments[0].getSort() == org.objectweb.asm.Type.OBJECT
                                || arguments[0].getSort() == org.objectweb.asm.Type.ARRAY);
        beforeReturns(
                method,
                Opcodes.ARETURN,
                () -> {
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(Opcodes.ASTORE, result));
                    hook.add(new LdcInsnNode(id));
                    hook.add(
                            isStatic
                                    ? new InsnNode(Opcodes.ACONST_NULL)
                                    : new VarInsnNode(Opcodes.ALOAD, 0));
                    hook.add(
                            hasArgument
                                    ? new VarInsnNode(Opcodes.ALOAD, isStatic ? 0 : 1)
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

    static boolean loadObjectArgument(MethodNode method, String id) {
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

    static boolean loadNamedFloatLocal(MethodNode method, String id, String localName) {
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

    static boolean loadBooleanGate(MethodNode method, String id) {
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

    static boolean loadStaticBooleanReturn(MethodNode method, String id) {
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

    static boolean loadVoidStartEndArgument(MethodNode method, String id) {
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

    /** Rewrites one reference argument immediately before a selected invocation. */
    static boolean loadInvocationArgumentRemap(
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

    static boolean loadSimpleStartEnd(MethodNode method, String startName, String endName) {
        InsnList start = new InsnList();
        loadThisAndCall(start, startName);
        method.instructions.insert(start);
        beforeReturns(method, Opcodes.RETURN, () -> thisCall(endName));
        return true;
    }
}
