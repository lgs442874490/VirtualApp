package org.uproxy.tun2socks;

/** uProxy tun2socks native bridge (Apache-2.0; 来自最强兔子 libtun2socks.so). */
public final class Tun2SocksJni {
    static {
        System.loadLibrary("tun2socks");
    }

    private Tun2SocksJni() {
    }

    /**
     * @param fd       VpnService establish() 返回的 tun fd
     * @param mtu      MTU
     * @param address  tun 本地地址（如 10.0.0.1）
     * @param netmask  子网掩码
     * @param gateway  网关（可为空）
     * @param socks   本地 SOCKS5 地址（127.0.0.1:port）
     * @param udpRelay 可选 UDP relay（可为空）
     * @param dns     DNS（如 8.8.8.8:53）
     * @return 0=正常退出；非 0=错误码（native 线程阻塞运行）
     */
    public static native int runTun2Socks(int fd, int mtu, String address, String netmask,
                                          String gateway, String socks, String udpRelay,
                                          String dns, int maxSession, int tcpBuffer);

    public static native int terminateTun2Socks();

    public static void logTun2Socks(String level, String tag, String msg) {
        android.util.Log.i("Tun2Socks", String.format("%s (%s): %s", level, tag, msg));
    }
}
