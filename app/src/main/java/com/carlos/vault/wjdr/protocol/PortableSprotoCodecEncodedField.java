package com.carlos.vault.wjdr.protocol;

public final class PortableSprotoCodecEncodedField {

    public static PortableSprotoCodecEncodedField absent() {
        return new PortableSprotoCodecEncodedField(false, 0, new byte[0]);
    }

    private final boolean present;
    private final int headerValue;
    private final byte[] body;

    public PortableSprotoCodecEncodedField(boolean present, int headerValue, byte[] body) {
            this.present = present;
            this.headerValue = headerValue;
            this.body = body;
    }

        public boolean present() { return this.present; }
        public int headerValue() { return this.headerValue; }
        public byte[] body() { return this.body; }
}
