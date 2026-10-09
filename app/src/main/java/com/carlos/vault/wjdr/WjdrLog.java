package com.carlos.vault.wjdr;

/**
 * 引擎日志（平台中立）：默认打到 stdout，宿主可注入 sink（App 注 android.util.Log，服务器注文件日志）。
 */
public final class WjdrLog {

    /** level: I / W / E / D */
    public interface Sink {
        void log(char level, String tag, String message);
    }

    private static volatile Sink sink = (level, tag, message) ->
            System.out.println(tag + "/" + level + ": " + message);

    public static void setSink(Sink s) {
        if (s != null) sink = s;
    }

    public static void i(String format, Object... args) {
        sink.log('I', "WjdrProto", safe(format, args));
    }

    public static void w(String format, Object... args) {
        sink.log('W', "WjdrProto", safe(format, args));
    }

    public static void e(String format, Object... args) {
        sink.log('E', "WjdrProto", safe(format, args));
    }

    public static void d(String format, Object... args) {
        sink.log('D', "WjdrProto", safe(format, args));
    }

    private static String safe(String format, Object... args) {
        try {
            String f = String.valueOf(format);
            int placeholders = f.split("\\{}", -1).length - 1;
            if (args == null || args.length == 0 || placeholders == 0) {
                return f;
            }
            StringBuilder sb = new StringBuilder();
            int argIdx = 0;
            for (int i = 0; i < f.length(); i++) {
                char c = f.charAt(i);
                if (c == '{' && i + 1 < f.length() && f.charAt(i + 1) == '}' && argIdx < args.length) {
                    sb.append(args[argIdx++]);
                    i++;
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return String.valueOf(format);
        }
    }
}
