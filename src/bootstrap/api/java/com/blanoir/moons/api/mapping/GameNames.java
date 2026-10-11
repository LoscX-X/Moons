package com.blanoir.moons.api.mapping;

import java.io.*;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** JDK-only version name table for private-member lookup and bytecode transformation. */
public final class GameNames {
    private static final Map<String, String> NAMED = new HashMap<>(), RUNTIME = new HashMap<>();
    private static final Map<String, String> FIELDS = new HashMap<>(), METHODS = new HashMap<>();
    private static final Map<String, String> REVERSE_FIELDS = new HashMap<>(),
            REVERSE_METHODS = new HashMap<>();
    private static final Map<String, List<String>> PARENTS = new HashMap<>();

    static {
        try (var input = GameNames.class.getResourceAsStream("/META-INF/moons/game-names.tsv")) {
            if (input == null) throw new IOException("Missing version name table");
            try (var reader =
                    new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                if (!"moons-game-names\t1\t1.21.11".equals(reader.readLine()))
                    throw new IOException("Wrong game name table");
                for (String line; (line = reader.readLine()) != null; ) {
                    var p = line.split("\t", -1);
                    switch (p[0]) {
                        case "C" -> {
                            RUNTIME.put(p[1], p[2]);
                            NAMED.put(p[2], p[1]);
                        }
                        case "F", "M" -> {
                            var forward = p[0].equals("F") ? FIELDS : METHODS;
                            var reverse = p[0].equals("F") ? REVERSE_FIELDS : REVERSE_METHODS;
                            forward.put(key(p[1], p[2], p[3]), p[4]);
                            reverse.put(key(p[1], p[4], p[3]), p[2]);
                        }
                        case "H" ->
                                PARENTS.put(
                                        p[1],
                                        Arrays.stream(p)
                                                .skip(2)
                                                .filter(s -> !s.isEmpty())
                                                .toList());
                        default -> throw new IOException("Unknown name entry");
                    }
                }
            }
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private GameNames() {}

    private static String key(String owner, String name, String descriptor) {
        return owner + '\t' + name + '\t' + descriptor;
    }

    public static String namedType(String name) {
        return NAMED.getOrDefault(name, name);
    }

    public static String runtimeType(String name) {
        return RUNTIME.getOrDefault(name, name);
    }

    public static boolean intermediary(String name) {
        return !namedType(name).equals(name);
    }

    public static String descriptor(String value, boolean toRuntime) {
        var result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != 'L') {
                result.append(c);
                continue;
            }
            int end = value.indexOf(';', i);
            if (end < 0) throw new IllegalArgumentException("Invalid descriptor: " + value);
            var type = value.substring(i + 1, end);
            result.append('L').append(toRuntime ? runtimeType(type) : namedType(type)).append(';');
            i = end;
        }
        return result.toString();
    }

    public static String member(
            String owner, String name, String desc, boolean field, boolean toRuntime) {
        String namedOwner = namedType(owner), namedDesc = descriptor(desc, false);
        var table =
                field
                        ? (toRuntime ? FIELDS : REVERSE_FIELDS)
                        : (toRuntime ? METHODS : REVERSE_METHODS);
        return find(table, namedOwner, name, namedDesc, new HashSet<>());
    }

    private static String find(
            Map<String, String> table, String owner, String name, String desc, Set<String> seen) {
        if (!seen.add(owner)) return name;
        var mapped = table.get(key(owner, name, desc));
        if (mapped != null) return mapped;
        for (var parent : PARENTS.getOrDefault(owner, List.of())) {
            var inherited = find(table, parent, name, desc, seen);
            if (!inherited.equals(name)) return inherited;
        }
        return name;
    }

    public static String field(Class<?> owner, String name, Class<?> type) {
        return intermediary(owner.getName().replace('.', '/'))
                ? member(
                        owner.getName().replace('.', '/'),
                        name,
                        type.descriptorString(),
                        true,
                        true)
                : name;
    }

    public static String method(Class<?> owner, String name, MethodType type) {
        return intermediary(owner.getName().replace('.', '/'))
                ? member(
                        owner.getName().replace('.', '/'),
                        name,
                        type.toMethodDescriptorString(),
                        false,
                        true)
                : name;
    }

    public static String reflectedField(Class<?> owner, String name) {
        if (!intermediary(owner.getName().replace('.', '/'))) return name;
        for (var field : owner.getDeclaredFields())
            if (field.getName().equals(field(owner, name, field.getType()))) return field.getName();
        throw new IllegalArgumentException("Unknown field " + owner.getName() + "." + name);
    }

    public static String reflectedMethod(Class<?> owner, String name, Class<?>... parameters) {
        if (!intermediary(owner.getName().replace('.', '/'))) return name;
        for (var method : owner.getDeclaredMethods())
            if (Arrays.equals(parameters, method.getParameterTypes())
                    && method.getName()
                            .equals(
                                    method(
                                            owner,
                                            name,
                                            MethodType.methodType(
                                                    method.getReturnType(), parameters))))
                return method.getName();
        throw new IllegalArgumentException("Unknown method " + owner.getName() + "." + name);
    }
}
