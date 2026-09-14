package com.blanoir.moons.ysm.internal.core.security;

import com.blanoir.moons.ysm.internal.core.algorithms.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Local .ysm decoding only. Network and server-cache protocols have been removed. */
public final class YsmCrypt {
    private static final long SEED_KEY_DERIVATION = 0xD017CBBA7B5D3581L;
    private static final long SEED_FILE_VERIFICATION = 0x9E5599DB80C67C29L;
    private static final long SEED_RES_VERIFICATION = 0xA62B1A2C43842BC3L;

    public static byte[] decryptYsmFile(byte[] fileData) throws Exception {
        if (fileData.length < 8 + 24 + 32 + 8) {
            throw new RuntimeException("Invalid YSM file: File too short.");
        }

        int headerLength = 0;
        while (headerLength < fileData.length && fileData[headerLength] != 0x00) {
            headerLength++;
        }

        int tailOffset = fileData.length - 64;
        byte[] key = Arrays.copyOfRange(fileData, tailOffset, tailOffset + 32);
        byte[] iv = Arrays.copyOfRange(fileData, tailOffset + 32, tailOffset + 56);
        long fileHash =
                ByteBuffer.wrap(fileData, tailOffset + 56, 8)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .getLong();

        CityHash ch = new CityHash();
        long calculatedHash =
                ch.hash64WithSeed(fileData, 0, fileData.length - 8, SEED_FILE_VERIFICATION);
        if (calculatedHash != fileHash) {
            throw new RuntimeException("Corrupted YSM file: File hash mismatch.");
        }

        int ptrBinaryData = headerLength + 1;
        int crypto =
                ByteBuffer.wrap(fileData, ptrBinaryData, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (crypto != 3) {
            throw new RuntimeException("Invalid YSM file: Crypto version is not 3.");
        }
        ptrBinaryData += 4;

        byte[] chachaDecrypted =
                modifiedChaChaDecrypt(
                        fileData,
                        ptrBinaryData,
                        tailOffset - ptrBinaryData,
                        key,
                        iv,
                        SEED_RES_VERIFICATION);

        byte[] keyIv = new byte[56];
        System.arraycopy(key, 0, keyIv, 0, 32);
        System.arraycopy(iv, 0, keyIv, 32, 24);
        mt19937XorInPlace(chachaDecrypted, keyIv, SEED_KEY_DERIVATION);
        byte[] xorredData = chachaDecrypted;

        // uint16_t n = xorred_data[0] | (xorred_data[1] << 8); n &= 0x3ff;
        int n = ((xorredData[0] & 0xFF) | ((xorredData[1] & 0xFF) << 8)) & 0x3FF;

        int zstdOffset = 2 + n;
        return YsmZstd.decompress(xorredData, zstdOffset, xorredData.length - zstdOffset);
    }

    private static byte[] modifiedChaChaDecrypt(byte[] data, byte[] key, byte[] iv, long seed)
            throws Exception {
        return modifiedChaChaDecrypt(data, 0, data.length, key, iv, seed);
    }

    private static byte[] modifiedChaChaDecrypt(
            byte[] data, int dataOff, int dataLen, byte[] key, byte[] iv, long seed)
            throws Exception {
        byte[] keyIv = new byte[56];
        System.arraycopy(key, 0, keyIv, 0, 32);
        System.arraycopy(iv, 0, keyIv, 32, 24);

        CityHash ch = new CityHash();
        long hash2 = ch.hash64WithSeed(keyIv, seed);

        // ((hash2 & 0x3f) | 0x40) << 6
        int nextRoundSize = (int) (((hash2 & 0x3FL) | 0x40L) << 6);
        int rounds = (int) (10 * Long.remainderUnsigned(hash2, 3) + 10);

        XChaCha20 ctx = new XChaCha20(key, iv, rounds);

        byte[] result = new byte[dataLen];
        int blockPointer = 0;

        while (blockPointer < dataLen) {
            if (blockPointer + nextRoundSize > dataLen) {
                nextRoundSize = dataLen - blockPointer;
            }
            ctx.processBytes(data, dataOff + blockPointer, result, blockPointer, nextRoundSize);
            blockPointer += nextRoundSize;

            if (blockPointer < dataLen) {
                long resHash =
                        ch.hash64WithSeed(
                                result, blockPointer - nextRoundSize, nextRoundSize, seed);
                nextRoundSize = ctx.updateStateYSM(resHash);
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
