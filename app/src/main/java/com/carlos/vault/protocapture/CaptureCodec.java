package com.carlos.vault.protocapture;

import android.content.Context;
import android.content.res.AssetManager;

import com.carlos.vault.wjdr.protocol.PortableSprotoCodec;
import com.carlos.vault.wjdr.protocol.SprotoSchemaParser;
import com.carlos.vault.wjdr.protocol.GameMessage;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** 每个连接一个抓包解码器（schema 进程级缓存；解码加锁防两方向并发）。 */
final class CaptureCodec {
    private final PortableSprotoCodec codec;
    private final Object lock = new Object();

    CaptureCodec(Context ctx) throws Exception {
        AssetManager am = ctx.getAssets();
        byte[] c2s = readAll(am.open("wjdr/game_proto_c2s.spb"));
        byte[] s2c = readAll(am.open("wjdr/game_proto_s2c.spb"));
        codec = new PortableSprotoCodec(new SprotoSchemaParser());
        codec.loadSchemas(c2s, s2c);
    }

    GameMessage decode(boolean c2s, byte[] payload) {
        synchronized (lock) {
            return c2s ? codec.decodeRequestForCapture(payload) : codec.decodeResponse(payload);
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
