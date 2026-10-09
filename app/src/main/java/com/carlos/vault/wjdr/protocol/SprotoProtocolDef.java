package com.carlos.vault.wjdr.protocol;

import com.carlos.vault.wjdr.protocol.SprotoTypeDef;

public class SprotoProtocolDef {
    private String name;
    private int tag;
    private int confirm;
    private SprotoTypeDef requestType;
    private SprotoTypeDef responseType;

        public String getName() {
        return this.name;
    }

        public int getTag() {
        return this.tag;
    }

        public int getConfirm() {
        return this.confirm;
    }

        public SprotoTypeDef getRequestType() {
        return this.requestType;
    }

        public SprotoTypeDef getResponseType() {
        return this.responseType;
    }

        public void setName(String name) {
        this.name = name;
    }

        public void setTag(int tag) {
        this.tag = tag;
    }

        public void setConfirm(int confirm) {
        this.confirm = confirm;
    }

        public void setRequestType(SprotoTypeDef requestType) {
        this.requestType = requestType;
    }

        public void setResponseType(SprotoTypeDef responseType) {
        this.responseType = responseType;
    }

        public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof SprotoProtocolDef)) {
            return false;
        }
        SprotoProtocolDef other = (SprotoProtocolDef)o;
        if (!other.canEqual(this)) {
            return false;
        }
        if (this.getTag() != other.getTag()) {
            return false;
        }
        if (this.getConfirm() != other.getConfirm()) {
            return false;
        }
        String this$name = this.getName();
        String other$name = other.getName();
        if (this$name == null ? other$name != null : !this$name.equals(other$name)) {
            return false;
        }
        SprotoTypeDef this$requestType = this.getRequestType();
        SprotoTypeDef other$requestType = other.getRequestType();
        if (this$requestType == null ? other$requestType != null : !((Object)this$requestType).equals(other$requestType)) {
            return false;
        }
        SprotoTypeDef this$responseType = this.getResponseType();
        SprotoTypeDef other$responseType = other.getResponseType();
        return !(this$responseType == null ? other$responseType != null : !((Object)this$responseType).equals(other$responseType));
    }

        protected boolean canEqual(Object other) {
        return other instanceof SprotoProtocolDef;
    }

        public int hashCode() {
        int PRIME = 59;
        int result = 1;
        result = result * 59 + this.getTag();
        result = result * 59 + this.getConfirm();
        String $name = this.getName();
        result = result * 59 + ($name == null ? 43 : $name.hashCode());
        SprotoTypeDef $requestType = this.getRequestType();
        result = result * 59 + ($requestType == null ? 43 : ((Object)$requestType).hashCode());
        SprotoTypeDef $responseType = this.getResponseType();
        result = result * 59 + ($responseType == null ? 43 : ((Object)$responseType).hashCode());
        return result;
    }

        public String toString() {
        return "SprotoProtocolDef(name=" + this.getName() + ", tag=" + this.getTag() + ", confirm=" + this.getConfirm() + ", requestType=" + String.valueOf(this.getRequestType()) + ", responseType=" + String.valueOf(this.getResponseType()) + ")";
    }
}

