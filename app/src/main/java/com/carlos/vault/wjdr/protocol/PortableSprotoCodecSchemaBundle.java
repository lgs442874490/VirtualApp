package com.carlos.vault.wjdr.protocol;

public final class PortableSprotoCodecSchemaBundle {

    private final SprotoSchema c2sSchema;
    private final SprotoTypeDef c2sPackageType;
    private final SprotoSchema s2cSchema;
    private final SprotoTypeDef s2cPackageType;

    public PortableSprotoCodecSchemaBundle(SprotoSchema c2sSchema, SprotoTypeDef c2sPackageType, SprotoSchema s2cSchema, SprotoTypeDef s2cPackageType) {
            this.c2sSchema = c2sSchema;
            this.c2sPackageType = c2sPackageType;
            this.s2cSchema = s2cSchema;
            this.s2cPackageType = s2cPackageType;
    }

        public SprotoSchema c2sSchema() { return this.c2sSchema; }
        public SprotoTypeDef c2sPackageType() { return this.c2sPackageType; }
        public SprotoSchema s2cSchema() { return this.s2cSchema; }
        public SprotoTypeDef s2cPackageType() { return this.s2cPackageType; }
}
