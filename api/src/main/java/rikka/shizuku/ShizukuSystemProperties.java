package rikka.shizuku;

import android.os.RemoteException;

/**
 * @since added from version 9
 */
public class ShizukuSystemProperties {

    public static String get(String key) throws RemoteException {
        return Shizuku.requireService().getSystemProperty(key, null);
    }

    public static String get(String key, String def) throws RemoteException {
        return Shizuku.requireService().getSystemProperty(key, def);
    }

    public static int getInt(String key, int def) throws RemoteException {
        String raw = Shizuku.requireService().getSystemProperty(key, Integer.toString(def));
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
        String raw = Shizuku.requireService().getSystemProperty(key, Long.toString(def));
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
        String raw = Shizuku.requireService().getSystemProperty(key, Boolean.toString(def));
        if (raw == null || raw.isEmpty()) {
            return def;
        }
        return Boolean.parseBoolean(raw);
    }

    public static void set(String key, String val) throws RemoteException {
        Shizuku.requireService().setSystemProperty(key, val);
    }
}
