package com.dudu2255.astaps;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("astaps", Context.MODE_PRIVATE);
    }

    public static boolean autostart(Context c) {
        return sp(c).getBoolean("autostart", false);
    }

    public static void setAutostart(Context c, boolean v) {
        sp(c).edit().putBoolean("autostart", v).apply();
    }

    public static boolean serverWanted(Context c) {
        return sp(c).getBoolean("server_wanted", false);
    }

    public static void setServerWanted(Context c, boolean v) {
        sp(c).edit().putBoolean("server_wanted", v).apply();
    }
}
