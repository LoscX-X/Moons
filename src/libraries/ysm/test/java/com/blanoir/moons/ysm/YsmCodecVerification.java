package com.blanoir.moons.ysm;

import com.blanoir.moons.ysm.codecs.OpusPcmDecoder;
import com.blanoir.moons.ysm.codecs.internal.concentus.*;
import com.blanoir.moons.ysm.codecs.internal.gagravarr.opus.*;

import java.io.*;
import java.util.Arrays;

/** Uses synthesized audio; no model author's sound assets are included. */
final class YsmCodecVerification {
    static void verify() throws Exception {
        byte[] normal = OpusPcmDecoder.decode(encode(0, 0));
        byte[] skipped = OpusPcmDecoder.decode(encode(120, 0));
        if (normal.length != 960 * 12 * 2 || skipped.length != normal.length - 240)
            throw new AssertionError("Opus frame count/pre-skip");
        if (!Arrays.equals(Arrays.copyOfRange(normal, 240, normal.length), skipped))
            throw new AssertionError("Opus pre-skip must remove leading frames");
        byte[] cropped = encode(120, 0);
        // Set the final page's end position independently of the codec implementation.
        for (int offset = 0; offset < cropped.length; ) {
            int segments = cropped[offset + 26] & 255;
            int size = 27 + segments;
            for (int i = 0; i < segments; i++) size += cropped[offset + 27 + i] & 255;
            if ((cropped[offset + 5] & 4) != 0) {
                long end = 960 * 12 - 200;
                for (int i = 0; i < 8; i++) cropped[offset + 6 + i] = (byte) (end >>> (i * 8));
                Arrays.fill(cropped, offset + 22, offset + 26, (byte) 0);
                int crc =
                        com.blanoir.moons.ysm.codecs.internal.gagravarr.ogg.CRCUtils.getCRC(
                                Arrays.copyOfRange(cropped, offset, offset + size));
                for (int i = 0; i < 4; i++) cropped[offset + 22 + i] = (byte) (crc >>> (i * 8));
            }
            offset += size;
        }
        if (!Arrays.equals(
                OpusPcmDecoder.decode(cropped), Arrays.copyOf(skipped, skipped.length - 400)))
            throw new AssertionError("Opus end granule must trim trailing padding after pre-skip");
        long energy = 0;
        for (int i = 0; i < normal.length; i += 2) {
            short value = (short) ((normal[i] & 255) | (normal[i + 1] << 8));
            energy += (long) value * value;
        }
        if (energy < 100_000_000L)
            throw new AssertionError("Synthetic Opus audio decoded as silence");
        byte[] quieter = OpusPcmDecoder.decode(encode(0, -6 * 256));
        long quietEnergy = 0;
        for (int i = 0; i < quieter.length; i += 2) {
            short value = (short) ((quieter[i] & 255) | (quieter[i + 1] << 8));
            quietEnergy += (long) value * value;
        }
        double ratio = (double) quietEnergy / energy;
        if (ratio < .24 || ratio > .26) throw new AssertionError("Opus output gain: " + ratio);
        try {
            OpusPcmDecoder.decode(new byte[] {1, 2, 3});
            throw new AssertionError("Invalid Opus accepted");
        } catch (IOException expected) {
        }
        System.out.println("YSM_CODECS_VERIFIED opus=synthetic-tone+pre-skip+gain+end-trim");
    }

    private static byte[] encode(int skip, int gain) throws Exception {
        OpusEncoder encoder = new OpusEncoder(48000, 1, OpusApplication.OPUS_APPLICATION_AUDIO);
        OpusInfo info = new OpusInfo();
        info.setNumChannels(1);
        info.setSampleRate(48000);
        info.setPreSkip(skip);
        info.setOutputGain(gain);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (OpusFile output = new OpusFile(encoded, 123, info, new OpusTags())) {
            output.setMaxPacketsPerPage(1);
            short[] input = new short[960];
            byte[] packet = new byte[4096];
            for (int frame = 0; frame < 12; frame++) {
                for (int i = 0; i < input.length; i++)
                    input[i] =
                            (short)
                                    (12000
                                            * Math.sin(
                                                    (frame * 960 + i) * 2 * Math.PI * 440 / 48000));
                int size = encoder.encode(input, 0, 960, packet, 0, packet.length);
                output.writeAudioData(new OpusAudioData(Arrays.copyOf(packet, size)));
            }
        }
        return encoded.toByteArray();
    }
}
