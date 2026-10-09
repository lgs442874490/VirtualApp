package com.carlos.vault.wjdr.protocol;

final class SprotoBinarySupport {
    private SprotoBinarySupport() {
    }

    static int readInt32(byte[] data, int offset) {
        return data[offset] & 0xFF | (data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF) << 16 | (data[offset + 3] & 0xFF) << 24;
    }
}

