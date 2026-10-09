package com.carlos.vaultserver;

import com.carlos.vault.wjdr.AssetSource;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** classpath 资源源：.spb schema 由构建打进 jar（build.gradle processResources）。 */
public final class ClasspathAssets implements AssetSource {

    @Override
    public byte[] read(String name) throws Exception {
        InputStream in = getClass().getClassLoader().getResourceAsStream(name);
        if (in == null) {
            throw new IllegalStateException("classpath 缺少资源 " + name + "（检查 .spb 是否随 jar 打包）");
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }
}
