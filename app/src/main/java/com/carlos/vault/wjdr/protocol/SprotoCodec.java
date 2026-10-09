package com.carlos.vault.wjdr.protocol;

import com.carlos.vault.wjdr.protocol.GameMessage;
import java.util.Map;

public interface SprotoCodec {
    public void loadSchemas(byte[] var1, byte[] var2);

    public byte[] encodeRequest(String var1, Map<String, Object> var2, int var3);

    public GameMessage decodeResponse(byte[] var1);

    public void releaseSession(int var1);

    public void clearSessions();

    public void updateSecretKey(byte[] var1);

    public boolean available();
}

