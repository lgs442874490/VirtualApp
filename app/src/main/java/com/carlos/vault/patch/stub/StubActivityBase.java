package com.carlos.vault.patch.stub;

import android.app.Activity;
import android.os.Bundle;

/**
 * 占坑 Activity 基类。
 *
 * <p>用途：DexPatch 无法在 Manifest 新增组件，因此提前预埋空壳 Activity。
 * 将来需要「新增界面」时，补丁 dex 提供同名 {@code StubActivityN} 的完整实现，
 * 系统按类名实例化时即会命中补丁版本，无需发版。
 *
 * <p>空壳自身不做任何事：直接 finish，避免误启动出现空白页。
 * 全部 {@code exported=false} 且无 intent-filter，外部无法调起。
 */
public abstract class StubActivityBase extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
    }
}
