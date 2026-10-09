package com.carlos.vault.daily;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机/更新后拉起日常自动执行服务（vivo 需在设置里给号库开自启动权限）。 */
public class DailyBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : String.valueOf(intent.getAction());
        if (action.equals(Intent.ACTION_BOOT_COMPLETED)
                || action.equals(Intent.ACTION_MY_PACKAGE_REPLACED)) {
            DailySchedulerService.ensureStarted(context);
        }
    }
}
