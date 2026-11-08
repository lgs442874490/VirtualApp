package com.carlos.vault.protocapture;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import com.carlos.vault.wjdr.protocol.GameMessage;

import org.uproxy.tun2socks.Tun2SocksJni;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 真机抓包（T3）：VpnService + tun2socks + 本地 SOCKS5 中继。
 * 目标 App 的 TCP 流量经 tun2socks 进本地 SOCKS5；中继时顺带按 2 字节长度前缀切 sproto 帧，
 * C2S/S2C 各用 PortableSprotoCodec 解码写入 ProtoCaptureLog。
 */
public class ProtoCaptureService extends VpnService {
    public static final String ACTION_START = "com.carlos.vault.PROTO_CAPTURE_START";
    public static final String ACTION_STOP = "com.carlos.vault.PROTO_CAPTURE_STOP";
    public static final String EXTRA_TARGET_PACKAGE = "target_package";
    private static final String CHANNEL = "proto_capture";
    private static final int NOTIFY_ID = 0x7A31;
    /** 游戏协议端口（登录/主城/世界）；其余 TCP 仍转发但不解码记录。 */
    private static final Set<Integer> GAME_PORTS = new HashSet<>(Arrays.asList(
            30101, 30102, 30103, 30104, 30105));

    private static volatile boolean running;
    private static volatile int socksPort;
    private static volatile String targetPackage = "";
    /** 并发 SOCKS 连接上限：防止回环/异常客户端把线程与内存打爆（真机 OOM 崩溃）。 */
    private final java.util.concurrent.atomic.AtomicInteger activeConns =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 启停互斥：避免 start/stop 并发导致 libtun2socks 重复释放 abort（2026-11-08 真机崩溃）。 */
    private static final Object START_STOP_LOCK = new Object();

    private volatile boolean stopping;
    private ParcelFileDescriptor tun;
    private ServerSocket server;
    private Thread serverThread;
    private Thread nativeThread;

    public static boolean isRunning() {
        return running;
    }

    public static int socksPort() {
        return socksPort;
    }

    public static String targetPackage() {
        return targetPackage;
    }

    public static void stop(Context ctx) {
        try {
            ctx.startService(new Intent(ctx, ProtoCaptureService.class).setAction(ACTION_STOP));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopCapture();
            return START_NOT_STICKY;
        }
        String target = intent == null ? null : intent.getStringExtra(EXTRA_TARGET_PACKAGE);
        startCapture(target);
        return START_NOT_STICKY;
    }

    private void startCapture(String target) {
        synchronized (START_STOP_LOCK) {
            if (running) return;
            stopping = false;
            try {
                startForegroundNotice();
                // 实时落盘：抓包进程崩溃（真机 libtun2socks OOM/SIGABRT）时内存记录会丢，
                // 文件里保留崩溃前已抓到的协议，便于事后分析。
                ProtoCaptureLog.setLiveFile(new File(getFilesDir(), "proto-capture-live.txt"));
                targetPackage = resolveTarget(target);
                server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
                socksPort = server.getLocalPort();
                serverThread = new Thread(this::acceptLoop, "proto-capture-socks");
                serverThread.setDaemon(true);
                serverThread.start();

                Builder b = new Builder();
                b.setSession("号库真机抓包");
                b.setMtu(1500);
                b.addAddress("10.0.0.1", 24);
                // 分裂模式（rabbit 默认）：只把游戏服务器所在网段导入 VPN；DNS/其它流量走真实网络，
                // 避免游戏检测到全局 VPN 而报“无法使用网络”。
                b.addRoute("43.0.0.0", 8);
                b.addRoute("101.0.0.0", 8);
                if (!targetPackage.isEmpty()) {
                    try {
                        b.addAllowedApplication(targetPackage);
                    } catch (Throwable t) {
                        // 目标包未安装/受限：退化为全部应用（不排除自身，见下）
                    }
                }
                // 目标为空=全部应用：不能 addDisallowedApplication(自身)。
                // 容器内游戏（com.gof.china）与号库宿主同 UID，排除号库会把游戏流量一起漏掉。
                tun = b.establish();
                if (tun == null) {
                    stopCapture();
                    return;
                }
                final int fd = tun.getFd();
                final int port = socksPort;
                running = true;
                nativeThread = new Thread(() -> {
                    try {
                        Tun2SocksJni.runTun2Socks(fd, 1500, "10.0.0.1", "255.255.255.0",
                                null, "127.0.0.1:" + port, null, null, 0, 0);
                    } catch (Throwable ignored) {
                    } finally {
                        if (!stopping) stopCapture();
                    }
                }, "proto-capture-tun2socks");
                nativeThread.setDaemon(true);
                nativeThread.start();
            } catch (Throwable t) {
                stopCapture();
            }
        }
    }

    /**
     * 实际抓取目标：容器内游戏包 UID == 号库自身 UID 时改抓宿主包名（VPN 按 UID 覆盖容器游戏）。
     * 空值=全部应用；优先检测已安装的游戏包（容器内同样返回宿主包名）。
     */
    private String resolveTarget(String requested) {
        String t = requested != null ? requested.trim() : "";
        if (!t.isEmpty()) {
            long uid = packageUid(t);
            return ProtoCaptureTargets.resolve(t, android.os.Process.myUid(), uid, getPackageName());
        }
        PackageManager pm = getPackageManager();
        for (String p : ProtoCaptureTargets.GAME_PACKAGES) {
            try {
                pm.getPackageInfo(p, 0);
                long uid = packageUid(p);
                if (uid > 0L && uid == android.os.Process.myUid()) return getPackageName();
                return p;
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    /** 包名对应 UID；查不到返回 0（不改写目标）。 */
    private long packageUid(String pkg) {
        try {
            return getPackageManager().getApplicationInfo(pkg, 0).uid;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private void acceptLoop() {
        while (!stopping && server != null && !server.isClosed()) {
            try {
                final Socket client = server.accept();
                if (activeConns.get() >= 8) {
                    try {
                        client.close();
                    } catch (Throwable ignored) {
                    }
                    continue;
                }
                activeConns.incrementAndGet();
                Thread t = new Thread(() -> {
                    try {
                        handle(client);
                    } finally {
                        activeConns.decrementAndGet();
                    }
                }, "proto-capture-conn");
                t.setDaemon(true);
                t.start();
            } catch (Throwable t) {
                if (!stopping) {
                    try { Thread.sleep(100L); } catch (InterruptedException ignored) { return; }
                }
            }
        }
    }

    /** 把 socket 绑到物理网络（非 VPN），用于 protect 失败时绕过 VPN 防回环。 */
    private boolean bindToPhysicalNetwork(Socket s) {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c == null) continue;
                boolean physical = c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                if (!physical || c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                try {
                    n.bindSocket(s);
                    return true;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void handle(Socket client) {
        Socket upstream = null;
        try {
            client.setSoTimeout(10_000);
            InputStream ci = client.getInputStream();
            OutputStream co = client.getOutputStream();
            byte[] head = new byte[2];
            if (!readFully(ci, head, 2)) return;
            int n = head[1] & 0xFF;
            byte[] methods = new byte[n];
            if (!readFully(ci, methods, n)) return;
            byte[] greet = new byte[2 + n];
            greet[0] = head[0];
            greet[1] = head[1];
            System.arraycopy(methods, 0, greet, 2, n);
            if (!Socks5Protocol.acceptsNoAuth(greet, greet.length)) {
                co.write(Socks5Protocol.noAcceptableReply());
                co.flush();
                return;
            }
            co.write(Socks5Protocol.greetingReply());
            co.flush();

            byte[] req4 = new byte[4];
            if (!readFully(ci, req4, 4)) return;
            int atyp = req4[3] & 0xFF;
            byte[] rest;
            if (atyp == Socks5Protocol.ATYP_IPV4) {
                rest = new byte[4 + 2];
                if (!readFully(ci, rest, rest.length)) return;
            } else if (atyp == Socks5Protocol.ATYP_IPV6) {
                rest = new byte[16 + 2];
                if (!readFully(ci, rest, rest.length)) return;
            } else if (atyp == Socks5Protocol.ATYP_DOMAIN) {
                byte[] ln = new byte[1];
                if (!readFully(ci, ln, 1)) return;
                rest = new byte[(ln[0] & 0xFF) + 2];
                if (!readFully(ci, rest, rest.length)) return;
            } else {
                co.write(Socks5Protocol.failReply(Socks5Protocol.REP_ATYP_UNSUPPORTED));
                co.flush();
                return;
            }
            byte[] req = new byte[4 + rest.length];
            System.arraycopy(req4, 0, req, 0, 4);
            System.arraycopy(rest, 0, req, 4, rest.length);
            Socks5Protocol.Request r;
            try {
                r = Socks5Protocol.parseConnect(req, req.length);
            } catch (IllegalArgumentException e) {
                co.write(Socks5Protocol.failReply(e.getMessage() != null
                        && e.getMessage().startsWith("cmd_")
                        ? Socks5Protocol.REP_CMD_UNSUPPORTED : Socks5Protocol.REP_FAIL));
                co.flush();
                return;
            }
            if (r == null) return;
            upstream = new Socket();
            // 上游 socket 必须 protect 绕过 VPN：否则 43/101 网段的上游连接会被自己的 VPN
            // 再次捕获 → SOCKS 回环（真机表现：loginauth 每 5ms 重发、S2C=0、进程 OOM 崩溃）。
            // protect 失败时直接拒绝该连接，绝不能继续 connect，否则回环风暴。
            boolean protectedOk;
            try {
                protectedOk = protect(upstream);
            } catch (Throwable t) {
                protectedOk = false;
            }
            // 部分 ROM（vivo 等）上 VpnService.protect(Socket) 会返回 false；退回用
            // ConnectivityManager 把 socket 绑到物理网络（WiFi/蜂窝），同样绕过 VPN。
            if (!protectedOk) {
                protectedOk = bindToPhysicalNetwork(upstream);
            }
            if (!protectedOk) {
                try {
                    upstream.close();
                } catch (Throwable ignored) {
                }
                co.write(Socks5Protocol.failReply(Socks5Protocol.REP_FAIL));
                co.flush();
                android.util.Log.w("ProtoCapture", "protect/bind failed, reject to avoid SOCKS loop");
                return;
            }
            upstream.connect(new InetSocketAddress(r.host, r.port), 10_000);
            upstream.setSoTimeout(0);
            client.setSoTimeout(0);
            co.write(Socks5Protocol.successReply());
            co.flush();
            final boolean capture = GAME_PORTS.contains(r.port);
            final CaptureCodec codec;
            try {
                codec = capture ? CaptureCodec.get(this) : null;
            } catch (Throwable t) {
                // schema 加载失败：仍转发，只记录原始 hex
                startPump(ci, upstream.getOutputStream(), true, r.port, null);
                startPump(upstream.getInputStream(), co, false, r.port, null);
                return;
            }
            Socket up = upstream;
            startPump(ci, up.getOutputStream(), true, r.port, codec);
            startPump(up.getInputStream(), co, false, r.port, codec);
        } catch (Throwable t) {
            try {
                if (upstream != null) upstream.close();
            } catch (Throwable ignored) {
            }
            try {
                client.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 单方向 relay；捕获时按 2 字节长度前缀切帧并解码记录。 */
    private void startPump(InputStream in, OutputStream out, boolean c2s, int port,
                           CaptureCodec codec) {
        Thread t = new Thread(() -> {
            ProtoFrameAccumulator acc = new ProtoFrameAccumulator();
            byte[] buf = new byte[16 * 1024];
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                    if (codec == null) continue;
                    for (byte[] frame : acc.feed(buf, 0, n)) {
                        record(c2s, codec, frame);
                    }
                }
            } catch (Throwable ignored) {
            }
        }, "proto-capture-pump");
        t.setDaemon(true);
        t.start();
    }

    private void record(boolean c2s, CaptureCodec codec, byte[] frame) {
        try {
            if (frame.length > 512 * 1024) {
                // 超大帧（联盟成员列表等）不深解码，避免递归/大数组把堆压爆；保留原始头。
                ProtoCaptureLog.add(new ProtoCaptureRecord(System.currentTimeMillis(),
                        c2s ? "C2S" : "S2C", "large_frame", "大帧(>" + (512 * 1024) + "B，未解码)",
                        "", frame.length, ProtoCaptureLog.hex(frame, 64)));
                return;
            }
            GameMessage m = codec.decode(c2s, frame);
            String proto = m == null ? "" : m.getProto();
            Object content = m == null ? null : m.getContent();
            String json = content == null ? "" : ProtoCaptureLog.toReadable(content);
            ProtoCaptureLog.add(new ProtoCaptureRecord(System.currentTimeMillis(),
                    c2s ? "C2S" : "S2C", proto,
                    ProtoCaptureRecord.clip(json, 200), json, frame.length, ""));
        } catch (Throwable t) {
            ProtoCaptureLog.add(new ProtoCaptureRecord(System.currentTimeMillis(),
                    c2s ? "C2S" : "S2C", "decode_error", "解码失败（原始前64字节）",
                    "", frame.length, ProtoCaptureLog.hex(frame, 64)));
        }
    }

    private static boolean readFully(InputStream in, byte[] buf, int len) {
        int off = 0;
        while (off < len) {
            int n;
            try {
                n = in.read(buf, off, len - off);
            } catch (Throwable t) {
                return false;
            }
            if (n <= 0) return false;
            off += n;
        }
        return true;
    }

    private void startForegroundNotice() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                    nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                            "真机抓包", NotificationManager.IMPORTANCE_LOW));
                }
            }
            Intent open = new Intent(this, ProtoCaptureActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                    Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
            nb.setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("真机抓包运行中")
                    .setContentText("目标：" + (targetPackage.isEmpty() ? "全部应用" : targetPackage))
                    .setContentIntent(pi)
                    .setOngoing(true);
            startForeground(NOTIFY_ID, nb.build());
        } catch (Throwable ignored) {
        }
    }

    private void stopCapture() {
        synchronized (START_STOP_LOCK) {
            if (stopping) return;
            stopping = true;
            running = false;
            try {
                Tun2SocksJni.terminateTun2Socks();
            } catch (Throwable ignored) {
            }
            try {
                if (server != null) server.close();
            } catch (Throwable ignored) {
            }
            try {
                if (tun != null) tun.close();
            } catch (Throwable ignored) {
            }
            server = null;
            tun = null;
            try {
                stopForeground(true);
            } catch (Throwable ignored) {
            }
            stopSelf();
        }
    }

    @Override
    public void onRevoke() {
        stopCapture();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopCapture();
        super.onDestroy();
    }
}
