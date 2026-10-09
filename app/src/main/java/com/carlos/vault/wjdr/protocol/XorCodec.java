package com.carlos.vault.wjdr.protocol;

public final class XorCodec {
    private XorCodec() {
    }

    public static byte[] apply(byte[] input, byte[] key) {
        if (input == null || key == null || key.length == 0) {
            return input;
        }
        byte[] output = new byte[input.length];
        for (int i = 0; i < input.length; ++i) {
            output[i] = (byte)(input[i] ^ key[i % key.length]);
        }
        return output;
    }
}

