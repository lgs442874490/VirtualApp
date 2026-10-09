package com.carlos.vault.wjdr.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

public class GameMessage {
    public static final String TYPE_REQUEST = "REQUEST";
    public static final String TYPE_RESPONSE = "RESPONSE";
    private int length;
    private String proto;
    private int session;
    private String type = "REQUEST";
    private Map<String, Object> content = new LinkedHashMap<String, Object>();

    public static boolean hasProtocolContent(GameMessage message) {
        return message != null && message.getProto() != null && message.getContent() != null;
    }

        public int getLength() {
        return this.length;
    }

        public String getProto() {
        return this.proto;
    }

        public int getSession() {
        return this.session;
    }

        public String getType() {
        return this.type;
    }

        public Map<String, Object> getContent() {
        return this.content;
    }

        public void setLength(int length) {
        this.length = length;
    }

        public void setProto(String proto) {
        this.proto = proto;
    }

        public void setSession(int session) {
        this.session = session;
    }

        public void setType(String type) {
        this.type = type;
    }

        public void setContent(Map<String, Object> content) {
        this.content = content;
    }

        public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof GameMessage)) {
            return false;
        }
        GameMessage other = (GameMessage)o;
        if (!other.canEqual(this)) {
            return false;
        }
        if (this.getLength() != other.getLength()) {
            return false;
        }
        if (this.getSession() != other.getSession()) {
            return false;
        }
        String this$proto = this.getProto();
        String other$proto = other.getProto();
        if (this$proto == null ? other$proto != null : !this$proto.equals(other$proto)) {
            return false;
        }
        String this$type = this.getType();
        String other$type = other.getType();
        if (this$type == null ? other$type != null : !this$type.equals(other$type)) {
            return false;
        }
        Map<String, Object> this$content = this.getContent();
        Map<String, Object> other$content = other.getContent();
        return !(this$content == null ? other$content != null : !((Object)this$content).equals(other$content));
    }

        protected boolean canEqual(Object other) {
        return other instanceof GameMessage;
    }

        public int hashCode() {
        int PRIME = 59;
        int result = 1;
        result = result * 59 + this.getLength();
        result = result * 59 + this.getSession();
        String $proto = this.getProto();
        result = result * 59 + ($proto == null ? 43 : $proto.hashCode());
        String $type = this.getType();
        result = result * 59 + ($type == null ? 43 : $type.hashCode());
        Map<String, Object> $content = this.getContent();
        result = result * 59 + ($content == null ? 43 : ((Object)$content).hashCode());
        return result;
    }

        public String toString() {
        return "GameMessage(length=" + this.getLength() + ", proto=" + this.getProto() + ", session=" + this.getSession() + ", type=" + this.getType() + ", content=" + String.valueOf(this.getContent()) + ")";
    }
}

