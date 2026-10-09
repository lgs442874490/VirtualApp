package com.carlos.vault.wjdr.protocol;

public final class PortableSprotoCodecPackedSegment {

    public static PortableSprotoCodecPackedSegment startFf(byte[] bytes) {
        return new PortableSprotoCodecPackedSegment(true, false, bytes);
    }

    public static PortableSprotoCodecPackedSegment continueFf() {
        return new PortableSprotoCodecPackedSegment(false, true, new byte[0]);
    }

    public static PortableSprotoCodecPackedSegment normal(byte[] bytes) {
        return new PortableSprotoCodecPackedSegment(false, false, bytes);
    }

    private final boolean isFfStart;
    private final boolean isFfContinue;
    private final byte[] bytes;

    public PortableSprotoCodecPackedSegment(boolean isFfStart, boolean isFfContinue, byte[] bytes) {
            this.isFfStart = isFfStart;
            this.isFfContinue = isFfContinue;
            this.bytes = bytes;
    }

        public boolean isFfStart() { return this.isFfStart; }
        public boolean isFfContinue() { return this.isFfContinue; }
        public byte[] bytes() { return this.bytes; }
}
