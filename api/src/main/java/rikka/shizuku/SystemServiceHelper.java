package rikka.shizuku;

import android.annotation.SuppressLint;
import android.os.IBinder;
import android.os.Parcel;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe system service binder and transaction code lookup helper.
 * <p>
 * Incorporates {@link ConcurrentHashMap} caching, {@link IBinder#pingBinder()} liveness
 * validation, and {@link #clearCache()} from Nightzuku API ({@code SystemServiceHelper.kt}).
 */
@SuppressLint("PrivateApi")
public class SystemServiceHelper {

    private static final String TAG = "SystemServiceHelper";
    private static final Map<String, IBinder> SYSTEM_SERVICE_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Integer> TRANSACT_CODE_CACHE = new ConcurrentHashMap<>();

    private static Method getService;

    static {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            getService = sm.getMethod("getService", String.class);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            Log.w(TAG, Log.getStackTraceString(e));
        }
    }

    /**
     * Returns a reference to a service with the given name, verifying that any cached
     * binder is still alive via {@link IBinder#pingBinder()}.
     *
     * @param name the name of the service to get such as "package" for android.content.pm.IPackageManager
     * @return a reference to the service, or {@code null} if the service doesn't exist
     */
    @Nullable
    public static IBinder getSystemService(@NonNull String name) {
        IBinder binder = SYSTEM_SERVICE_CACHE.get(name);
        if (binder == null || !binder.pingBinder()) {
            try {
                binder = getService != null ? (IBinder) getService.invoke(null, name) : null;
            } catch (IllegalAccessException | InvocationTargetException e) {
                Log.w(TAG, Log.getStackTraceString(e));
            }
            if (binder != null) {
                SYSTEM_SERVICE_CACHE.put(name, binder);
            } else {
                SYSTEM_SERVICE_CACHE.remove(name);
            }
        }
        return binder;
    }

    /**
     * Clears cached system service binders.
     */
    public static void clearCache() {
        SYSTEM_SERVICE_CACHE.clear();
    }

    /**
     * Returns transaction code from given class and method name.
     *
     * @param className  class name such as "android.content.pm.IPackageManager$Stub"
     * @param methodName method name such as "getInstalledPackages"
     * @return transaction code, or {@code null} if the class or the method doesn't exist
     * @deprecated Use {@link ShizukuBinderWrapper} instead
     */
    @Deprecated
    @Nullable
    public static Integer getTransactionCode(@NonNull String className, @NonNull String methodName) {
        final String fieldName = "TRANSACTION_" + methodName;
        final String key = className + "." + fieldName;

        Integer value = TRANSACT_CODE_CACHE.get(key);
        if (value != null) return value;

        try {
            final Class<?> cls = Class.forName(className);
            Field declaredField = null;
            try {
                declaredField = cls.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                for (Field f : cls.getDeclaredFields()) {
                    if (f.getType() != int.class)
                        continue;

                    String name = f.getName();
                    if (name.startsWith(fieldName + "_")
                            && TextUtils.isDigitsOnly(name.substring(fieldName.length() + 1))) {
                        declaredField = f;
                        break;
                    }
                }
            }
            if (declaredField == null) {
                return null;
            }

            declaredField.setAccessible(true);
            value = declaredField.getInt(cls);

            TRANSACT_CODE_CACHE.put(key, value);
            return value;
        } catch (ClassNotFoundException | IllegalAccessException e) {
            Log.w(TAG, Log.getStackTraceString(e));
        }
        return null;
    }

    @Deprecated
    public static Parcel obtainParcel(@NonNull String serviceName, @NonNull String interfaceName, @NonNull String methodName) {
        return obtainParcel(serviceName, interfaceName, interfaceName + "$Stub", methodName);
    }

    @Deprecated
    public static Parcel obtainParcel(@NonNull String serviceName, @NonNull String interfaceName, @NonNull String className, @NonNull String methodName) {
        throw new UnsupportedOperationException("Direct use of Shizuku#transactRemote is no longer supported, please use ShizukuBinderWrapper");
    }
}
