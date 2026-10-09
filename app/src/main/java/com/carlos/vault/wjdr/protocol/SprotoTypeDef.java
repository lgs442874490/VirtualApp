package com.carlos.vault.wjdr.protocol;

import com.carlos.vault.wjdr.protocol.SprotoFieldDef;
import java.util.ArrayList;
import java.util.List;

public class SprotoTypeDef {
    private String name;
    private List<SprotoFieldDef> fields = new ArrayList<SprotoFieldDef>();
    private int base = -1;
    private int maxFields;

    public SprotoFieldDef findField(int tag) {
        SprotoFieldDef field2;
        int index;
        if (this.fields.isEmpty()) {
            return null;
        }
        if (this.base >= 0 && (index = tag - this.base) >= 0 && index < this.fields.size() && (field2 = this.fields.get(index)).getTag() == tag) {
            return field2;
        }
        for (SprotoFieldDef candidate : this.fields) {
            if (candidate.getTag() != tag) continue;
            return candidate;
        }
        return null;
    }

        public String getName() {
        return this.name;
    }

        public List<SprotoFieldDef> getFields() {
        return this.fields;
    }

        public int getBase() {
        return this.base;
    }

        public int getMaxFields() {
        return this.maxFields;
    }

        public void setName(String name) {
        this.name = name;
    }

        public void setFields(List<SprotoFieldDef> fields) {
        this.fields = fields;
    }

        public void setBase(int base) {
        this.base = base;
    }

        public void setMaxFields(int maxFields) {
        this.maxFields = maxFields;
    }

        public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof SprotoTypeDef)) {
            return false;
        }
        SprotoTypeDef other = (SprotoTypeDef)o;
        if (!other.canEqual(this)) {
            return false;
        }
        if (this.getBase() != other.getBase()) {
            return false;
        }
        if (this.getMaxFields() != other.getMaxFields()) {
            return false;
        }
        String this$name = this.getName();
        String other$name = other.getName();
        if (this$name == null ? other$name != null : !this$name.equals(other$name)) {
            return false;
        }
        List<SprotoFieldDef> this$fields = this.getFields();
        List<SprotoFieldDef> other$fields = other.getFields();
        return !(this$fields == null ? other$fields != null : !((Object)this$fields).equals(other$fields));
    }

        protected boolean canEqual(Object other) {
        return other instanceof SprotoTypeDef;
    }

        public int hashCode() {
        int PRIME = 59;
        int result = 1;
        result = result * 59 + this.getBase();
        result = result * 59 + this.getMaxFields();
        String $name = this.getName();
        result = result * 59 + ($name == null ? 43 : $name.hashCode());
        List<SprotoFieldDef> $fields = this.getFields();
        result = result * 59 + ($fields == null ? 43 : ((Object)$fields).hashCode());
        return result;
    }

        public String toString() {
        return "SprotoTypeDef(name=" + this.getName() + ", fields=" + String.valueOf(this.getFields()) + ", base=" + this.getBase() + ", maxFields=" + this.getMaxFields() + ")";
    }
}

