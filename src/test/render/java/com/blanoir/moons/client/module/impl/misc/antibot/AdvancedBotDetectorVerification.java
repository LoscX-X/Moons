package com.blanoir.moons.client.module.impl.misc.antibot;

public final class AdvancedBotDetectorVerification {
    public static void main(String[] args) throws Exception {
        allow(facts(0, 0, true, false), "Missing player info alone");
        allow(facts(0, 50, true, false), "Missing player info while climbing stairs");
        allow(facts(0, 50, false, true), "Listed players sharing a name while climbing stairs");
        allow(facts(120, 0, false, false), "Finite illegal rotation alone");
        for (float pitch : new float[] {-450, -270, -90, 90, 270, 450, 91, -91}) {
            allow(facts(pitch, 50, true, false), "Wrapped or quantized pitch " + pitch);
        }

        var detector = new AdvancedBotDetector();
        var clone = new AdvancedBotDetector.Facts(1, 0, 0, true, true);
        observe(detector, clone, 2);
        require(!detector.isBot(), "Missing conflicting profile needs sustained confirmation");
        detector.observe(clone);
        require(detector.isBot(), "Persistent missing profile with a listed namesake");
        detector.observe(facts(0, 50, false, true));
        require(!detector.isBot(), "Player info recovery immediately releases a listed player");
        observe(detector, clone, 2);
        require(!detector.isBot(), "A new identity conflict needs fresh confirmation");
        detector.observe(clone);
        require(detector.isBot(), "Confirmed evidence can be detected again");
        detector.observe(facts(0, 0, true, false));
        require(!detector.isBot(), "Removing the identity conflict clears the verdict");

        detector = new AdvancedBotDetector();
        observe(detector, facts(0, 0, false, false), 30);
        observe(detector, facts(Float.NaN, 0, false, false), 2);
        require(!detector.isBot(), "Transient malformed rotation is not enough");
        detector.observe(facts(0, 0, false, false));
        observe(detector, facts(Float.NaN, 0, false, false), 2);
        require(!detector.isBot(), "Separate anomalies do not accumulate");
        detector.observe(facts(Float.NaN, 0, false, false));
        require(detector.isBot(), "Sustained malformed rotation is filtered");
        detector.observe(facts(0, 0, false, false));
        require(!detector.isBot(), "Normal rotation releases the target");

        reject(facts(120, 0, true, false), "Missing profile and illegal pitch");
        reject(facts(120, 10, false, false), "Illegal pitch and repeated invalid ground moves");
        reject(facts(Float.POSITIVE_INFINITY, 0, false, false), "Non-finite rotation");
        allow(
                new AdvancedBotDetector.Facts(0, 120, 50, true, false),
                "Fresh entity grace for ambiguous evidence");
        require(!new AdvancedBotDetector().isBot(), "A new player has no inherited verdict");
        HideBotRenderVerification.verify();
        System.out.println("MOONS_ANTIBOT_VERIFIED");
    }

    private static AdvancedBotDetector.Facts facts(
            float pitch, int invalidGround, boolean missingProfile, boolean duplicateName) {
        return new AdvancedBotDetector.Facts(
                100, pitch, invalidGround, missingProfile, duplicateName);
    }

    private static void allow(AdvancedBotDetector.Facts facts, String message) {
        var detector = new AdvancedBotDetector();
        for (int tick = 0; tick < 200; tick++) {
            detector.observe(facts);
            require(!detector.isBot(), message);
        }
    }

    private static void reject(AdvancedBotDetector.Facts facts, String message) {
        var detector = new AdvancedBotDetector();
        observe(detector, facts, 100);
        require(detector.isBot(), message);
    }

    private static void observe(
            AdvancedBotDetector detector, AdvancedBotDetector.Facts facts, int ticks) {
        for (int tick = 0; tick < ticks; tick++) detector.observe(facts);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
