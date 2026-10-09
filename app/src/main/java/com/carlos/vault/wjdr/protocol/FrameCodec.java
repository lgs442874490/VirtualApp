package com.carlos.vault.wjdr.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class FrameCodec {
    private FrameCodec() {
    }

    public static byte[] prependLength(byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(payload.length + 2).order(ByteOrder.BIG_ENDIAN);
        buffer.putShort((short)payload.length);
        buffer.put(payload);
        return buffer.array();
    }

    public static int readLength(byte[] frame) {
        return ByteBuffer.wrap(frame, 0, 2).order(ByteOrder.BIG_ENDIAN).getShort() & 0xFFFF;
    }
}

