package rikka.shizuku.server;

import android.app.ActivityThread;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextHidden;
import android.ddm.DdmHandleAppName;
import android.os.Build;
import android.os.IBinder;
import android.os.UserHandle;
import android.os.UserHandleHidden;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import dev.rikka.tools.refine.Refine;

/**
 * Bootstrapper for privileged UserService processes running under {@code app_process}.
 * <p>
 * Incorporates {@code ActivityThread$AppBindData} initialization from Nightzuku API
 * ({@code kerneldroid/nightzuku-api}) so {@code Application.onCreate()} has valid
 * bound application metadata on modern Android releases, while preserving the Context
 * fallback for OEM ROM quirks.
 */
public class UserService {

    private static String TAG = "UserService";

    public static void setTag(String tag) {
        UserService.TAG = tag;
    }

    @Nullable
    public static Pair<IBinder, String> create(String[] args) {
        String name = null;
        String token = null;
        String pkg = null;
        String cls = null;
        int uid = -1;

        for (String arg : args) {
            if (arg.startsWith("--debug-name=")) {
                name = arg.substring(13);
            } else if (arg.startsWith("--token=")) {
                token = arg.substring(8);
            } else if (arg.startsWith("--package=")) {
                pkg = arg.substring(10);
            } else if (arg.startsWith("--class=")) {
                cls = arg.substring(8);
            } else if (arg.startsWith("--uid=")) {
                try {
                    uid = Integer.parseInt(arg.substring(6));
                } catch (NumberFormatException ignored) {
                    uid = -1;
                }
            }
        }

        if (pkg == null || cls == null || token == null) {
            return null;
        }

        int userId = uid / 100000;

        Log.i(TAG, String.format("starting service %s/%s...", pkg, cls));

        IBinder service;

        try {
            ActivityThread activityThread = ActivityThread.systemMain();
            Context systemContext = activityThread.getSystemContext();

            String processName = name != null ? name : pkg + ":user_service";
            DdmHandleAppName.setAppName(processName, userId);

            //noinspection InstantiationOfUtilityClass
            UserHandle userHandle = Refine.unsafeCast(
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                            ? UserHandleHidden.of(userId)
                            : new UserHandleHidden(userId));
            Context context = Refine.<ContextHidden>unsafeCast(systemContext).createPackageContextAsUser(
                    pkg, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY, userHandle);

            Application application = null;
            try {
                Field mPackageInfo = context.getClass().getDeclaredField("mPackageInfo");
                mPackageInfo.setAccessible(true);
                Object loadedApk = mPackageInfo.get(context);

                // Populate ActivityThread$AppBindData before makeApplication (from Nightzuku API)
                try {
                    Class<?> appBindDataClass = Class.forName("android.app.ActivityThread$AppBindData");
                    Constructor<?> appBindDataCtor = appBindDataClass.getDeclaredConstructor();
                    appBindDataCtor.setAccessible(true);
                    Object appBindData = appBindDataCtor.newInstance();

                    setAppBindDataField(appBindDataClass, appBindData, "processName", processName);

                    Object appInfo = null;
                    try {
                        appInfo = loadedApk.getClass().getMethod("getApplicationInfo").invoke(loadedApk);
                    } catch (Throwable ignored) {
                        try {
                            Field mAppInfo = loadedApk.getClass().getDeclaredField("mApplicationInfo");
                            mAppInfo.setAccessible(true);
                            appInfo = mAppInfo.get(loadedApk);
                        } catch (Throwable ignored2) {
                        }
                    }
                    setAppBindDataField(appBindDataClass, appBindData, "appInfo", appInfo);

                    try {
                        Object compatInfo = Class.forName("android.content.res.CompatibilityInfo")
                                .getField("DEFAULT_COMPATIBILITY_INFO")
                                .get(null);
                        setAppBindDataField(appBindDataClass, appBindData, "compatInfo", compatInfo);
                    } catch (Throwable ignored) {
                    }

                    Field mBoundApplication = activityThread.getClass().getDeclaredField("mBoundApplication");
                    mBoundApplication.setAccessible(true);
                    mBoundApplication.set(activityThread, appBindData);
                } catch (Throwable ignored) {
                }

                Method makeApplication = loadedApk.getClass().getDeclaredMethod(
                        "makeApplication", boolean.class, Instrumentation.class);
                application = (Application) makeApplication.invoke(loadedApk, true, null);
                Field mInitialApplication = activityThread.getClass().getDeclaredField("mInitialApplication");
                mInitialApplication.setAccessible(true);
                mInitialApplication.set(activityThread, application);
            } catch (Throwable e) {
                Log.w(TAG, "Failed to initialize Application, using Context as fallback", e);
                application = null;
            }

            ClassLoader classLoader = (application != null ? application.getClassLoader() : context.getClassLoader());
            Class<?> serviceClass = classLoader.loadClass(cls);
            Constructor<?> constructorWithContext = null;
            try {
                constructorWithContext = serviceClass.getConstructor(Context.class);
            } catch (NoSuchMethodException | SecurityException ignored) {
            }
            if (constructorWithContext != null) {
                service = (IBinder) constructorWithContext.newInstance(application != null ? application : context);
            } else {
                Constructor<?> defaultCtor = serviceClass.getDeclaredConstructor();
                defaultCtor.setAccessible(true);
                service = (IBinder) defaultCtor.newInstance();
            }
        } catch (Throwable tr) {
            Log.w(TAG, String.format("unable to start service %s/%s...", pkg, cls), tr);
            return null;
        }

        return new Pair<>(service, token);
    }

    private static void setAppBindDataField(
            Class<?> appBindDataClass, Object appBindData, String fieldName, @Nullable Object value) {
        if (value == null) return;
        try {
            Field field = appBindDataClass.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(appBindData, value);
        } catch (Throwable ignored) {
        }
    }
}
