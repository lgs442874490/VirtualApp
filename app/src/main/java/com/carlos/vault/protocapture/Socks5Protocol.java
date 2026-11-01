package com.carlos.vault.protocapture;

import java.net.InetAddress;

/**
 * 本地 SOCKS5（仅 NOAUTH + CONNECT）纯协议编解码；relay 由 ProtoCaptureService 实现。
 * 支持 ATYP=1(IPv4)/3(域名)/4(IPv6)。UDP ASSOCIATE 暂不支持（返回 07）。
 */
public final class Socks5Protocol {
    public static final int VER = 5;
    public static final int CMD_CONNECT = 1;
    public static final int ATYP_IPV4 = 1;
    public static final int ATYP_DOMAIN = 3;
    public static final int ATYP_IPV6 = 4;
    public static final int REP_OK = 0;
    public static final int REP_FAIL = 1;
    public static final int REP_CMD_UNSUPPORTED = 7;
    public static final int REP_ATYP_UNSUPPORTED = 8;

    private Socks5Protocol() {
    }

    /** 客户端 greeting 是否可用（VER=5 且 METHODS 含 0x00 NOAUTH）。 */
    public static boolean acceptsNoAuth(byte[] greeting, int len) {
        if (greeting == null || len < 2 || (greeting[0] & 0xFF) != VER) return false;
        int n = greeting[1] & 0xFF;
        if (len < 2 + n) return false;
        for (int i = 0; i < n; i++) {
            if (greeting[2 + i] == 0) return true;
        }
        return false;
    }

    public static byte[] greetingReply() {
        return new byte[]{VER, 0};
    }

    public static byte[] noAcceptableReply() {
        return new byte[]{(byte) 0xFF, (byte) 0xFF};
    }

    /** 解析后的 CONNECT 目标。 */
    public static final class Request {
        public final String host;
        public final int port;
        public final int atyp;

        Request(String host, int port, int atyp) {
            this.host = host;
            this.port = port;
            this.atyp = atyp;
        }
    }

    /** 解析 CONNECT 请求；不完整返回 null；非法（VER/CMD/ATYP）抛 IllegalArgumentException 并带 rep 码。 */
    public static Request parseConnect(byte[] b, int len) {
        if (b == null || len < 4) return null;
        if ((b[0] & 0xFF) != VER) throw new IllegalArgumentException("bad ver");
        int cmd = b[1] & 0xFF;
        if (cmd != CMD_CONNECT) throw new IllegalArgumentException("cmd_" + cmd);
        int atyp = b[3] & 0xFF;
        int pos = 4;
        String host;
        if (atyp == ATYP_IPV4) {
            if (len < pos + 4 + 2) return null;
            host = (b[pos] & 0xFF) + "." + (b[pos + 1] & 0xFF) + "."
                    + (b[pos + 2] & 0xFF) + "." + (b[pos + 3] & 0xFF);
            pos += 4;
        } else if (atyp == ATYP_DOMAIN) {
            if (len < pos + 1) return null;
            int n = b[pos] & 0xFF;
            pos++;
            if (n <= 0 || n > 255 || len < pos + n + 2) return null;
            host = new String(b, pos, n, java.nio.charset.StandardCharsets.UTF_8);
            pos += n;
        } else if (atyp == ATYP_IPV6) {
            if (len < pos + 16 + 2) return null;
            byte[] ip = new byte[16];
            System.arraycopy(b, pos, ip, 0, 16);
            try {
                host = InetAddress.getByAddress(ip).getHostAddress();
            } catch (Throwable t) {
                host = "::";
            }
            pos += 16;
        } else {
            throw new IllegalArgumentException("atyp_" + atyp);
        }
        int port = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
        if (port <= 0) throw new IllegalArgumentException("port");
        return new Request(host, port, atyp);
    }

    /** 成功应答（BND.ADDR=0.0.0.0, BND.PORT=0）。 */
    public static byte[] successReply() {
        return new byte[]{VER, REP_OK, 0, ATYP_IPV4, 0, 0, 0, 0, 0, 0};
    }

    public static byte[] failReply(int rep) {
        return new byte[]{VER, (byte) rep, 0, ATYP_IPV4, 0, 0, 0, 0, 0, 0};
    }
}
