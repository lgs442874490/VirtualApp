/*
 * Decompiled with CFR 0.152.
 */
package com.wjdr.tool.modules.registration.model;

public enum PhoneRegistrationStage {
    QUEUED("\u7b49\u5f85\u5f00\u59cb"),
    PROVIDER_LOGIN("\u767b\u5f55\u63a5\u7801\u5e73\u53f0"),
    PROVIDER_BALANCE("\u67e5\u8be2\u5e73\u53f0\u4f59\u989d"),
    ACQUIRING_NUMBER("\u83b7\u53d6\u53f7\u7801"),
    SENDING_GAME_SMS("\u53d1\u9001\u6e38\u620f\u77ed\u4fe1"),
    WAITING_SMS("\u7b49\u5f85\u77ed\u4fe1"),
    VALIDATING_CAPTCHA("\u6821\u9a8c\u8bc1\u7801"),
    REGISTERING_ACCOUNT("\u6ce8\u518c\u8d26\u53f7"),
    VERIFYING_REAL_NAME("\u5b9e\u540d\u8ba4\u8bc1"),
    SUCCESS("\u6ce8\u518c\u6210\u529f"),
    FAILED("\u6ce8\u518c\u5931\u8d25"),
    INTERRUPTED("\u6267\u884c\u4e2d\u65ad");

    private final String label;

    private PhoneRegistrationStage(String label) {
        this.label = label;
    }

    public String label() {
        return this.label;
    }
}

