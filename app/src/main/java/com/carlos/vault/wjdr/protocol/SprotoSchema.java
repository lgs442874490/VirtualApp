package com.carlos.vault.wjdr.protocol;

import com.carlos.vault.wjdr.protocol.SprotoProtocolDef;
import com.carlos.vault.wjdr.protocol.SprotoTypeDef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SprotoSchema {
    private final List<SprotoTypeDef> types = new ArrayList<SprotoTypeDef>();
    private final List<SprotoProtocolDef> protocols = new ArrayList<SprotoProtocolDef>();
    private final Map<String, SprotoTypeDef> typesByName = new LinkedHashMap<String, SprotoTypeDef>();
    private final Map<String, SprotoProtocolDef> protocolsByName = new LinkedHashMap<String, SprotoProtocolDef>();
    private final Map<Integer, SprotoProtocolDef> protocolsByTag = new LinkedHashMap<Integer, SprotoProtocolDef>();

    public void addType(SprotoTypeDef type) {
        this.types.add(type);
        this.typesByName.put(type.getName(), type);
    }

    public void addProtocol(SprotoProtocolDef protocol) {
        this.protocols.add(protocol);
        this.protocolsByName.put(protocol.getName(), protocol);
        this.protocolsByTag.put(protocol.getTag(), protocol);
    }

    public SprotoTypeDef requireType(String name) {
        SprotoTypeDef type = this.typesByName.get(name);
        if (type == null) {
            throw new IllegalArgumentException("Unknown sproto type: " + name);
        }
        return type;
    }

    public SprotoProtocolDef requireProtocol(String name) {
        SprotoProtocolDef protocol = this.protocolsByName.get(name);
        if (protocol == null) {
            throw new IllegalArgumentException("Unknown sproto protocol: " + name);
        }
        return protocol;
    }

    public Optional<SprotoProtocolDef> findProtocol(int tag) {
        return Optional.ofNullable(this.protocolsByTag.get(tag));
    }

        public List<SprotoTypeDef> getTypes() {
        return this.types;
    }

        public List<SprotoProtocolDef> getProtocols() {
        return this.protocols;
    }

        public Map<String, SprotoTypeDef> getTypesByName() {
        return this.typesByName;
    }

        public Map<String, SprotoProtocolDef> getProtocolsByName() {
        return this.protocolsByName;
    }

        public Map<Integer, SprotoProtocolDef> getProtocolsByTag() {
        return this.protocolsByTag;
    }

        public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof SprotoSchema)) {
            return false;
        }
        SprotoSchema other = (SprotoSchema)o;
        if (!other.canEqual(this)) {
            return false;
        }
        List<SprotoTypeDef> this$types = this.getTypes();
        List<SprotoTypeDef> other$types = other.getTypes();
        if (this$types == null ? other$types != null : !((Object)this$types).equals(other$types)) {
            return false;
        }
        List<SprotoProtocolDef> this$protocols = this.getProtocols();
        List<SprotoProtocolDef> other$protocols = other.getProtocols();
        if (this$protocols == null ? other$protocols != null : !((Object)this$protocols).equals(other$protocols)) {
            return false;
        }
        Map<String, SprotoTypeDef> this$typesByName = this.getTypesByName();
        Map<String, SprotoTypeDef> other$typesByName = other.getTypesByName();
        if (this$typesByName == null ? other$typesByName != null : !((Object)this$typesByName).equals(other$typesByName)) {
            return false;
        }
        Map<String, SprotoProtocolDef> this$protocolsByName = this.getProtocolsByName();
        Map<String, SprotoProtocolDef> other$protocolsByName = other.getProtocolsByName();
        if (this$protocolsByName == null ? other$protocolsByName != null : !((Object)this$protocolsByName).equals(other$protocolsByName)) {
            return false;
        }
        Map<Integer, SprotoProtocolDef> this$protocolsByTag = this.getProtocolsByTag();
        Map<Integer, SprotoProtocolDef> other$protocolsByTag = other.getProtocolsByTag();
        return !(this$protocolsByTag == null ? other$protocolsByTag != null : !((Object)this$protocolsByTag).equals(other$protocolsByTag));
    }

        protected boolean canEqual(Object other) {
        return other instanceof SprotoSchema;
    }

        public int hashCode() {
        int PRIME = 59;
        int result = 1;
        List<SprotoTypeDef> $types = this.getTypes();
        result = result * 59 + ($types == null ? 43 : ((Object)$types).hashCode());
        List<SprotoProtocolDef> $protocols = this.getProtocols();
        result = result * 59 + ($protocols == null ? 43 : ((Object)$protocols).hashCode());
        Map<String, SprotoTypeDef> $typesByName = this.getTypesByName();
        result = result * 59 + ($typesByName == null ? 43 : ((Object)$typesByName).hashCode());
        Map<String, SprotoProtocolDef> $protocolsByName = this.getProtocolsByName();
        result = result * 59 + ($protocolsByName == null ? 43 : ((Object)$protocolsByName).hashCode());
        Map<Integer, SprotoProtocolDef> $protocolsByTag = this.getProtocolsByTag();
        result = result * 59 + ($protocolsByTag == null ? 43 : ((Object)$protocolsByTag).hashCode());
        return result;
    }

        public String toString() {
        return "SprotoSchema(types=" + String.valueOf(this.getTypes()) + ", protocols=" + String.valueOf(this.getProtocols()) + ", typesByName=" + String.valueOf(this.getTypesByName()) + ", protocolsByName=" + String.valueOf(this.getProtocolsByName()) + ", protocolsByTag=" + String.valueOf(this.getProtocolsByTag()) + ")";
    }
}

