package com.carlos.vault.wjdr.protocol;

import java.util.List;

public final class SessionClosedFailure {
    public static final String CATEGORY = "SESSION_CLOSED";
    private static final List<String> MARKERS = List.of("\u8fde\u63a5\u5df2\u5173\u95ed", "\u8fde\u63a5\u5df2\u65ad\u5f00", "\u8fde\u63a5\u4e0d\u53ef\u7528", "\u8fde\u63a5\u5df2\u91cd\u7f6e", "\u8d26\u53f7\u79bb\u7ebf", "\u8d26\u53f7\u4e0d\u5728\u7ebf", "\u4e0d\u5b58\u5728\u6216\u4e0d\u5728\u7ebf", "\u8d26\u53f7\u5df2\u5728\u5176\u4ed6\u8bbe\u5907\u4e0a\u7ebf", "\u68c0\u6d4b\u5230\u8d26\u53f7\u5728\u5176\u4ed6\u8bbe\u5907\u4e0a\u7ebf", "\u6e38\u620f\u8fde\u63a5\u8d85\u65f6", "\u6e38\u620f\u8fde\u63a5\u5df2\u5931\u6548", "\u6e38\u620f\u670d\u52a1\u5668\u6b63\u5728\u5173\u95ed", "\u6e38\u620f\u670d\u52a1\u5668\u5f3a\u5236\u65ad\u5f00\u8fde\u63a5", "\u540c\u8bbe\u5907\u8fde\u63a5\u53d1\u751f\u51b2\u7a81", "\u738b\u56fd\u8fc1\u79fb\u5bfc\u81f4\u4f1a\u8bdd\u65ad\u5f00", "\u5bb6\u56ed\u72b6\u6001\u91cd\u7f6e\u5bfc\u81f4\u4f1a\u8bdd\u65ad\u5f00", "\u6e38\u620f\u670d\u52a1\u7aef\u901a\u77e5\u4e0b\u7ebf", "\u670d\u52a1\u7aef\u5f3a\u5236\u4e0b\u7ebf", "\u89d2\u8272\u4f1a\u8bdd\u5df2\u66f4\u65b0", "\u7b49\u5f85\u534f\u8bae\u54cd\u5e94\u8d85\u65f6");
    private static final int MAX_CAUSE_DEPTH = 16;

    private SessionClosedFailure() {
    }

    public static boolean matches(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 16; current = current.getCause(), ++depth) {
            if (!SessionClosedFailure.mentions(current.getMessage())) continue;
            return true;
        }
        return false;
    }

    public static boolean mentions(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return MARKERS.stream().anyMatch(text::contains);
    }

    public static boolean mentionsAny(String ... texts) {
        if (texts == null) {
            return false;
        }
        for (String text : texts) {
            if (!SessionClosedFailure.mentions(text)) continue;
            return true;
        }
        return false;
    }
}

