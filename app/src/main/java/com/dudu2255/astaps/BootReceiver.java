package com.dudu2255.astaps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action);
        if (boot && (Prefs.autostart(context) || Prefs.serverWanted(context))) {
            ServerService.start(context);
        }
    }
}
