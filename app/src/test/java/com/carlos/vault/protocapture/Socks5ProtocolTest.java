package com.carlos.vault.protocapture;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** SOCKS5 握手/CONNECT 解析（仅 NOAUTH）。 */
public class Socks5ProtocolTest {

    @Test
    public void greeting_noAuthAccepted() {
        byte[] g = new byte[]{5, 1, 0};
        assertTrue(Socks5Protocol.acceptsNoAuth(g, g.length));
        byte[] no = new byte[]{5, 1, 2};
        assertFalse(Socks5Protocol.acceptsNoAuth(no, no.length));
        assertFalse(Socks5Protocol.acceptsNoAuth(new byte[]{4, 1, 0}, 3));
        assertFalse("methods 不完整", Socks5Protocol.acceptsNoAuth(new byte[]{5, 2, 0}, 2));
    }

    @Test
    public void parseConnect_ipv4() {
        byte[] req = new byte[]{5, 1, 0, 1, 43, (byte) 144, (byte) 130, (byte) 216,
                (byte) (30101 >> 8), (byte) (30101 & 0xFF)};
        Socks5Protocol.Request r = Socks5Protocol.parseConnect(req, req.length);
        assertEquals("43.144.130.216", r.host);
        assertEquals(30101, r.port);
        assertEquals(Socks5Protocol.ATYP_IPV4, r.atyp);
    }

    @Test
    public void parseConnect_domain() {
        byte[] host = "game.example.com".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] req = new byte[4 + 1 + host.length + 2];
        req[0] = 5;
        req[1] = 1;
        req[2] = 0;
        req[3] = 3;
        req[4] = (byte) host.length;
        System.arraycopy(host, 0, req, 5, host.length);
        req[5 + host.length] = (byte) (30101 >> 8);
        req[6 + host.length] = (byte) (30101 & 0xFF);
        Socks5Protocol.Request r = Socks5Protocol.parseConnect(req, req.length);
        assertEquals("game.example.com", r.host);
        assertEquals(30101, r.port);
    }

    @Test
    public void parseConnect_incompleteReturnsNull() {
        assertNull(Socks5Protocol.parseConnect(new byte[]{5, 1, 0, 1, 1}, 5));
        assertNull(Socks5Protocol.parseConnect(new byte[]{5, 1, 0, 3, 5, 'a'}, 6));
        assertNull(Socks5Protocol.parseConnect(null, 0));
    }

    @Test
    public void parseConnect_unsupportedCommand() {
        try {
            Socks5Protocol.parseConnect(new byte[]{5, 2, 0, 1, 1, 2, 3, 4, 0, 1}, 10);
            throw new AssertionError("应抛不支持命令");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().startsWith("cmd_"));
        }
        try {
            Socks5Protocol.parseConnect(new byte[]{5, 1, 0, 9}, 4);
            throw new AssertionError("应抛不支持 ATYP");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().startsWith("atyp_"));
        }
    }

    @Test
    public void replies() {
        assertEquals(2, Socks5Protocol.greetingReply().length);
        assertEquals(0, Socks5Protocol.greetingReply()[1]);
        byte[] ok = Socks5Protocol.successReply();
        assertEquals(10, ok.length);
        assertEquals(0, ok[1]);
        assertEquals(Socks5Protocol.REP_FAIL, Socks5Protocol.failReply(
                Socks5Protocol.REP_FAIL)[1]);
    }
}
