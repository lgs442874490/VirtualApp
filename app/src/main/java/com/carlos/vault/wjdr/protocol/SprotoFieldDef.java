package com.carlos.vault.wjdr.protocol;

import com.carlos.vault.wjdr.protocol.SprotoTypeDef;

public class SprotoFieldDef {
    private String name;
    private int tag;
    private int type;
    private SprotoTypeDef subtype;
    private int key = -1;
    private int map = -1;
    private int extra;

    public boolean isArray() {
        return (this.type & 0x80) != 0;
    }

    public int baseType() {
        return this.type & 0xFFFFFF7F;
    }

        public String getName() {
        return this.name;
    }

        public int getTag() {
        return this.tag;
    }

        public int getType() {
        return this.type;
    }

        public SprotoTypeDef getSubtype() {
        return this.subtype;
    }

        public int getKey() {
        return this.key;
    }

        public int getMap() {
        return this.map;
    }

        public int getExtra() {
        return this.extra;
    }

        public void setName(String name) {
        this.name = name;
    }

        public void setTag(int tag) {
        this.tag = tag;
    }

        public void setType(int type) {
        this.type = type;
    }

        public void setSubtype(SprotoTypeDef subtype) {
        this.subtype = subtype;
    }

        public void setKey(int key) {
        this.key = key;
    }

        public void setMap(int map) {
        this.map = map;
    }

        public void setExtra(int extra) {
        this.extra = extra;
    }

        public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof SprotoFieldDef)) {
            return false;
        }
        SprotoFieldDef other = (SprotoFieldDef)o;
        if (!other.canEqual(this)) {
            return false;
        }
        if (this.getTag() != other.getTag()) {
            return false;
        }
        if (this.getType() != other.getType()) {
            return false;
        }
        if (this.getKey() != other.getKey()) {
            return false;
        }
        if (this.getMap() != other.getMap()) {
            return false;
        }
        if (this.getExtra() != other.getExtra()) {
            return false;
        }
        String this$name = this.getName();
        String other$name = other.getName();
        if (this$name == null ? other$name != null : !this$name.equals(other$name)) {
            return false;
        }
        SprotoTypeDef this$subtype = this.getSubtype();
        SprotoTypeDef other$subtype = other.getSubtype();
        return !(this$subtype == null ? other$subtype != null : !((Object)this$subtype).equals(other$subtype));
    }

        protected boolean canEqual(Object other) {
        return other instanceof SprotoFieldDef;
    }

        public int hashCode() {
        int PRIME = 59;
        int result = 1;
        result = result * 59 + this.getTag();
        result = result * 59 + this.getType();
        result = result * 59 + this.getKey();
        result = result * 59 + this.getMap();
        result = result * 59 + this.getExtra();
        String $name = this.getName();
        result = result * 59 + ($name == null ? 43 : $name.hashCode());
        SprotoTypeDef $subtype = this.getSubtype();
        result = result * 59 + ($subtype == null ? 43 : ((Object)$subtype).hashCode());
        return result;
    }

        public String toString() {
        return "SprotoFieldDef(name=" + this.getName() + ", tag=" + this.getTag() + ", type=" + this.getType() + ", subtype=" + String.valueOf(this.getSubtype()) + ", key=" + this.getKey() + ", map=" + this.getMap() + ", extra=" + this.getExtra() + ")";
    }
}

