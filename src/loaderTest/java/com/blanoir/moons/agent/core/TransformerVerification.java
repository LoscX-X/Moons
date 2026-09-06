package com.blanoir.moons.agent.core;

import com.blanoir.moons.api.bridge.RuntimeBridge;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Build-time verification against real Minecraft bytecode, without Fabric. */
public final class TransformerVerification {
    private TransformerVerification() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 0) throw new IllegalArgumentException("Expected one or more Minecraft JAR paths");
        verifyBootstrapBridgeBoundary();
        PacketSendVerification.verify();
        MappingService mappings = VersionMappings.create();
        MoonsTransformer transformer = new MoonsTransformer(mappings);
        Set<String> classes = new LinkedHashSet<>();
        Set<String> availableClasses = new LinkedHashSet<>();
        Set<String> unchangedTargets = new LinkedHashSet<>();
        mappings.allTargets().forEach(target -> classes.addAll(target.classNames()));

        List<JarFile> minecraftJars = new ArrayList<>();
        try {
            for (String argument : arguments) minecraftJars.add(new JarFile(Path.of(argument).toFile()));
            for (String className : classes) {
                JarFile minecraft = null;
                JarEntry entry = null;
                for (JarFile candidate : minecraftJars) {
                    entry = candidate.getJarEntry(className + ".class");
                    if (entry != null) {
                        minecraft = candidate;
                        break;
                    }
                }
                if (entry == null) {
                    boolean required = mappings.targetsForClass(className).stream()
                            .anyMatch(TargetMethod::required);
                    if (required) throw new AssertionError("Target class is missing: " + className);
                    continue;
                }
                availableClasses.add(className);
                byte[] original;
                try (InputStream input = minecraft.getInputStream(entry)) {
                    original = input.readAllBytes();
                }
                byte[] transformed = transformer.transform(null, className, original);
                if (transformed == null) {
                    unchangedTargets.add(className);
                    continue;
                }
                assertStructureUnchanged(className, original, transformed);
                verify(className, transformed);
            }
        } finally {
            for (JarFile jar : minecraftJars) jar.close();
        }

        Set<String> expected = new LinkedHashSet<>();
        mappings.allTargets().stream()
                .filter(target -> target.classNames().stream().anyMatch(availableClasses::contains))
                .forEach(target -> expected.add(target.id()));
        if (!transformer.failedHooks().isEmpty() || !unchangedTargets.isEmpty()) {
            throw new AssertionError("Unavailable hooks=" + transformer.failedHooks()
                    + ", unchanged target classes=" + unchangedTargets);
        }
        if (!transformer.installedHooks().equals(expected)) {
            throw new AssertionError("Hook mismatch. expected=" + expected
                    + ", installed=" + transformer.installedHooks());
        }
        System.out.println("MOONS_TRANSFORMERS_VERIFIED hooks=" + expected.size()
                + " classes=" + availableClasses.size());
    }

    private static void verify(String className, byte[] bytecode) throws Exception {
        ClassReader reader = new ClassReader(bytecode);
        ClassNode node = new ClassNode();
        reader.accept(new CheckClassAdapter(node, false), 0);
        for (MethodNode method : node.methods) {
            if (method.instructions.size() == 0) continue;
            new Analyzer<>(new BasicInterpreter()).analyze(className, method);
        }
        verifyMovementHookOrdering(className, node);
    }

    private static void verifyMovementHookOrdering(String className, ClassNode node) {
        if (className.equals("net/minecraft/client/player/LocalPlayer")) {
            MethodNode update = findMethod(node, "tick", "()V");
            MethodNode input = findMethod(node, "aiStep", "()V");
            int updateHook = callIndex(update,
                    "com/blanoir/moons/api/bridge/AgentBridge", "onPlayerUpdate",
                    "(Ljava/lang/Object;)Z");
            if (updateHook < 0 || updateHook > 3) {
                throw new AssertionError("PlayerUpdate is not installed at LocalPlayer.tick head");
            }
            int inputTick = callIndex(input,
                    "net/minecraft/client/player/ClientInput", "tick", "()V");
            int inputHook = callIndex(input,
                    "com/blanoir/moons/api/bridge/AgentBridge", "onMoveInput",
                    "(Ljava/lang/Object;)V");
            if (inputTick < 0 || inputHook <= inputTick || inputHook - inputTick > 3) {
                throw new AssertionError("MoveInput is not installed immediately after ClientInput.tick");
            }
        }
        if (className.equals("net/minecraft/world/entity/Entity")) {
            MethodNode strafe = findMethod(node, "moveRelative",
                    "(FLnet/minecraft/world/phys/Vec3;)V");
            int eventHook = callIndex(strafe,
                    "com/blanoir/moons/api/bridge/AgentBridge", "onPlayerMove",
                    "(Ljava/lang/Object;FLjava/lang/Object;)[Ljava/lang/Object;");
            int scaleRead = callIndex(strafe,
                    "java/lang/Number", "floatValue", "()F");
            if (eventHook < 0 || scaleRead <= eventHook
                    || opcodeCountAfter(strafe, Opcodes.AALOAD, eventHook) < 2) {
                throw new AssertionError("StrafeEvent does not write scale and movement before moveRelative");
            }
        }
    }

    private static void verifyBootstrapBridgeBoundary() {
        for (java.lang.reflect.Method method : RuntimeBridge.class.getMethods()) {
            assertBootstrapType(method, method.getReturnType());
            for (Class<?> parameter : method.getParameterTypes()) {
                assertBootstrapType(method, parameter);
            }
        }
    }

    private static void assertBootstrapType(java.lang.reflect.Method method, Class<?> type) {
        Class<?> component = type;
        while (component.isArray()) component = component.getComponentType();
        if (component.isPrimitive() || component.getName().startsWith("java.")) return;
        throw new AssertionError("RuntimeBridge exposes non-bootstrap type "
                + component.getName() + " in " + method);
    }

    private static MethodNode findMethod(ClassNode node, String name, String descriptor) {
        return node.methods.stream()
                .filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing method " + node.name + "." + name + descriptor));
    }

    private static int callIndex(MethodNode method, String owner, String name, String descriptor) {
        int index = 0;
        for (org.objectweb.asm.tree.AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner)
                    && call.name.equals(name)
                    && call.desc.equals(descriptor)) return index;
            index++;
        }
        return -1;
    }

    private static int opcodeCountAfter(MethodNode method, int opcode, int afterIndex) {
        int index = 0;
        int count = 0;
        for (org.objectweb.asm.tree.AbstractInsnNode instruction : method.instructions) {
            if (index > afterIndex && instruction.getOpcode() == opcode) count++;
            index++;
        }
        return count;
    }

    private static void assertStructureUnchanged(
            String className,
            byte[] originalBytecode,
            byte[] transformedBytecode
    ) {
        ClassNode original = readStructure(originalBytecode);
        ClassNode transformed = readStructure(transformedBytecode);
        if (original.access != transformed.access
                || !java.util.Objects.equals(original.name, transformed.name)
                || !java.util.Objects.equals(original.superName, transformed.superName)
                || !original.interfaces.equals(transformed.interfaces)) {
            throw new AssertionError("Class structure changed for " + className);
        }
        List<String> originalFields = original.fields.stream()
                .map(field -> field.access + ":" + field.name + ":" + field.desc + ":" + field.signature)
                .toList();
        List<String> transformedFields = transformed.fields.stream()
                .map(field -> field.access + ":" + field.name + ":" + field.desc + ":" + field.signature)
                .toList();
        if (!originalFields.equals(transformedFields)) {
            throw new AssertionError("Fields changed for " + className);
        }
        List<String> originalMethods = original.methods.stream()
                .map(method -> method.access + ":" + method.name + ":" + method.desc + ":"
                        + method.signature + ":" + method.exceptions)
                .toList();
        List<String> transformedMethods = transformed.methods.stream()
                .map(method -> method.access + ":" + method.name + ":" + method.desc + ":"
                        + method.signature + ":" + method.exceptions)
                .toList();
        if (!originalMethods.equals(transformedMethods)) {
            throw new AssertionError("Methods changed for " + className);
        }
    }

    private static ClassNode readStructure(byte[] bytecode) {
        ClassNode node = new ClassNode();
        new ClassReader(bytecode).accept(
                node,
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }
}
