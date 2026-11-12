/*
 * Decompiled with CFR 0.152.
 */
package com.wjdr.tool.modules.bootstrap.service;

import java.util.List;

final class BootstrapCdkeyCatalog {
    private static final List<String> CODES = List.of("WJDR666", "WJDR2025", "WOAIWJDR", "TILI520", "\u5434\u6a3e888", "\u5434\u6a3e666", "\u91d1\u5b50888", "\u91d1\u5b50666", "\u5927\u806a666", "\u5927\u806a888", "JC666", "JC888", "666WJDR2024");

    private BootstrapCdkeyCatalog() {
    }

    static List<String> codes() {
        return CODES;
    }
}

