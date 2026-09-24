package com.blanoir.moons.client.ui.hud;

import com.blanoir.moons.client.module.framework.ModuleRegistry;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class HudTagVerification {
    public static void main(String[] args) {
        var count = new AtomicInteger();
        var module =
                new ModuleRegistry.Module(
                                "example",
                                "Example",
                                "World",
                                () -> true,
                                (c, enabled) -> 1,
                                () -> count + " found | scanning 57/1089",
                                List.of(),
                                false)
                        .withHudTag(() -> count + " found", "888 found")
                        .withDefaultEnabled(true);
        double reserved =
                HudTagWidth.reserve(
                        module.hudTag().widthSamples(), HudTagVerification::proportionalWidth);
        for (int value : new int[] {0, 1, 9, 10, 99, 100, 111, 512}) {
            count.set(value);
            require(
                    module.hudTag().text().get().equals(value + " found"),
                    "Compact HUD value updates");
            require(module.tag().get().contains("scanning"), "Detailed status remains available");
            double rowWidth = Math.max(proportionalWidth(module.hudTag().text().get()), reserved);
            require(
                    rowWidth == reserved,
                    "Count digit transitions preserve the sort width: " + value);
        }
        require(module.defaultEnabled(), "Copy keeps the default flag");
        var states = List.of("Waiting", "Held");
        double stateWidth = HudTagWidth.reserve(states, HudTagVerification::proportionalWidth);
        for (String state : states)
            require(proportionalWidth(state) <= stateWidth, "Status transitions fit fixed width");
        require(
                module.withHudTag("").hudTag().text().get().isEmpty(),
                "Verbose tags can be hidden");
        var normal =
                new ModuleRegistry.Module(
                        "normal",
                        "Normal",
                        "World",
                        () -> true,
                        (c, enabled) -> 1,
                        () -> "Legit",
                        List.of(),
                        false);
        require(
                normal.hudTag().text().get().equals("Legit"),
                "Uncustomized mode tags are retained");
        System.out.println("HUD compact status and fixed proportional-font width checks passed.");
    }

    private static double proportionalWidth(String text) {
        // Deliberately make '1' wider than '8' to catch a hard-coded widest digit.
        return text.chars().mapToDouble(c -> c == '1' ? 9 : c == '8' ? 5 : c == ' ' ? 3 : 6).sum();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
