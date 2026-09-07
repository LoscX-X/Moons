package com.blanoir.moons.client.module.impl.misc.antibot;

import com.blanoir.moons.client.config.Settings;

import java.nio.file.Files;
import java.util.List;

/** Regression scenarios for the unified evidence model and mode compatibility. */
public final class AdvancedBotVerification {
    public static void main(String[] args) throws Exception {
        Settings.configure(Files.createTempDirectory("moons-antibot-verify-"));
        if (args.length > 0) Settings.setString("antibot.mode", args[0]);
        check(AntiBot.hudTag().equals("advanced"), "default and retired modes resolve to Advanced");
        check(AntiBot.modeOptions().equals(List.of("advanced")), "only Advanced appears in the UI");
        for (String legacy : List.of("custom", "matrix", "intave_heavy", "horizon")) {
            AntiBot.setMode(null, legacy);
            check(AntiBot.hudTag().equals("advanced"), "legacy mode resolves: " + legacy);
            check(
                    Settings.getString("antibot.mode", "").equals("advanced"),
                    "canonical mode is saved");
        }

        var normal = facts(0, 0, false, false, false, 40);
        var skinlessLowPing = facts(0, 0, false, false, true, 0);
        var duplicate = facts(0, 0, false, true, false, 40);
        var groundOnly = facts(0, 30, false, false, false, 40);
        var missing = facts(0, 0, true, false, false, -1);
        var stairsWithLowPing = facts(0, 30, false, false, true, 0);
        var corroborated = facts(0, 30, false, true, false, 40);
        for (var benign :
                List.of(
                        normal,
                        skinlessLowPing,
                        duplicate,
                        groundOnly,
                        stairsWithLowPing,
                        new AdvancedBotDetector.Facts(100, -42, 0, 0, false, false, false, 40),
                        new AdvancedBotDetector.Facts(
                                100, Integer.MAX_VALUE, 0, 0, false, false, false, 40))) {
            AdvancedBotDetector detector = new AdvancedBotDetector();
            observe(detector, benign, 100);
            check(!detector.isBot(), "isolated weak evidence does not filter players");
        }

        AdvancedBotDetector spawn = new AdvancedBotDetector();
        observe(spawn, missing, 19);
        check(!spawn.isBot(), "incomplete spawn information gets a grace period");
        observe(spawn, normal, 50);
        check(!spawn.isBot(), "profile arriving during grace avoids false positives");

        AdvancedBotDetector detector = new AdvancedBotDetector();
        observe(detector, normal, 25);
        observe(detector, missing, 25);
        check(!detector.isBot(), "short profile interruption is tolerated");
        observe(detector, normal, 1);
        observe(detector, missing, 39);
        check(!detector.isBot(), "confirmation must be consecutive");
        observe(detector, missing, 2);
        check(!detector.isBot(), "missing profile also needs confirmation after its grace period");
        observe(detector, missing, 1);
        check(detector.isBot(), "persistent strong evidence filters the target");
        observe(detector, normal, 1);
        check(!detector.isBot(), "corrected information releases a target");
        observe(detector, missing, 3);
        check(!detector.isBot(), "a recovered profile starts a fresh missing-profile grace period");
        observe(detector, normal, 1);
        observe(detector, corroborated, 3);
        check(detector.isBot(), "independent weak evidence can confirm a bot");
        observe(detector, normal, 1);
        observe(detector, facts(Float.NaN, 0, false, false, false, 40), 3);
        check(detector.isBot(), "nonfinite rotation is rejected");
        AdvancedBotDetector replacement = new AdvancedBotDetector();
        observe(replacement, missing, 1);
        check(!replacement.isBot(), "fresh observations cannot inherit a prior verdict");
        observe(
                replacement,
                new AdvancedBotDetector.Facts(2, 42, 180, 30, true, true, true, 0),
                50);
        check(!replacement.isBot(), "entity age also gates confirmation");
        System.out.println("ADVANCED_ANTIBOT_VERIFIED");
    }

    private static AdvancedBotDetector.Facts facts(
            float pitch,
            int ground,
            boolean missing,
            boolean duplicate,
            boolean emptyProperties,
            int latency) {
        return new AdvancedBotDetector.Facts(
                100, 42, pitch, ground, missing, duplicate, emptyProperties, latency);
    }

    private static void observe(
            AdvancedBotDetector detector, AdvancedBotDetector.Facts facts, int ticks) {
        for (int tick = 0; tick < ticks; tick++) detector.observe(facts);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
