package com.carlos.vault.wjdr.protocol;

import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

public final class Rc4StreamCodec {
    private static final int STATE_SIZE = 256;
    private final int[] state = new int[256];
    private final ReentrantLock stateLock = new ReentrantLock();

    public Rc4StreamCodec(byte[] key) {
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException("RC4 key must not be empty");
        }
        this.initializeState(key);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public byte[] apply(byte[] input, int cryptLength) {
        this.stateLock.lock();
        try {
            byte[] output = Arrays.copyOf(input, input.length);
            int limit = Math.max(0, Math.min(cryptLength, output.length));
            int firstIndex = 0;
            int secondIndex = 0;
            for (int index = 0; index < limit; ++index) {
                firstIndex = (firstIndex + 1) % 256;
                secondIndex = (secondIndex + this.state[firstIndex]) % 256;
                this.swap(firstIndex, secondIndex);
                int keyByte = this.state[(this.state[firstIndex] + this.state[secondIndex]) % 256];
                output[index] = (byte)(output[index] ^ keyByte);
            }
            byte[] byArray = output;
            return byArray;
        }
        finally {
            this.stateLock.unlock();
        }
    }

    private void initializeState(byte[] key) {
        for (int index = 0; index < 256; ++index) {
            this.state[index] = index;
        }
        int keyIndex = 0;
        for (int index = 0; index < 256; ++index) {
            keyIndex = (keyIndex + this.state[index] + Byte.toUnsignedInt(key[index % key.length])) % 256;
            this.swap(index, keyIndex);
        }
    }

    private void swap(int first, int second) {
        int value = this.state[first];
        this.state[first] = this.state[second];
        this.state[second] = value;
    }
}

