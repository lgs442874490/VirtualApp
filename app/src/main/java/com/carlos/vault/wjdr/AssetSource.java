package com.carlos.vault.wjdr;

/**
 * 协议资源读取源（平台中立）：App 侧由 assets 提供，服务器侧由 classpath/磁盘提供。
 * WjdrEngine 只依赖此接口，不再持有 Android Context。
 */
public interface AssetSource {
    /** 按资源名读出全部字节（如 "wjdr/game_proto_c2s.spb"）。 */
    byte[] read(String name) throws Exception;
}
