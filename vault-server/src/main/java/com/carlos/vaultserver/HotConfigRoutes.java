package com.carlos.vaultserver;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 热更新自托管静态路由（无签名 / 无信封）：
 *
 * <pre>
 * GET /v1/vault/hotconfig       返回 {@code dataDir/hotconfig.json}，其中 {base} 替换为
 *                               scheme://Host（X-Forwarded-Proto 优先，否则 http）
 * GET /v1/vault/app-latest.apk  流式返回 {@code dataDir/download/app-latest.apk} 整包
 * </pre>
 *
 * 背景：App 的 {@code HotConfig} 默认更新源即 {@code VaultSync.baseUrl(ctx)
 * + "/v1/vault/hotconfig"}；nginx 已反代 {@code /v1/vault/}，故本路由与签名业务
 * 端点同前缀、不参与 ApiSign 验签。hotconfig.json 由 {@code tools/hotupdate_prepare.sh}
 * 生成后 scp 到服务器 dataDir；{base} 占位符让同一份 JSON 在 http/https、不同
 * Host（IP / 域名 / 端口）下都能拼出正确下载地址。
 *
 * <p>{base} 推导优先级：{@code VAULT_PUBLIC_BASE} 环境变量（可选部署兜底）→
 * X-Forwarded-Proto/X-Forwarded-Host → Host 头（缺失回落监听地址）。
 *
 * 错误一律 JSON 信封（{code,message,data,serverTime}），与 VaultHttpApi 一致；
 * 成功时 hotconfig 原样返回 JSON（不做 envelope 包裹，App 直接解析 version/apk）。
 */
final class HotConfigRoutes {

    /** 热更配置文件（相对 dataDir）。 */
    static final String HOT_CONFIG_FILE = "hotconfig.json";
    /** 整包文件（相对 dataDir）。 */
    static final String APK_FILE = "download/app-latest.apk";
    /** DexPatch 代码补丁文件（相对 dataDir）。 */
    static final String PATCH_FILE = "patch/code.dex";
    /** Android 安装包 MIME（浏览器/系统下载器识别用）。 */
    static final String APK_CONTENT_TYPE = "application/vnd.android.package-archive";
    /** 补丁 dex 的 MIME（二进制流）。 */
    static final String PATCH_CONTENT_TYPE = "application/octet-stream";
    /** JSON 中下载地址的服务器占位符（替换为 scheme://Host）。 */
    static final String PLACEHOLDER_BASE = "{base}";

    /** 热更 JSON 体积上限（与 App 端 HotConfig 的 2MB 安全边界一致）。 */
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    /** APK 流式拷贝缓冲区。 */
    private static final int APK_BUFFER_BYTES = 64 * 1024;

    /**
     * 可选部署兜底：nginx 若未透传 Host/X-Forwarded-Host（反代会把 Host 改成
     * 127.0.0.1:8790），可设 {@code VAULT_PUBLIC_BASE=https://103.236.96.82:64868}
     * 强制 {base} 取对外基址，无需改 nginx/发版。未设置时按请求头推导（契约行为）。
     */
    private static volatile String publicBaseOverride;

    private HotConfigRoutes() {
    }

    /** GET /v1/vault/hotconfig：读 dataDir/hotconfig.json，替换 {base} 后原样返回。 */
    static void serveHotConfig(HttpExchange ex, Path dataDir) throws IOException {
        if (!isGet(ex)) return;
        Path file = dataDir.resolve(HOT_CONFIG_FILE);
        if (!Files.isRegularFile(file)) {
            writeEnvelope(ex, 404, 3002, "hotconfig not found");
            return;
        }
        String raw;
        try {
            if (Files.size(file) > MAX_JSON_BYTES) {
                writeEnvelope(ex, 413, 3003, "hotconfig too large");
                return;
            }
            raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            writeEnvelope(ex, 500, 3001, "hotconfig read failed");
            return;
        }
        String host = ex.getRequestHeaders().getFirst("X-Forwarded-Host");
        if (host == null || host.trim().isEmpty()) {
            host = ex.getRequestHeaders().getFirst("Host");
        }
        String configuredBase = configuredPublicBase();
        String body = configuredBase.isEmpty()
                ? renderHotConfig(raw, ex.getRequestHeaders().getFirst("X-Forwarded-Proto"),
                        host, ex.getLocalAddress())
                : renderHotConfig(raw, configuredBase);
        writeRaw(ex, 200, "application/json; charset=utf-8", body);
    }

    /** GET /v1/vault/app-latest.apk：流式返回 dataDir/download/app-latest.apk。 */
    static void serveApk(HttpExchange ex, Path dataDir) throws IOException {
        if (!isGet(ex)) return;
        Path file = dataDir.resolve(APK_FILE);
        if (!Files.isRegularFile(file)) {
            writeEnvelope(ex, 404, 3002, "apk not found");
            return;
        }
        long len;
        try {
            len = Files.size(file);
        } catch (IOException e) {
            writeEnvelope(ex, 500, 3001, "apk stat failed");
            return;
        }
        if (len <= 0) {
            writeEnvelope(ex, 404, 3002, "apk empty");
            return;
        }
        ex.getResponseHeaders().set("Content-Type", APK_CONTENT_TYPE);
        // sendResponseHeaders(200, len) 会写 Content-Length；显式设置便于非 JDK 客户端/代理透传
        ex.getResponseHeaders().set("Content-Length", String.valueOf(len));
        ex.sendResponseHeaders(200, len);
        try (InputStream in = Files.newInputStream(file);
             OutputStream out = ex.getResponseBody()) {
            byte[] buf = new byte[APK_BUFFER_BYTES];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /** GET /v1/vault/patch/code.dex：流式返回 dataDir/patch/code.dex（DexPatch 代码补丁）。 */
    static void servePatch(HttpExchange ex, Path dataDir) throws IOException {
        if (!isGet(ex)) return;
        Path file = dataDir.resolve(PATCH_FILE);
        if (!Files.isRegularFile(file)) {
            writeEnvelope(ex, 404, 3002, "patch not found");
            return;
        }
        long len;
        try {
            len = Files.size(file);
        } catch (IOException e) {
            writeEnvelope(ex, 500, 3001, "patch stat failed");
            return;
        }
        if (len <= 0) {
            writeEnvelope(ex, 404, 3002, "patch empty");
            return;
        }
        ex.getResponseHeaders().set("Content-Type", PATCH_CONTENT_TYPE);
        ex.getResponseHeaders().set("Content-Length", String.valueOf(len));
        ex.sendResponseHeaders(200, len);
        try (InputStream in = Files.newInputStream(file);
             OutputStream out = ex.getResponseBody()) {
            byte[] buf = new byte[APK_BUFFER_BYTES];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    // ---------- 纯函数（便于单测） ----------

    /**
     * {base} 替换纯函数：把 JSON 文本里的所有占位符替换为 scheme://Host。
     *
     * @param rawJson        服务器磁盘上的 hotconfig.json 原文
     * @param forwardedProto X-Forwarded-Proto 头（可空/可含逗号列表，取第一段）
     * @param host           Host 头（可空，空则回落监听地址）
     * @param local          本端监听地址（Host 缺失时的兜底，可 null）
     */
    static String renderHotConfig(String rawJson, String forwardedProto, String host,
                                  InetSocketAddress local) {
        return renderHotConfig(rawJson, baseUrl(forwardedProto, host, local));
    }

    /** {base} 替换纯函数（已确定基址版，供 VAULT_PUBLIC_BASE 覆盖路径复用/单测）。 */
    static String renderHotConfig(String rawJson, String base) {
        if (rawJson == null) return "";
        return rawJson.replace(PLACEHOLDER_BASE, base == null ? "" : base);
    }

    /**
     * 对外基址纯函数：scheme://Host。X-Forwarded-Proto 优先（nginx/Caddy 反代场景），
     * 否则 http；Host 取请求头（多级反代逗号列表取第一段），缺失时回落本端监听地址。
     */
    static String baseUrl(String forwardedProto, String host, InetSocketAddress local) {
        String scheme = "http";
        if (forwardedProto != null && !forwardedProto.trim().isEmpty()) {
            String p = forwardedProto.split(",")[0].trim().toLowerCase(Locale.ROOT);
            if ("https".equals(p) || "http".equals(p)) scheme = p;
        }
        String h = host == null ? "" : host.trim();
        if (h.contains(",")) h = h.split(",")[0].trim();
        if (h.isEmpty() && local != null) {
            String lh = local.getHostString();
            h = (lh == null || lh.isEmpty() ? "127.0.0.1" : lh) + ":" + local.getPort();
        }
        return scheme + "://" + h;
    }

    /** VAULT_PUBLIC_BASE 环境变量（惰性读取一次；末尾斜杠归一；未设置返回 ""）。 */
    static String configuredPublicBase() {
        String cached = publicBaseOverride;
        if (cached != null) return cached;
        String v = System.getenv("VAULT_PUBLIC_BASE");
        v = v == null ? "" : v.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        publicBaseOverride = v;
        return v;
    }

    // ---------- 响应工具 ----------

    /** GET only；其它方法按契约回 405 + 信封 3002（与 VaultHttpApi 的 method not allowed 同码）。 */
    private static boolean isGet(HttpExchange ex) throws IOException {
        if ("GET".equals(ex.getRequestMethod())) return true;
        writeEnvelope(ex, 405, 3002, "method not allowed");
        return false;
    }

    private static void writeRaw(HttpExchange ex, int httpStatus, String contentType,
                                 String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(httpStatus, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void writeEnvelope(HttpExchange ex, int httpStatus, int code,
                                      String message) throws IOException {
        JSONObject o = new JSONObject();
        o.put("code", code);
        o.put("message", message);
        o.put("data", new JSONObject());
        o.put("serverTime", System.currentTimeMillis());
        writeRaw(ex, httpStatus, "application/json; charset=utf-8", o.toString());
    }
}
