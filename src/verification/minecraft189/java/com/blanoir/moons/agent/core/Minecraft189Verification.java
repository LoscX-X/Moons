package com.blanoir.moons.agent.core;

import com.blanoir.moons.build.McpHookTable;
import com.blanoir.moons.build.McpRemapper;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Offline validation of production mappings, transformer, bridge ABI and reverse remapping. */
public final class Minecraft189Verification implements Opcodes {
    private static final String BRIDGE = "com/blanoir/moons/api/bridge/AgentBridge";
    private static final Set<String> BRIDGE_METHODS = bridgeMethods();
    private static int analyzed, calls;

    public static void main(String[] args) throws Exception {
        if (args.length < 2)
            throw new IllegalArgumentException(
                    "input-directory report-directory [named-payload.jar ...]");
        Path root = Path.of(args[0]), output = Path.of(args[1]);
        Files.createDirectories(output);
        // Regenerate independently so stale generated tables cannot hide missing targets.
        Path generated = output.resolve("generated/moons/1_8/hooks.tsv");
        McpHookTable.main(new String[] {root.toString(), generated.toString()});
        List<String> expected = Files.readAllLines(generated);
        try (var resource = VersionMappings.class.getResourceAsStream("/moons/1_8/hooks.tsv")) {
            if (resource == null) throw new AssertionError("Production hook resource missing");
            List<String> actual =
                    new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                            .lines()
                            .toList();
            if (!actual.equals(expected))
                throw new AssertionError(
                        "Production hooks.tsv is stale; regenerateMinecraft189Hooks");
        }
        MappingService mappings = VersionMappings.create();
        verifyNamespace(root.resolve("minecraft-1.8.9-named.jar"), root, mappings, false, output);
        verifyNamespace(root.resolve("client.jar"), root, mappings, true, output);
        Path roundTrip = output.resolve("game-roundtrip-obfuscated.jar");
        remap(root, root.resolve("minecraft-1.8.9-named.jar"), roundTrip);
        compareGame(root.resolve("client.jar"), roundTrip);
        verifyInheritedProbe(root, output);
        for (int i = 2; i < args.length; i++) {
            Path payload = Path.of(args[i]),
                    remapped = output.resolve("obfuscated-" + payload.getFileName());
            remap(root, payload, remapped);
            verifyGameLinkage(remapped, root.resolve("client.jar"));
        }
        String result =
                "MINECRAFT189_VERIFIED targets="
                        + expected.size()
                        + " namespaces=2 analyzed-methods="
                        + analyzed
                        + " bridge-calls="
                        + calls
                        + " reverse-remap=all-game-classes inherited-linkage=passed payloads="
                        + (args.length - 2);
        Files.writeString(output.resolve("result.txt"), result + System.lineSeparator());
        System.out.println(result);
    }

    private static void verifyNamespace(
            Path jarPath, Path root, MappingService mappings, boolean obfuscated, Path output)
            throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(jarPath.toUri().toURL());
        try (var files = Files.list(root.resolve("libraries"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".jar")).toList())
                urls.add(file.toUri().toURL());
        }
        // Resources, not class initialization, drive the production frame merge resolver.
        try (var loader =
                        new URLClassLoader(
                                urls.toArray(URL[]::new),
                                Minecraft189Verification.class.getClassLoader());
                JarFile jar = new JarFile(jarPath.toFile())) {
            MoonsTransformer transformer = new MoonsTransformer(mappings);
            Set<String> ids = new LinkedHashSet<>();
            int classes = 0;
            List<String> failures = new ArrayList<>();
            for (String owner : mappings.targetClassNames()) {
                if (owner.startsWith("net/minecraft/") == obfuscated) continue;
                JarEntry entry = jar.getJarEntry(owner + ".class");
                if (entry == null) {
                    failures.add("Missing target class " + owner);
                    continue;
                }
                byte[] original;
                try (var stream = jar.getInputStream(entry)) {
                    original = stream.readAllBytes();
                }
                byte[] transformed = transformer.transform(loader, owner, original);
                if (transformed == null) {
                    failures.add("No transformed bytecode: " + owner);
                    continue;
                }
                classes++;
                try {
                    ClassNode before = read(original), after = read(transformed);
                    if (!declarations(before).equals(declarations(after)))
                        throw new AssertionError("Class schema changed");
                    new ClassReader(transformed)
                            .accept(
                                    new CheckClassAdapter(new ClassWriter(0), false),
                                    ClassReader.EXPAND_FRAMES);
                    for (MethodNode method : after.methods) {
                        if ((method.access & (ACC_NATIVE | ACC_ABSTRACT)) != 0) continue;
                        new Analyzer<>(new BasicVerifier()).analyze(owner, method);
                        analyzed++;
                        for (AbstractInsnNode ins : method.instructions)
                            if (ins instanceof MethodInsnNode call && call.owner.equals(BRIDGE)) {
                                calls++;
                                if (call.getOpcode() != INVOKESTATIC
                                        || !BRIDGE_METHODS.contains(call.name + call.desc))
                                    throw new AssertionError(
                                            "Missing bridge ABI " + call.name + call.desc);
                            }
                    }
                    for (TargetMethod target : mappings.targetsForClass(owner)) {
                        ids.add(target.id());
                        MethodNode method =
                                after.methods.stream()
                                        .filter(m -> target.matchesMethod(m.name, m.desc))
                                        .findFirst()
                                        .orElseThrow();
                        if (target.id().startsWith("packet.apply.")) verifyPacketApply(method);
                    }
                    if (transformer.transform(loader, owner, transformed) != null)
                        throw new AssertionError("Retransformation duplicated hooks");
                } catch (Throwable failure) {
                    failures.add(owner + ": " + failure);
                }
            }
            failures.addAll(transformer.failedHooks());
            if (!transformer.installedHooks().equals(ids))
                failures.add(
                        "Installed hook set mismatch: "
                                + difference(ids, transformer.installedHooks()));
            String namespace = obfuscated ? "obfuscated" : "named";
            Files.write(output.resolve(namespace + "-failures.txt"), failures);
            if (!failures.isEmpty())
                throw new AssertionError(namespace + " validation failed: " + failures);
            System.out.println(
                    "MINECRAFT189_HOOKS namespace="
                            + namespace
                            + " classes="
                            + classes
                            + " installed="
                            + ids.size()
                            + " schema+flow+bridge+idempotence=passed");
        }
    }

    private static void verifyPacketApply(MethodNode method) {
        MethodInsnNode bridge = null;
        for (AbstractInsnNode ins : method.instructions)
            if (ins instanceof MethodInsnNode call
                    && call.owner.equals(BRIDGE)
                    && call.name.equals("onPacketApply")) {
                if (bridge != null) throw new AssertionError("Duplicate packet-apply callback");
                bridge = call;
            }
        if (bridge == null) throw new AssertionError("Missing packet-apply callback");
        AbstractInsnNode second = previousCode(bridge),
                first = previousCode(second),
                handoff = previousCode(first);
        if (!(second instanceof VarInsnNode b && b.getOpcode() == ALOAD && b.var == 0)
                || !(first instanceof VarInsnNode a && a.getOpcode() == ALOAD && a.var == 1)
                || !(handoff instanceof MethodInsnNode invoke
                        && invoke.getOpcode() == INVOKESTATIC
                        && Type.getArgumentTypes(invoke.desc).length == 3))
            throw new AssertionError(
                    "Packet callback must run immediately after thread handoff and before vanilla mutation");
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode start) {
        AbstractInsnNode previous = start.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous;
    }

    private static Set<String> difference(Set<String> expected, Set<String> actual) {
        var result = new TreeSet<>(expected);
        result.removeAll(actual);
        return result;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static Set<String> declarations(ClassNode node) {
        Set<String> result = new TreeSet<>();
        result.add(
                "C "
                        + node.name
                        + " "
                        + node.superName
                        + " "
                        + node.interfaces
                        + " "
                        + node.access);
        for (FieldNode f : node.fields) result.add("F " + f.name + f.desc + " " + f.access);
        for (MethodNode m : node.methods) result.add("M " + m.name + m.desc + " " + m.access);
        return result;
    }

    private static Set<String> bridgeMethods() {
        try (var input =
                Minecraft189Verification.class
                        .getClassLoader()
                        .getResourceAsStream(BRIDGE + ".class")) {
            if (input == null)
                throw new IllegalStateException("AgentBridge is not on the verifier classpath");
            Set<String> methods = new HashSet<>();
            for (MethodNode method : read(input.readAllBytes()).methods)
                if ((method.access & (ACC_PUBLIC | ACC_STATIC)) == (ACC_PUBLIC | ACC_STATIC))
                    methods.add(method.name + method.desc);
            return methods;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void remap(Path root, Path input, Path output) throws Exception {
        McpRemapper.main(
                new String[] {
                    "reverse",
                    root.resolve("joined.srg").toString(),
                    root.resolve("fields.csv").toString(),
                    root.resolve("methods.csv").toString(),
                    input.toString(),
                    output.toString(),
                    root.resolve("minecraft-1.8.9-named.jar").toString()
                });
    }

    private static void compareGame(Path original, Path remapped) throws Exception {
        int classes = 0;
        List<String> failures = new ArrayList<>();
        try (JarFile a = new JarFile(original.toFile());
                JarFile b = new JarFile(remapped.toFile())) {
            for (JarEntry entry : Collections.list(a.entries())) {
                if (!entry.getName().endsWith(".class")) continue;
                JarEntry other = b.getJarEntry(entry.getName());
                if (other == null) {
                    failures.add("Lost class " + entry.getName());
                    continue;
                }
                ClassNode first, second;
                try (var in = a.getInputStream(entry)) {
                    first = read(in.readAllBytes());
                }
                try (var in = b.getInputStream(other)) {
                    second = read(in.readAllBytes());
                }
                if (!declarations(first).equals(declarations(second)))
                    failures.add("Declaration roundtrip " + first.name);
                if (!references(first).equals(references(second)))
                    failures.add("Reference roundtrip " + first.name);
                classes++;
            }
        }
        if (!failures.isEmpty())
            throw new AssertionError("MCP reverse remap failures: " + failures);
        System.out.println(
                "MINECRAFT189_REMAP classes="
                        + classes
                        + " declarations+member-references=identical");
    }

    private static List<String> references(ClassNode node) {
        List<String> refs = new ArrayList<>();
        for (MethodNode method : node.methods)
            for (AbstractInsnNode ins : method.instructions) {
                if (ins instanceof FieldInsnNode f)
                    refs.add(f.getOpcode() + " " + f.owner + "." + f.name + f.desc);
                if (ins instanceof MethodInsnNode m)
                    refs.add(m.getOpcode() + " " + m.owner + "." + m.name + m.desc);
                if (ins instanceof TypeInsnNode t) refs.add(t.getOpcode() + " " + t.desc);
                if (ins instanceof InvokeDynamicInsnNode dynamic)
                    refs.add(
                            "D "
                                    + dynamic.name
                                    + dynamic.desc
                                    + dynamic.bsm
                                    + Arrays.toString(dynamic.bsmArgs));
            }
        return refs;
    }

    private static void verifyInheritedProbe(Path root, Path output) throws Exception {
        ClassWriter writer = new ClassWriter(0);
        String player = "net/minecraft/client/entity/EntityPlayerSP";
        writer.visit(
                V1_8, ACC_PUBLIC, "verification/InheritedProbe", null, "java/lang/Object", null);
        MethodVisitor method =
                writer.visitMethod(
                        ACC_PUBLIC | ACC_STATIC, "read", "(L" + player + ";)D", null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(
                INVOKEVIRTUAL, player, "getHeldItem", "()Lnet/minecraft/item/ItemStack;", false);
        method.visitInsn(POP);
        method.visitVarInsn(ALOAD, 0);
        method.visitFieldInsn(GETFIELD, player, "posX", "D");
        method.visitInsn(DRETURN);
        method.visitMaxs(2, 1);
        method.visitEnd();
        writer.visitEnd();
        Path probe = output.resolve("inherited-probe.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(probe))) {
            jar.putNextEntry(new JarEntry("verification/InheritedProbe.class"));
            jar.write(writer.toByteArray());
            jar.closeEntry();
        }
        Path remapped = output.resolve("inherited-probe-obfuscated.jar");
        remap(root, probe, remapped);
        verifyGameLinkage(remapped, root.resolve("client.jar"));
    }

    private static void verifyGameLinkage(Path payload, Path game) throws Exception {
        Map<String, ClassNode> gameClasses = index(game),
                payloadClasses = index(payload),
                all = new HashMap<>(gameClasses);
        all.putAll(payloadClasses);
        int references = 0;
        List<String> failures = new ArrayList<>();
        for (ClassNode node : payloadClasses.values())
            for (MethodNode method : node.methods)
                for (AbstractInsnNode ins : method.instructions) {
                    String owner = null, name = null, desc = null;
                    boolean field = false;
                    if (ins instanceof MethodInsnNode m) {
                        owner = m.owner;
                        name = m.name;
                        desc = m.desc;
                    }
                    if (ins instanceof FieldInsnNode f) {
                        owner = f.owner;
                        name = f.name;
                        desc = f.desc;
                        field = true;
                    }
                    if (owner == null) continue;
                    if (owner.startsWith("net/minecraft/")) {
                        failures.add(
                                "Unmapped named owner "
                                        + node.name
                                        + " -> "
                                        + owner
                                        + "."
                                        + name
                                        + desc);
                        continue;
                    }
                    if (!gameClasses.containsKey(owner)
                            && !inheritsGame(owner, all, new HashSet<>())) continue;
                    references++;
                    if (!memberExists(owner, name, desc, field, all, new HashSet<>()))
                        failures.add(
                                node.name + "." + method.name + " -> " + owner + "." + name + desc);
                }
        if (!failures.isEmpty())
            throw new AssertionError(
                    "Obfuscated linkage failures ("
                            + failures.size()
                            + "): "
                            + failures.stream().limit(80).toList());
        System.out.println(
                "MINECRAFT189_LINKAGE payload="
                        + payload.getFileName()
                        + " game-member-references="
                        + references
                        + " resolved");
    }

    private static boolean inheritsGame(
            String owner, Map<String, ClassNode> classes, Set<String> seen) {
        if (owner == null || !seen.add(owner)) return false;
        ClassNode node = classes.get(owner);
        if (node == null) return false;
        if (owner.indexOf('/') < 0) return true;
        if (inheritsGame(node.superName, classes, seen)) return true;
        for (String itf : node.interfaces) if (inheritsGame(itf, classes, seen)) return true;
        return false;
    }

    private static boolean memberExists(
            String owner,
            String name,
            String descriptor,
            boolean field,
            Map<String, ClassNode> classes,
            Set<String> seen) {
        if (owner == null || !seen.add(owner)) return false;
        ClassNode node = classes.get(owner);
        if (node == null) {
            try (var stream =
                    Minecraft189Verification.class
                            .getClassLoader()
                            .getResourceAsStream(owner + ".class")) {
                if (stream != null) {
                    node = read(stream.readAllBytes());
                    classes.put(owner, node);
                }
            } catch (IOException ignored) {
            }
        }
        if (node == null) return false;
        if (field) {
            for (FieldNode candidate : node.fields)
                if (candidate.name.equals(name) && candidate.desc.equals(descriptor)) return true;
        } else {
            for (MethodNode candidate : node.methods)
                if (candidate.name.equals(name) && candidate.desc.equals(descriptor)) return true;
        }
        if (name.equals("<init>")) return false;
        if (memberExists(node.superName, name, descriptor, field, classes, seen)) return true;
        for (String itf : node.interfaces)
            if (memberExists(itf, name, descriptor, field, classes, seen)) return true;
        return false;
    }

    private static Map<String, ClassNode> index(Path jar) throws Exception {
        Map<String, ClassNode> result = new HashMap<>();
        try (JarFile input = new JarFile(jar.toFile())) {
            for (JarEntry entry : Collections.list(input.entries()))
                if (entry.getName().endsWith(".class"))
                    try (var in = input.getInputStream(entry)) {
                        var node = read(in.readAllBytes());
                        result.put(node.name, node);
                    }
        }
        return result;
    }
}
