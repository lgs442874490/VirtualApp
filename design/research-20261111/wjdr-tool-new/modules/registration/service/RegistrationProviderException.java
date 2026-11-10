/*
 * Decompiled with CFR 0.152.
 */
package com.wjdr.tool.modules.registration.service;

public class RegistrationProviderException
extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private static final long NETWORK_ERROR = Long.MIN_VALUE;

    public RegistrationProviderException(String message, long providerCode) {
        super(RegistrationProviderException.withCode(message, providerCode));
    }

    public RegistrationProviderException(String message, long providerCode, Throwable cause) {
        super(RegistrationProviderException.withCode(message, providerCode), cause);
    }

    private static String withCode(String message, long providerCode) {
        return providerCode == Long.MIN_VALUE ? message : message + "\uff08code=" + providerCode + "\uff09";
    }
}

