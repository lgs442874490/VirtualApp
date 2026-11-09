package com.carlos.vault.patch;

import android.content.Context;

import com.carlos.common.App;

/**
 * 宿主 Application 的补丁壳：继承 aar 内的 {@link App}，在 {@code super.attachBaseContext}
 * <b>之前</b>插入补丁 dex。
 *
 * <p>为什么必须在 super 之前：{@code App.attachBaseContext} 会调用
 * {@code VirtualCore.get().startup(...)} 启动 VirtualApp，期间会加载大量类；
 * 若补丁在其后才插入，已被加载的类不会再从补丁 dex 解析，补丁即失效。
 *
 * <p>无补丁时行为与原始 {@code com.carlos.common.App} 完全一致（所有路径安全返回）。
 */
public class PatchApp extends App {

    @Override
    protected void attachBaseContext(Context base) {
        PatchLoader.applyAtStartup(base);
        super.attachBaseContext(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // 启动后异步检查补丁（不阻塞启动；下载/校验失败静默降级）
        try {
            new Thread(() -> PatchManager.sync(this), "dexpatch-sync").start();
        } catch (Throwable ignored) {
        }
    }
}
