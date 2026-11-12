/*
 * Decompiled with CFR 0.152.
 */
package com.wjdr.tool.modules.registration.model;

import java.util.Locale;

public enum RegistrationProviderType {
    D1("D1", true),
    HAOZHU("\u8c6a\u732a", false);

    private final String label;
    private final boolean automaticNumberDisposal;

    private RegistrationProviderType(String label, boolean automaticNumberDisposal) {
        this.label = label;
        this.automaticNumberDisposal = automaticNumberDisposal;
    }

    public static RegistrationProviderType from(String value) {
        if (value == null || value.isBlank()) {
            return HAOZHU;
        }
        try {
            return RegistrationProviderType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("\u4e0d\u652f\u6301\u7684\u63a5\u7801\u5e73\u53f0\uff1a" + value, exception);
        }
    }

    public String label() {
        return this.label;
    }

    public boolean automaticNumberDisposal() {
        return this.automaticNumberDisposal;
    }
}

