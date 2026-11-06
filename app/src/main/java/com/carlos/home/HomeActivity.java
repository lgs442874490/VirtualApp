package com.carlos.home;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * 兼容壳（2026-11-06 决策4）：旧版 VA 主页已删除，本类仅为引擎 AAR 的
 * {@code com.carlos.common.ui.activity.BackHomeActivity}（StringFog 反射
 * {@code Class.forName("com.carlos.home.HomeActivity")} 做「返回号库主页」
 * moveTaskToFront）保留反射目标，防止该功能 ClassNotFound 静默失效。
 *
 * <p>被拉起时立即转跳新主页（vault.HomeActivity）并 finish 自身。
 * 不要在本类添加任何业务逻辑；旧克隆/启动器/Xposed 管理 UI 已整体下线。
 */
public class HomeActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            startActivity(new Intent(this, com.carlos.vault.HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (Throwable ignored) {
            // 反射链兜底场景：新主页不可用时静默退出，不让壳崩溃
        }
        finish();
    }
}
