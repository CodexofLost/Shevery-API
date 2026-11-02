package com.hamondev.shevery.api;

import android.os.RemoteException;

/**
 * System properties access through the native Shevery Privilege API.
 * <p>
 * Includes empty-string and malformed-number fallback handling adapted from
 * Nightzuku API ({@code NightSystemProperties.kt}).
 */
public final class SheverySystemProperties {

    private SheverySystemProperties() {
    }

    public static String get(String key) throws RemoteException {
        return SheveryPrivilege.requireService().getSystemProperty(key, null);
    }

    public static String get(String key, String def) throws RemoteException {
        return SheveryPrivilege.requireService().getSystemProperty(key, def);
    }

    public static int getInt(String key, int def) throws RemoteException {
        String raw = SheveryPrivilege.requireService().getSystemProperty(key, Integer.toString(def));
        if (raw == null || raw.isEmpty()) {
            return def;
        }
        try {
            return Integer.decode(raw);
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    public static long getLong(String key, long def) throws RemoteException {
        String raw = SheveryPrivilege.requireService().getSystemProperty(key, Long.toString(def));
        if (raw == null || raw.isEmpty()) {
            return def;
        }
        try {
            return Long.decode(raw);
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    public static boolean getBoolean(String key, boolean def) throws RemoteException {
        String raw = SheveryPrivilege.requireService().getSystemProperty(key, Boolean.toString(def));
        if (raw == null || raw.isEmpty()) {
            return def;
        }
        return Boolean.parseBoolean(raw);
    }

    public static void set(String key, String val) throws RemoteException {
        SheveryPrivilege.requireService().setSystemProperty(key, val);
    }
}
