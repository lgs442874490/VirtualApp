package com.carlos.vault.wjdr.protocol;

public final class PortableSprotoCodecDecodedValue {

    private final Object value;
    private final int nextOffset;

    public PortableSprotoCodecDecodedValue(Object value, int nextOffset) {
            this.value = value;
            this.nextOffset = nextOffset;
    }

        public Object value() { return this.value; }
        public int nextOffset() { return this.nextOffset; }
}
