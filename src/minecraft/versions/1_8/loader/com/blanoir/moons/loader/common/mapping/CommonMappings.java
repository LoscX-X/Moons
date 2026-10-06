package com.blanoir.moons.loader.common.mapping;

import com.blanoir.moons.loader.common.asm.hooks.Minecraft189Hooks;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Exact vanilla 1.8.9 and MCP-named targets; generated from verified inputs. */
public final class CommonMappings {
    private CommonMappings() {}

    public static MappingService create() {
        List<TargetMethod> targets = new ArrayList<>();
        try (InputStream input = CommonMappings.class.getResourceAsStream("/moons/1_8/hooks.tsv")) {
            if (input == null) throw new IllegalStateException("Missing 1.8.9 hook table");
            for (String line :
                    new String(input.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                String[] p = line.split("\t");
                if (p.length != 8) throw new IllegalStateException("Invalid 1.8.9 hook row");
                for (int start : new int[] {1, 4})
                    targets.add(
                            new TargetMethod(
                                    p[0],
                                    List.of(p[start]),
                                    List.of(p[start + 1]),
                                    p[start + 2],
                                    TargetMethod.HookKind.VERSION_SPECIFIC,
                                    (method, id) -> Minecraft189Hooks.install(method, id, p[7])));
            }
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        Map<String, List<TargetMethod>> hooks = new LinkedHashMap<>();
        for (TargetMethod target : targets)
            hooks.computeIfAbsent(target.id(), ignored -> new ArrayList<>()).add(target);
        return MappingService.fromPoints(hooks.values().stream().map(InjectionPoint::new).toList());
    }
}
