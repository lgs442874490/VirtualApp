/*
 * Decompiled with CFR 0.152.
 */
package com.wjdr.tool.modules.registration.model;

public enum PhoneRegistrationStatus {
    QUEUED,
    RUNNING,
    SUCCESS,
    FAILED;


    public boolean terminal() {
        return this == SUCCESS || this == FAILED;
    }
}

