package com.blanoir.moons.client.module.impl.combat.silentaura;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/** Immutable inference for the fixed PyTorch GRU(7,128), with no native runtime. */
public final class LearnedAimModel {
    public static final int WINDOW = 16;
    public static final int INPUTS = 7;
    private static final int HIDDEN = 128;
    private static final int GATES = 3 * HIDDEN;
    private static final int BYTES = 211588;
    private static final String RESOURCE = "/assets/moons/models/aim-gru128-epoch7.bin";

    private final float[] mean;
    private final float[] std;
    private final float[] scale;
    private final float[] inputWeights;
    private final float[] hiddenWeights;
    private final float[] inputBias;
    private final float[] hiddenBias;
    private final float[] head;
    private final float[] headBias;
    private final String checkpointHash;

    private LearnedAimModel(DataInputStream input) throws IOException {
        if (input.readInt() != 0x41475255
                || input.readInt() != 1
                || input.readInt() != INPUTS
                || input.readInt() != HIDDEN
                || input.readInt() != WINDOW
                || input.readInt() != 2
                || input.readInt() != 7) {
            throw new IOException("Unsupported learned aim model");
        }
        checkpointHash = HexFormat.of().formatHex(input.readNBytes(32));
        mean = read(input, INPUTS);
        std = read(input, INPUTS);
        scale = read(input, 2);
        for (float value : std)
            if (value <= 0) throw new IOException("Invalid model standard deviation");
        for (float value : scale)
            if (value <= 0) throw new IOException("Invalid model output scale");
        inputWeights = read(input, GATES * INPUTS);
        hiddenWeights = read(input, GATES * HIDDEN);
        inputBias = read(input, GATES);
        hiddenBias = read(input, GATES);
        head = read(input, 2 * HIDDEN);
        headBias = read(input, 2);
    }

    public static LearnedAimModel read(InputStream stream) throws IOException {
        if (stream == null) throw new IOException("Missing learned aim resource");
        byte[] bytes = stream.readNBytes(BYTES + 1);
        if (bytes.length != BYTES || stream.read() != -1)
            throw new IOException("Invalid model length");
        try {
            String hash =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!hash.equals("08c61de4af703b580fb20417bc0f17c9796998bcb9f597776d4dd8a87d65ebec")) {
                throw new IOException("Learned aim resource checksum mismatch");
            }
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
        return new LearnedAimModel(new DataInputStream(new ByteArrayInputStream(bytes)));
    }

    private static float[] read(DataInputStream input, int count) throws IOException {
        float[] result = new float[count];
        for (int i = 0; i < count; i++) {
            result[i] = input.readFloat();
            if (!Float.isFinite(result[i])) throw new IOException("Nonfinite model coefficient");
        }
        return result;
    }

    /** A new zero hidden state for every window, exactly as used during training. */
    public float[] predict(float[][] raw) {
        return predict(raw, new Workspace());
    }

    /** Caller-owned scratch; do not share one workspace between concurrent predictions. */
    public static final class Workspace {
        private final float[] hidden = new float[HIDDEN];
        private final float[] next = new float[HIDDEN];
        private final float[] input = new float[INPUTS];
        private final float[] gi = new float[GATES];
        private final float[] gh = new float[GATES];
    }

    public float[] predict(float[][] raw, Workspace workspace) {
        if (raw == null || raw.length != WINDOW)
            throw new IllegalArgumentException("Expected 16 rows");
        float[] hidden = workspace.hidden;
        float[] next = workspace.next;
        float[] input = workspace.input;
        float[] gi = workspace.gi;
        float[] gh = workspace.gh;
        // Reset even after a failed prediction. Every window starts from zero.
        Arrays.fill(hidden, 0.0F);
        for (float[] row : raw) {
            if (row == null || row.length != INPUTS)
                throw new IllegalArgumentException("Expected 7 features");
            for (int i = 0; i < INPUTS; i++) {
                if (!Float.isFinite(row[i]))
                    throw new IllegalArgumentException("Nonfinite feature");
                input[i] = (row[i] - mean[i]) / std[i];
            }
            for (int gate = 0; gate < GATES; gate++) {
                float in = inputBias[gate];
                float recurrent = hiddenBias[gate];
                int inputOffset = gate * INPUTS;
                int hiddenOffset = gate * HIDDEN;
                for (int i = 0; i < INPUTS; i++) in += inputWeights[inputOffset + i] * input[i];
                for (int i = 0; i < HIDDEN; i++)
                    recurrent += hiddenWeights[hiddenOffset + i] * hidden[i];
                gi[gate] = in;
                gh[gate] = recurrent;
            }
            for (int i = 0; i < HIDDEN; i++) {
                float reset = sigmoid(gi[i] + gh[i]);
                float update = sigmoid(gi[HIDDEN + i] + gh[HIDDEN + i]);
                float candidate =
                        (float) Math.tanh(gi[2 * HIDDEN + i] + reset * gh[2 * HIDDEN + i]);
                next[i] = (1.0F - update) * candidate + update * hidden[i];
            }
            float[] swap = hidden;
            hidden = next;
            next = swap;
        }
        float[] output = new float[2];
        for (int axis = 0; axis < 2; axis++) {
            float value = headBias[axis];
            for (int i = 0; i < HIDDEN; i++) value += head[axis * HIDDEN + i] * hidden[i];
            output[axis] = value * scale[axis];
            if (!Float.isFinite(output[axis]))
                throw new IllegalStateException("Nonfinite model prediction");
        }
        return output;
    }

    private static float sigmoid(float value) {
        return (float) (1.0D / (1.0D + Math.exp(-value)));
    }

    public String checkpointHash() {
        return checkpointHash;
    }

    public static LearnedAimModel bundled() {
        return Bundled.MODEL;
    }

    private static final class Bundled {
        private static final LearnedAimModel MODEL = load();

        private static LearnedAimModel load() {
            try (InputStream input = LearnedAimModel.class.getResourceAsStream(RESOURCE)) {
                return read(input);
            } catch (IOException | RuntimeException failure) {
                System.getLogger(LearnedAimModel.class.getName())
                        .log(
                                System.Logger.Level.WARNING,
                                "Learned aim unavailable; retaining primary aim mode",
                                failure);
                return null;
            }
        }
    }
}
