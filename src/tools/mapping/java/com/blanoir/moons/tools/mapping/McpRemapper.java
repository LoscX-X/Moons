package com.blanoir.moons.tools.mapping;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Build-time MCP remapping, including members accessed through subclasses. No game code is shipped. */
public final class McpRemapper {
    private record Member(String owner, String name, String descriptor) {}

    private record Hierarchy(String parent, List<String> interfaces, Set<Member> declarations) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 7)
            throw new IllegalArgumentException(
                    "forward|reverse srg fields.csv methods.csv input output hierarchy");
        boolean reverse =
                switch (args[0]) {
                    case "forward" -> false;
                    case "reverse" -> true;
                    default -> throw new IllegalArgumentException(args[0]);
                };
        Map<String, String> fields = names(Path.of(args[2])), methods = names(Path.of(args[3]));
        Map<String, String> classes = new HashMap<>();
        Map<Member, String> fieldMap = new HashMap<>(), methodMap = new HashMap<>();
        List<String> lines = Files.readAllLines(Path.of(args[1]));
        for (String line : lines) {
            String[] p = line.split(" ");
            if (p[0].equals("CL:")) classes.put(reverse ? p[2] : p[1], reverse ? p[1] : p[2]);
        }
        for (String line : lines) {
            String[] p = line.split(" ");
            if (p[0].equals("FD:")) {
                String[] obf = split(p[1]), srg = split(p[2]);
                String named = fields.getOrDefault(srg[1], srg[1]);
                fieldMap.put(
                        reverse ? new Member(srg[0], named, "") : new Member(obf[0], obf[1], ""),
                        reverse ? obf[1] : named);
            } else if (p[0].equals("MD:")) {
                String[] obf = split(p[1]), srg = split(p[3]);
                String named = methods.getOrDefault(srg[1], srg[1]);
                methodMap.put(
                        reverse
                                ? new Member(srg[0], named, p[4])
                                : new Member(obf[0], obf[1], p[2]),
                        reverse ? obf[1] : named);
            }
        }
        Map<String, Hierarchy> hierarchy = new HashMap<>();
        for (String file : new LinkedHashSet<>(List.of(args[6], args[4]))) {
            try (JarFile jar = new JarFile(file)) {
                for (JarEntry entry : Collections.list(jar.entries())) {
                    if (!entry.getName().endsWith(".class")) continue;
                    ClassReader reader;
                    try (InputStream in = jar.getInputStream(entry)) {
                        reader = new ClassReader(in);
                    }
                    Set<Member> declarations = new HashSet<>();
                    reader.accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public FieldVisitor visitField(
                                        int a, String n, String d, String s, Object v) {
                                    declarations.add(new Member(reader.getClassName(), n, ""));
                                    return null;
                                }

                                @Override
                                public MethodVisitor visitMethod(
                                        int a, String n, String d, String s, String[] e) {
                                    declarations.add(new Member(reader.getClassName(), n, d));
                                    return null;
                                }
                            },
                            ClassReader.SKIP_CODE
                                    | ClassReader.SKIP_DEBUG
                                    | ClassReader.SKIP_FRAMES);
                    hierarchy.put(
                            reader.getClassName(),
                            new Hierarchy(
                                    reader.getSuperName(),
                                    List.of(reader.getInterfaces()),
                                    declarations));
                }
            }
        }
        Remapper mapping =
                new Remapper(Opcodes.ASM9) {
                    @Override
                    public String map(String name) {
                        return classes.getOrDefault(name, name);
                    }

                    @Override
                    public String mapFieldName(String owner, String name, String descriptor) {
                        return inherited(fieldMap, owner, name, "", new HashSet<>());
                    }

                    @Override
                    public String mapMethodName(String owner, String name, String descriptor) {
                        return name.startsWith("<")
                                ? name
                                : inherited(methodMap, owner, name, descriptor, new HashSet<>());
                    }

                    private String inherited(
                            Map<Member, String> map,
                            String owner,
                            String name,
                            String descriptor,
                            Set<String> seen) {
                        if (owner == null || !seen.add(owner)) return name;
                        Member member = new Member(owner, name, descriptor);
                        String direct = map.get(member);
                        if (direct != null) return direct;
                        Hierarchy type = hierarchy.get(owner);
                        if (type == null) return name;
                        // Unmapped declarations can override mapped virtual methods. Fields never
                        // override.
                        if (descriptor.isEmpty() && type.declarations.contains(member)) return name;
                        String parent = inherited(map, type.parent, name, descriptor, seen);
                        if (!parent.equals(name)) return parent;
                        for (String itf : type.interfaces) {
                            String result = inherited(map, itf, name, descriptor, seen);
                            if (!result.equals(name)) return result;
                        }
                        return name;
                    }
                };
        Path output = Path.of(args[5]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Path temporary =
                Files.createTempFile(output.toAbsolutePath().getParent(), "mcp-remap-", ".jar");
        try {
            Set<String> emitted = new HashSet<>();
            try (JarFile input = new JarFile(args[4]);
                    JarOutputStream out = new JarOutputStream(Files.newOutputStream(temporary))) {
                List<JarEntry> entries = Collections.list(input.entries());
                entries.sort(Comparator.comparing(JarEntry::getName));
                for (JarEntry entry : entries) {
                    String name = entry.getName();
                    if (entry.isDirectory() || name.matches("META-INF/.*\\.(SF|RSA|DSA)")) continue;
                    byte[] bytes;
                    try (InputStream in = input.getInputStream(entry)) {
                        bytes = in.readAllBytes();
                    }
                    if (name.endsWith(".class")) {
                        ClassReader reader = new ClassReader(bytes);
                        ClassWriter writer = new ClassWriter(0);
                        reader.accept(new ClassRemapper(writer, mapping), 0);
                        bytes = writer.toByteArray();
                        name = mapping.map(reader.getClassName()) + ".class";
                    }
                    if (!emitted.add(name))
                        throw new IOException("Duplicate remapped entry: " + name);
                    JarEntry mapped = new JarEntry(name);
                    mapped.setTime(0);
                    out.putNextEntry(mapped);
                    out.write(bytes);
                    out.closeEntry();
                }
            }
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        System.out.println("MCP " + args[0] + ": " + output);
    }

    private static Map<String, String> names(Path csv) throws IOException {
        Map<String, String> result = new HashMap<>();
        for (String line : Files.readAllLines(csv)) {
            String[] p = line.split(",", 3);
            if (p.length >= 2) result.put(p[0], p[1]);
        }
        return result;
    }

    private static String[] split(String qualified) {
        int i = qualified.lastIndexOf('/');
        return new String[] {qualified.substring(0, i), qualified.substring(i + 1)};
    }
}
