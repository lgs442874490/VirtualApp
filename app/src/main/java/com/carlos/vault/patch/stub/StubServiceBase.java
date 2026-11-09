package com.carlos.vault.patch.stub;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * 占坑 Service 基类（同 {@link StubActivityBase} 的预埋思路）。
 *
 * <p>将来热更新增后台能力时，补丁 dex 提供同名 {@code StubServiceN} 的实现即可。
 * 空壳不绑定任何 Binder，未导出。
 */
public abstract class StubServiceBase extends Service {

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
