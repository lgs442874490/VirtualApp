package com.carlos.vault.wjdr.protocol;

public final class PortableSprotoCodecSchemaCacheKey {

    private final String c2sPath;
    private final String s2cPath;

    public PortableSprotoCodecSchemaCacheKey(String c2sPath, String s2cPath) {
            this.c2sPath = c2sPath;
            this.s2cPath = s2cPath;
    }

        public String c2sPath() { return this.c2sPath; }
        public String s2cPath() { return this.s2cPath; }
}
