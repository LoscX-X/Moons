package com.blanoir.moons.agent.core;

import com.blanoir.moons.api.AgentMode;
import com.blanoir.moons.api.bridge.AgentBridge;
import com.blanoir.moons.api.bridge.RuntimeBridge;
import com.blanoir.moons.runtime.DefaultRuntimeBridge;
import com.blanoir.moons.runtime.lifecycle.DefaultResourceScope;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

/** Executes transformed methods to check inactive allocation guards and paired cleanup. */
public final class HookGuardVerification {
    private static final String OWNER = HookGuardVerification.class.getName().replace('.', '/');
    private static final String FIXTURE = "com/blanoir/moons/agent/core/HookGuardFixture";
    private static final String XRAY = "xray.block-tessellate";
    private static final String SCOREBOARD = "render.scoreboard";
    private static final String TESSELLATE_DESCRIPTOR = "(" + "Ljava/lang/Object;".repeat(7) + ")V";
    private static boolean enabled;
    private static boolean background;
    private static boolean cancel;
    private static boolean disableDuringBody;
    private static int arrays;
    private static int bodies;
    private static int starts;
    private static int ends;

    private HookGuardVerification() {}

    static void verify() throws Exception {
        Class<?> fixture = createFixture();
        Object instance = fixture.getConstructor().newInstance();
        Method tessellate =
                fixture.getMethod(
                        "tessellate",
                        Object.class,
                        Object.class,
                        Object.class,
                        Object.class,
                        Object.class,
                        Object.class,
                        Object.class);
        Method scoreboard = fixture.getMethod("scoreboard", Object.class, Object.class);
        DefaultRuntimeBridge runtime =
                new DefaultRuntimeBridge(
                        AgentMode.JVMTI, Path.of("."), Path.of("unused-fixture.jar"), "fixture");
        DefaultResourceScope resources = new DefaultResourceScope();
        resources.own(
                runtime.events()
                        .methodHook()
                        .subscribe(
                                key ->
                                        key.equals(XRAY + ".end")
                                                ? background
                                                : enabled
                                                        && (key.equals(XRAY)
                                                                || key.equals(SCOREBOARD)),
                                event -> {
                                    if (event.id().equals(XRAY + ".end")) {
                                        background = false;
                                        ends++;
                                    } else if (event.id().equals(XRAY)) {
                                        starts++;
                                        background = true;
                                        if (cancel) event.value(false);
                                    } else if (event.id().equals(SCOREBOARD)) {
                                        starts++;
                                        if (cancel) event.value(true);
                                    }
                                }));
        RuntimeBridge previous = AgentBridge.install(runtime);
        try {
            reset();
            enabled = false;
            tessellate.invoke(instance, new Object[7]);
            scoreboard.invoke(instance, null, null);
            require(
                    arrays == 0 && bodies == 2 && starts == 0 && ends == 0,
                    "Disabled features must skip argument arrays and execute vanilla bodies");
            Object original = new Object();
            require(
                    AgentBridge.onObjectValue(XRAY, null, null, original) == original,
                    "Inactive object hooks must preserve the original reference");
            require(
                    AgentBridge.onBooleanValue(XRAY, null, null, true),
                    "Inactive boolean hooks must preserve true");
            require(
                    !AgentBridge.onBooleanValue(XRAY, null, null, false),
                    "Inactive boolean hooks must preserve false");
            require(
                    Float.floatToRawIntBits(AgentBridge.onFloatValue(XRAY, null, 1.0F, -0.0F))
                            == Float.floatToRawIntBits(-0.0F),
                    "Inactive float hooks must preserve the exact original value");

            reset();
            enabled = true;
            tessellate.invoke(instance, new Object[7]);
            Object[] alternateReturn = new Object[7];
            alternateReturn[0] = new Object();
            tessellate.invoke(instance, alternateReturn);
            scoreboard.invoke(instance, null, null);
            require(
                    arrays == 3 && bodies == 3 && starts == 3 && ends == 2 && !background,
                    "Active hooks must allocate, retain vanilla behavior and close both returns");

            reset();
            cancel = true;
            tessellate.invoke(instance, new Object[7]);
            scoreboard.invoke(instance, null, null);
            require(
                    arrays == 2 && bodies == 0 && starts == 2 && ends == 1 && !background,
                    "Cancellation must skip vanilla and close a started tessellation");

            reset();
            disableDuringBody = true;
            tessellate.invoke(instance, new Object[7]);
            require(
                    !enabled && arrays == 1 && bodies == 1 && ends == 1 && !background,
                    "Disabling during vanilla execution must still dispatch pending END cleanup");
            tessellate.invoke(instance, new Object[7]);
            require(
                    arrays == 1 && bodies == 2 && ends == 1,
                    "The next disabled invocation must not allocate or repeat cleanup");

            reset();
            int[] observed = {0};
            var observer = runtime.events().methodHook().subscribe(event -> observed[0]++);
            tessellate.invoke(instance, new Object[7]);
            require(
                    arrays == 1 && bodies == 1 && starts == 0 && ends == 0 && observed[0] == 2,
                    "Unfiltered external subscribers must receive START and END while disabled");
            observer.close();
            tessellate.invoke(instance, new Object[7]);
            require(
                    arrays == 1 && bodies == 2 && observed[0] == 2,
                    "Closing an external subscription must restore the allocation guard");

            reset();
            enabled = true;
            resources.close();
            tessellate.invoke(instance, new Object[7]);
            require(
                    arrays == 0 && bodies == 1 && !AgentBridge.isHookActive(XRAY),
                    "Closing a module scope must remove hook interest immediately");
            AgentBridge.uninstall(runtime);
            scoreboard.invoke(instance, null, null);
            require(
                    arrays == 0 && bodies == 2 && !AgentBridge.isHookActive(SCOREBOARD),
                    "An uninstalled runtime must leave vanilla code and skip allocations");

            RuntimeBridge legacy = new RuntimeBridge() {};
            AgentBridge.install(legacy);
            reset();
            tessellate.invoke(instance, new Object[7]);
            require(
                    arrays == 1 && bodies == 1,
                    "Runtime implementations without a filter override must remain compatible");
        } finally {
            AgentBridge.install(previous);
            resources.close();
            runtime.close();
            enabled = background = false;
        }
        System.out.println("HOOK_ALLOCATION_GUARDS_VERIFIED");
    }

    private static Class<?> createFixture() {
        ClassWriter original =
                new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        original.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, FIXTURE, null, "java/lang/Object", null);
        var constructor = original.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        body("tessellate", TESSELLATE_DESCRIPTOR, true).accept(original);
        body("scoreboard", "(Ljava/lang/Object;Ljava/lang/Object;)V", false).accept(original);
        original.visitEnd();
        MappingService mappings =
                new MappingService(
                        List.of(
                                new TargetMethod(
                                        XRAY,
                                        List.of(FIXTURE),
                                        List.of("tessellate"),
                                        TESSELLATE_DESCRIPTOR,
                                        TargetMethod.HookKind.XRAY_TESSELLATE),
                                new TargetMethod(
                                        SCOREBOARD,
                                        List.of(FIXTURE),
                                        List.of("scoreboard"),
                                        "(Ljava/lang/Object;Ljava/lang/Object;)V",
                                        TargetMethod.HookKind.SCOREBOARD)));
        MoonsTransformer transformer = new MoonsTransformer(mappings);
        byte[] transformed =
                transformer.transform(
                        HookGuardVerification.class.getClassLoader(),
                        FIXTURE,
                        original.toByteArray());
        require(
                transformed != null && transformer.failedHooks().isEmpty(),
                "Allocation fixture must be transformed successfully");

        // Count actual executed ANEWARRAY instructions without changing their operand stack.
        // Preserve the transformer's frames so JVM loading also checks the new branch metadata.
        ClassNode node = new ClassNode();
        new ClassReader(transformed).accept(node, 0);
        int instrumented = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction.getOpcode() != Opcodes.ANEWARRAY) continue;
                method.instructions.insert(
                        instruction,
                        new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "allocated", "()V", false));
                instrumented++;
            }
        }
        require(instrumented == 2, "Fixture must exercise both real injected argument arrays");
        ClassWriter counted = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(counted);
        byte[] code = counted.toByteArray();
        return new ClassLoader(HookGuardVerification.class.getClassLoader()) {
            Class<?> define() {
                return defineClass(FIXTURE.replace('/', '.'), code, 0, code.length);
            }
        }.define();
    }

    private static MethodNode body(String name, String descriptor, boolean multipleReturns) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        method.instructions.add(
                new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "vanillaBody", "()V", false));
        if (multipleReturns) {
            LabelNode alternate = new LabelNode();
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            method.instructions.add(new JumpInsnNode(Opcodes.IFNONNULL, alternate));
            method.instructions.add(new InsnNode(Opcodes.RETURN));
            method.instructions.add(alternate);
        }
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        return method;
    }

    public static void allocated() {
        arrays++;
    }

    public static void vanillaBody() {
        bodies++;
        if (disableDuringBody) enabled = false;
    }

    private static void reset() {
        arrays = bodies = starts = ends = 0;
        cancel = disableDuringBody = background = false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
