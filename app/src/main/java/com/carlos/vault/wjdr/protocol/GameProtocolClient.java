package com.carlos.vault.wjdr.protocol;

import com.carlos.vault.wjdr.WjdrLog;

import com.carlos.vault.wjdr.protocol.FrameCodec;
import com.carlos.vault.wjdr.protocol.SprotoCodec;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

public class GameProtocolClient {

    /** 连接配置（App 与 vault-server 共用；proxy 仅服务器侧静态住宅出口使用） */
    public static final class Config {
        public String gameHost;
        public int gamePort = 30101;
        public int readTimeoutMillis = 70000;
        public boolean traceEnabled = false;
        public int kickCooldownMillis = 60000;
        public int logContentMaxLength = 600;
        /** 出口代理（null = 直连，保持既有行为）。 */
        public java.net.Proxy proxy;
    }
        private static final String TAG = "WjdrProto";
    private static final int CONNECT_TIMEOUT_MILLIS = 8000;
    private static final int FRAME_LENGTH_HEADER_BYTES = 2;
    private static final int PREVIEW_HEX_BYTES = 32;
    private static final int CHEAT_MONITORING_KICK_CODE = 40271;
    private final Config config;
    private final SprotoCodec sprotoCodec;
    private final AtomicInteger sessionCounter = new AtomicInteger();
    private final ConcurrentHashMap<Integer, CompletableFuture<GameMessage>> pendingResponses = new ConcurrentHashMap();
    private final CopyOnWriteArrayList<Consumer<GameMessage>> inboundListeners = new CopyOnWriteArrayList();
    private final ConcurrentHashMap<String, Long> listenerFailureWarnedAt = new ConcurrentHashMap();
    private final ExecutorService callbackExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean readerRunning = new AtomicBoolean(false);
    private final AtomicLong reconnectBlockedUntilMillis = new AtomicLong();
    private final ReentrantLock ioLock = new ReentrantLock();
    private volatile Socket socket;
    private volatile InputStream inputStream;
    private volatile OutputStream outputStream;

    public GameProtocolClient(Config config, SprotoCodec sprotoCodec) {
        this.config = config;
        this.sprotoCodec = sprotoCodec;
    }

    public void initialize(byte[] c2sSchema, byte[] s2cSchema) {
        WjdrLog.i("GameProtocolClient prepared for {}:{}", (Object)this.config.gameHost, (Object)this.config.gamePort);
        this.sprotoCodec.loadSchemas(c2sSchema, s2cSchema);
    }

    public boolean isCodecAvailable() {
        return this.sprotoCodec.available();
    }

    public void connect() {
        this.ioLock.lock();
        try {
            long blockedUntil = this.reconnectBlockedUntilMillis.get();
            long now = System.currentTimeMillis();
            if (blockedUntil > now) {
                throw new IllegalStateException("\u6e38\u620f\u4f1a\u8bdd\u5df2\u88ab\u670d\u52a1\u7aef\u5f3a\u5236\u4e0b\u7ebf\uff0c\u51b7\u5374\u4e2d\uff0c\u5269\u4f59 " + (blockedUntil - now) + "ms");
            }
            if (this.socket != null && this.socket.isConnected() && !this.socket.isClosed()) {
                return;
            }
            this.requireCodecAvailable();
            Socket newSocket = this.openSocket();
            newSocket.setKeepAlive(true);
            newSocket.setTcpNoDelay(true);
            newSocket.setSoTimeout(this.config.readTimeoutMillis);
            this.socket = newSocket;
            this.inputStream = new BufferedInputStream(newSocket.getInputStream());
            this.outputStream = new BufferedOutputStream(newSocket.getOutputStream());
            if (this.readerRunning.compareAndSet(false, true)) {
                this.callbackExecutor.execute(this::readerLoop);
            }
        }
        catch (IOException ex) {
            this.disconnect();
            throw new IllegalStateException("\u8fde\u63a5\u6e38\u620f\u670d\u52a1\u5668\u5931\u8d25", ex);
        }
        finally {
            this.ioLock.unlock();
        }
    }

    private int protocolReadTimeoutMillis() {
        return this.config.readTimeoutMillis;
    }

    private Socket openSocket() throws IOException {
        Socket direct = this.config.proxy != null
                ? new Socket(this.config.proxy)
                : new Socket();
        direct.connect(new InetSocketAddress(this.config.gameHost, this.config.gamePort), 8000);
        return direct;
    }

    public boolean isConnected() {
        Socket current = this.socket;
        return current != null && current.isConnected() && !current.isClosed();
    }

    public void clearReconnectBlock() {
        this.reconnectBlockedUntilMillis.set(0L);
    }

    public GameMessage request(String proto, Map<String, Object> args, Duration timeout) {
        this.requireCodecAvailable();
        this.connect();
        int session = this.sessionCounter.incrementAndGet();
        CompletableFuture responseFuture = new CompletableFuture();
        this.pendingResponses.put(session, responseFuture);
        try {
            this.sendRequestFrame(proto, args, session);
            GameMessage response = (GameMessage)responseFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            this.logProtocol("RSP", response.getProto(), response.getSession(), response.getContent(), response.getLength());
            if (this.hasKickCode(response)) {
                this.enterKickCooldown(response);
                this.disconnect();
                throw new IllegalStateException("\u670d\u52a1\u7aef\u5f3a\u5236\u4e0b\u7ebf code=40271");
            }
            GameMessage gameMessage = response;
            return gameMessage;
        }
        catch (TimeoutException ex) {
            WjdrLog.w("\u7b49\u5f85\u534f\u8bae\u54cd\u5e94\u8d85\u65f6 proto={} session={}\uff0c\u65ad\u5f00\u8fde\u63a5\u4ee5\u89e3\u9664\u963b\u585e", (Object)proto, (Object)session);
            this.disconnect();
            throw new IllegalStateException("\u7b49\u5f85\u534f\u8bae\u54cd\u5e94\u8d85\u65f6: " + proto, ex);
        }
        catch (ExecutionException ex) {
            throw new IllegalStateException("\u534f\u8bae\u8c03\u7528\u5931\u8d25: " + proto, ex.getCause());
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("\u534f\u8bae\u8c03\u7528\u88ab\u4e2d\u65ad: " + proto, ex);
        }
        finally {
            this.pendingResponses.remove(session);
            this.sprotoCodec.releaseSession(session);
        }
    }

    public void sendFireAndForget(String proto, Map<String, Object> args) {
        this.requireCodecAvailable();
        this.connect();
        int session = this.sessionCounter.incrementAndGet();
        this.sendRequestFrame(proto, args, session);
    }

    private void requireCodecAvailable() {
        if (!this.isCodecAvailable()) {
            throw new IllegalStateException("\u6e38\u620f\u534f\u8bae\u7ed3\u6784\u5c1a\u672a\u52a0\u8f7d\uff0c\u8bf7\u5148\u521d\u59cb\u5316\u534f\u8bae\u5ba2\u6237\u7aef");
        }
    }

    private void sendRequestFrame(String proto, Map<String, Object> args, int session) {
        byte[] payload = this.sprotoCodec.encodeRequest(proto, args, session);
        byte[] frame = FrameCodec.prependLength(payload);
        this.logProtocol("REQ", proto, session, args, payload.length);
        this.writeFrame(frame);
    }

    public void disconnect() {
        this.closeQuietly(this.socket);
        this.ioLock.lock();
        try {
            this.readerRunning.set(false);
            this.pendingResponses.forEach((session, future) -> future.completeExceptionally(new IllegalStateException("\u8fde\u63a5\u5df2\u5173\u95ed")));
            this.pendingResponses.clear();
            this.sprotoCodec.clearSessions();
            this.closeQuietly(this.inputStream);
            this.closeQuietly(this.outputStream);
            this.closeQuietly(this.socket);
            this.inputStream = null;
            this.outputStream = null;
            this.socket = null;
        }
        finally {
            this.ioLock.unlock();
        }
    }

    public void updateSecretKey(byte[] secretKey) {
        if (!this.isCodecAvailable()) {
            return;
        }
        this.sprotoCodec.updateSecretKey(secretKey);
    }

    public AutoCloseable addInboundListener(Consumer<GameMessage> listener) {
        this.inboundListeners.add(listener);
        return () -> this.inboundListeners.remove(listener);
    }

    private void writeFrame(byte[] frame) {
        this.ioLock.lock();
        try {
            OutputStream current = this.outputStream;
            if (current == null) {
                throw new IOException("\u6e38\u620f\u8fde\u63a5\u8f93\u51fa\u6d41\u4e0d\u53ef\u7528");
            }
            current.write(frame);
            current.flush();
        }
        catch (IOException ex) {
            this.disconnect();
            throw new IllegalStateException("\u53d1\u9001\u534f\u8bae\u6570\u636e\u5931\u8d25", ex);
        }
        finally {
            this.ioLock.unlock();
        }
    }

    private void readerLoop() {
        while (this.readerRunning.get()) {
            try {
                int length;
                byte[] payload;
                byte[] header = this.readFully(2);
                if (header.length < 2 || (payload = this.readFully(length = FrameCodec.readLength(header))).length < length) break;
                try {
                    this.handleIncomingPayload(payload);
                }
                catch (Exception ex) {
                    WjdrLog.w("Failed to handle inbound protocol packet length={} preview={}", new Object[]{length, this.previewHex(payload), ex});
                }
            }
            catch (Exception ex) {
                if (!this.readerRunning.get()) break;
                if (ex instanceof SocketTimeoutException) {
                    WjdrLog.w("Game protocol reader idle timeout after {}ms, closing connection", (Object)this.config.readTimeoutMillis);
                    break;
                }
                WjdrLog.w("Game protocol reader loop terminated", (Throwable)ex);
                break;
            }
        }
        this.disconnect();
    }

    private void handleIncomingPayload(byte[] payload) {
        CompletableFuture<GameMessage> future;
        GameMessage message = this.sprotoCodec.decodeResponse(payload);
        if (message == null) {
            return;
        }
        CompletableFuture<GameMessage> completableFuture = future = "RESPONSE".equals(message.getType()) ? this.pendingResponses.get(message.getSession()) : null;
        if (future != null) {
            future.complete(message);
            this.notifyInboundListeners(message);
            return;
        }
        this.notifyInboundListeners(message);
        if (this.hasKickCode(message)) {
            this.enterKickCooldown(message);
            this.disconnect();
            return;
        }
        if (message.getContent() != null && message.getContent().containsKey("_unknown_proto_tag")) {
            WjdrLog.d("Ignored unknown server protocol proto={} session={} content={}", new Object[]{message.getProto(), message.getSession(), message.getContent()});
            return;
        }
        WjdrLog.d("Unmatched protocol message type={} proto={} session={}", new Object[]{message.getType(), message.getProto(), message.getSession()});
    }

    private void notifyInboundListeners(GameMessage message) {
        for (Consumer<GameMessage> listener : this.inboundListeners) {
            try {
                listener.accept(message);
            }
            catch (Exception ex) {
                String key = message.getProto() + "|" + ex.getClass().getName();
                long now = System.currentTimeMillis();
                Long warnedAt = this.listenerFailureWarnedAt.get(key);
                if (warnedAt != null && now - warnedAt <= 60000L) continue;
                this.listenerFailureWarnedAt.put(key, now);
                if (this.listenerFailureWarnedAt.size() > 1024) {
                    this.listenerFailureWarnedAt.clear();
                }
                WjdrLog.w("Inbound listener failed and skipped proto={}", (Object)message.getProto(), (Object)ex);
            }
        }
    }

    private byte[] readFully(int length) throws IOException {
        int read;
        byte[] data = new byte[length];
        for (int offset = 0; offset < length; offset += read) {
            read = this.inputStream.read(data, offset, length - offset);
            if (read >= 0) continue;
            return offset == 0 ? new byte[]{} : Arrays.copyOf(data, offset);
        }
        return data;
    }

    private boolean hasKickCode(GameMessage message) {
        if (message == null || message.getContent() == null) {
            return false;
        }
        Object code = message.getContent().get("code");
        if (code instanceof Number) {
            Number number = (Number)code;
            return number.intValue() == 40271;
        }
        return String.valueOf(code).equals(String.valueOf(40271));
    }

    private void enterKickCooldown(GameMessage message) {
        long cooldownMillis = Math.max(0L, this.config.kickCooldownMillis);
        this.reconnectBlockedUntilMillis.set(System.currentTimeMillis() + cooldownMillis);
        WjdrLog.e("Received server kick code={} proto={} session={}, pause reconnect for {}ms", new Object[]{40271, message.getProto(), message.getSession(), cooldownMillis});
    }

    private void closeQuietly(AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        }
        catch (Exception ex) {
            WjdrLog.d("Ignore close error", (Throwable)ex);
        }
    }

    public void shutdown() {
        this.disconnect();
        this.callbackExecutor.shutdown();
    }

    private String previewHex(byte[] payload) {
        int limit = Math.min(payload.length, 32);
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < limit; i++) hex.append(String.format("%02x", payload[i]));
        String preview = hex.toString();
        return payload.length > limit ? preview + "..." : preview;
    }

    private void logProtocol(String direction, String proto, int session, Object content, int payloadSize) {
        if (!this.config.traceEnabled) {
            return;
        }
        String pattern = "{} => proto={} session={} length={} content={}";
        Object[] arguments = new Object[]{direction, proto, session, payloadSize, this.abbreviateContent(content)};
        if ("req_heartbeat".equals(proto)) {
            WjdrLog.d(pattern, arguments);
            return;
        }
        WjdrLog.i(pattern, arguments);
    }

    private String abbreviateContent(Object content) {
        String text = String.valueOf(this.maskSensitive(content));
        int maxLength = Math.max(120, this.config.logContentMaxLength);
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    private Object maskSensitive(Object content) {
        if (!(content instanceof Map)) {
            return content;
        }
        Map rawMap = (Map)content;
        LinkedHashMap masked = new LinkedHashMap();
        rawMap.forEach((key, value) -> {
            String field = String.valueOf(key);
            String normalized = field.toLowerCase(Locale.ROOT);
            if (normalized.contains("secret") || normalized.contains("token") || normalized.contains("password")) {
                masked.put(field, "***");
            } else {
                masked.put(field, value);
            }
        });
        return masked;
    }
}

