package com.blanoir.moons.client.utils;

import com.blanoir.moons.client.utils.io.EmbeddedResources;
import com.blanoir.moons.client.utils.json.JsonFields;
import com.blanoir.moons.client.utils.render.ColorCodec;
import com.blanoir.moons.client.utils.text.NumberText;
import com.blanoir.moons.client.utils.time.FrameClock;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Boundary checks for shared presentation values without a game or renderer. */
public final class PresentationUtilsVerification {
    private PresentationUtilsVerification() {}

    public static void main(String[] args) throws Exception {
        require(ColorCodec.parseRgbOrArgb("001122") == 0xFF001122, "RGB becomes opaque");
        require(ColorCodec.parseRgbOrArgb("80112233") == 0x80112233, "ARGB retains its alpha");
        expectFailure(NumberFormatException.class, () -> ColorCodec.parseRgbOrArgb("0x112233"));

        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            require(NumberText.trimmedDecimal(1.234567, 2).equals("1.23"), "registry precision");
            require(NumberText.trimmedDecimal(1.234567, 6).equals("1.234567"), "input precision");
            require(NumberText.compactDouble(-0.0).equals("0"), "integral negative zero");
            require(NumberText.compactDouble(Double.NaN).equals("NaN"), "nonfinite labels");
        } finally {
            Locale.setDefault(previous);
        }

        JsonObject fields = new JsonObject();
        fields.add("null", JsonNull.INSTANCE);
        fields.add("malformed", new JsonObject());
        require(JsonFields.nullableString(fields, "null") == null, "nullable JSON field");
        require(
                JsonFields.stringOr(fields, "malformed", "fallback").equals("fallback"),
                "tolerant JSON field");
        expectFailure(
                UnsupportedOperationException.class,
                () -> JsonFields.nullableString(fields, "malformed"));
        expectFailure(IllegalArgumentException.class, () -> JsonFields.required(fields, "null"));

        boolean[] closed = {false};
        ClassLoader resources =
                new ClassLoader(null) {
                    @Override
                    public InputStream getResourceAsStream(String name) {
                        if (!name.equals("sample")) return null;
                        return new ByteArrayInputStream(
                                "SGVs\r\nbG8=\n".getBytes(StandardCharsets.UTF_8)) {
                            @Override
                            public void close() {
                                closed[0] = true;
                            }
                        };
                    }
                };
        byte[] decoded = EmbeddedResources.readMimeBase64(resources, "sample", "missing sample");
        require(
                new String(decoded, StandardCharsets.UTF_8).equals("Hello") && closed[0],
                "MIME resource decoding closes its stream");
        expectFailure(
                IllegalArgumentException.class,
                () -> EmbeddedResources.readRequiredBytes(resources, "absent", "missing sample"));

        FrameClock clock = new FrameClock(0.0);
        require(clock.nextDeltaSeconds(1_000_000_000L) == 0.0, "HUD first-frame delta");
        require(clock.nextDeltaSeconds(1_050_000_000L) == 0.05, "shared frame timestamp");
        clock.reset();
        require(clock.nextDeltaSeconds(2_000_000_000L) == 0.0, "HUD reset delta");
        System.out.println("Presentation utility verification passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expectFailure(Class<? extends Throwable> expected, CheckedAction action)
            throws Exception {
        try {
            action.run();
        } catch (Throwable failure) {
            if (expected.isInstance(failure)) return;
            throw new AssertionError("Expected " + expected.getSimpleName(), failure);
        }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }
}
