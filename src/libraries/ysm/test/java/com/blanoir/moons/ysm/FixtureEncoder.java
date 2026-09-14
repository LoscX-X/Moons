package com.blanoir.moons.ysm;

import com.blanoir.moons.ysm.internal.core.algorithms.*;
import com.blanoir.moons.ysm.internal.resource.pojo.RawYsmModel;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Test-only upstream format encoder. Never included in the production module. */
final class FixtureEncoder {
    private static final long SEED_KEY_DERIVATION = 0xD017CBBA7B5D3581L;
    private static final long SEED_FILE_VERIFICATION = 0x9E5599DB80C67C29L;
    private static final long SEED_RES_VERIFICATION = 0xA62B1A2C43842BC3L;
    private static final Random theRandom = new Random(1);

    static byte[] encode(RawYsmModel model) throws Exception {
        try (var data = YSMBinarySerializer.serialize(model, 32, false)) {
            byte[] content = data.toArray();
            ByteBuffer raw =
                    ByteBuffer.allocate(content.length + 4 + 128).order(ByteOrder.LITTLE_ENDIAN);
            raw.putInt(32).put(content);
            // File footer (distinct from the server-cache footer).
            raw.put((byte) 32)
                    .put((byte) 1)
                    .put((byte) 0)
                    .put((byte) 0)
                    .put((byte) 0)
                    .put((byte) 0);
            return encryptYsmFile(Arrays.copyOf(raw.array(), raw.position()));
        }
    }

    public static byte[] encryptYsmFile(byte[] rawClearText) throws Exception {
        byte[] key = new byte[32];
        byte[] iv = new byte[24];
        theRandom.nextBytes(key);
        theRandom.nextBytes(iv);

        byte[] keyIv = new byte[56];
        System.arraycopy(key, 0, keyIv, 0, 32);
        System.arraycopy(iv, 0, keyIv, 32, 24);

        byte[] zstdData = YsmZstd.compress(rawClearText);
        int paddingLength = 16 + theRandom.nextInt(112);
        int randomTop6Bits = theRandom.nextInt(64) << 10;
        int headerWord = (paddingLength & 0x3FF) | randomTop6Bits;
        byte[] payloadToEncrypt = new byte[2 + paddingLength + zstdData.length];
        payloadToEncrypt[0] = (byte) (headerWord & 0xFF);
        payloadToEncrypt[1] = (byte) ((headerWord >> 8) & 0xFF);
        byte[] padding = new byte[paddingLength];
        theRandom.nextBytes(padding);
        System.arraycopy(padding, 0, payloadToEncrypt, 2, paddingLength);
        System.arraycopy(zstdData, 0, payloadToEncrypt, 2 + paddingLength, zstdData.length);
        byte[] xoredData = mt19937Xor(payloadToEncrypt, keyIv, SEED_KEY_DERIVATION);
        byte[] encryptedBinaryData =
                modifiedChaChaEncrypt(xoredData, key, iv, SEED_RES_VERIFICATION);
        byte[] prefix = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 0x59, 0x53, 0x47, 0x50};
        byte[] data = ("").getBytes(StandardCharsets.UTF_8);

        byte[] headerBytes = new byte[prefix.length + data.length];
        System.arraycopy(prefix, 0, headerBytes, 0, prefix.length);
        System.arraycopy(data, 0, headerBytes, prefix.length, data.length);

        int totalSizeWithoutHash = headerBytes.length + 1 + 4 + encryptedBinaryData.length + 56;
        ByteBuffer fileBuf =
                ByteBuffer.allocate(totalSizeWithoutHash + 8).order(ByteOrder.LITTLE_ENDIAN);

        fileBuf.put(headerBytes);
        fileBuf.put((byte) 0x00); // terminator

        fileBuf.putInt(3);
        fileBuf.put(encryptedBinaryData);
        fileBuf.put(key);
        fileBuf.put(iv);

        CityHash ch = new CityHash();
        long fileHash =
                ch.hash64WithSeed(fileBuf.array(), 0, totalSizeWithoutHash, SEED_FILE_VERIFICATION);
        fileBuf.putLong(fileHash);

        return fileBuf.array();
    }

    private static byte[] modifiedChaChaEncrypt(byte[] plainText, byte[] key, byte[] iv, long seed)
            throws Exception {
        byte[] keyIv = new byte[56];
        System.arraycopy(key, 0, keyIv, 0, 32);
        System.arraycopy(iv, 0, keyIv, 32, 24);

        CityHash ch = new CityHash();
        long hash2 = ch.hash64WithSeed(keyIv, seed);

        int nextRoundSize = (int) (((hash2 & 0x3FL) | 0x40L) << 6);
        int rounds = (int) (10 * Long.remainderUnsigned(hash2, 3) + 10);

        XChaCha20 ctx = new XChaCha20(key, iv, rounds);
        byte[] result = new byte[plainText.length];
        int blockPointer = 0;

        while (blockPointer < plainText.length) {
            if (blockPointer + nextRoundSize > plainText.length) {
                nextRoundSize = plainText.length - blockPointer;
            }

            ctx.processBytes(plainText, blockPointer, result, blockPointer, nextRoundSize);
            blockPointer += nextRoundSize;

            if (blockPointer < plainText.length) {
                long resHash =
                        ch.hash64WithSeed(
                                plainText, blockPointer - nextRoundSize, nextRoundSize, seed);
                nextRoundSize = ctx.updateStateYSM(resHash);
            }
        }
        return result;
    }

    private static byte[] mt19937Xor(byte[] data, byte[] currentKeyIv, long seedDerivation) {
        return mt19937Xor(data, 0, data.length, currentKeyIv, seedDerivation);
    }

    private static byte[] mt19937Xor(
            byte[] data, int offset, int length, byte[] currentKeyIv, long seedDerivation) {
        long mtSeed = new CityHash().hash64WithSeed(currentKeyIv, seedDerivation);
        MT19937 mt = new MT19937(mtSeed);
        byte[] result = new byte[length];

        int i = 0;
        while (i < length) {
            long rnd = mt.extract_number();
            for (int j = 0; j < 8 && i < length; ++j) {
                byte keystreamByte = (byte) ((rnd >>> (j * 8)) & 0xFF);
                result[i] = (byte) (data[offset + i] ^ keystreamByte);
                i++;
            }
        }
        return result;
    }

    private static void mt19937XorInPlace(byte[] data, byte[] currentKeyIv, long seedDerivation) {
        mt19937XorInPlace(data, 0, data.length, currentKeyIv, seedDerivation);
    }

    private static void mt19937XorInPlace(
            byte[] data, int offset, int length, byte[] currentKeyIv, long seedDerivation) {
        long mtSeed = new CityHash().hash64WithSeed(currentKeyIv, seedDerivation);
        MT19937 mt = new MT19937(mtSeed);

        int i = 0;
        while (i < length) {
            long rnd = mt.extract_number();
            for (int j = 0; j < 8 && i < length; ++j) {
                byte keystreamByte = (byte) ((rnd >>> (j * 8)) & 0xFF);
                data[offset + i] = (byte) (data[offset + i] ^ keystreamByte);
                i++;
            }
        }
    }
}
