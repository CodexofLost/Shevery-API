package com.hamondev.shevery.api;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Diagnostic and environment helper utilities for the Shevery Privilege API,
 * providing ergonomic status checks, manager navigation, and server inspection.
 */
public final class SheveryHelper {

    public static final String SHEVERY_MANAGER_PACKAGE = SheveryApiConstants.MANAGER_APPLICATION_ID;
    public static final String SHIZUKU_MANAGER_PACKAGE = SheveryApiConstants.SHIZUKU_STUB_APPLICATION_ID;

    private SheveryHelper() {
    }

    /**
     * Checks if the native Shevery Manager application is installed.
     */
    public static boolean isSheveryManagerInstalled(@NonNull Context context) {
        return isPackageInstalled(context, SHEVERY_MANAGER_PACKAGE);
    }

    /**
     * Checks if the legacy Shizuku Manager (or forwarder stub) application is installed.
     */
    public static boolean isShizukuManagerInstalled(@NonNull Context context) {
        return isPackageInstalled(context, SHIZUKU_MANAGER_PACKAGE);
    }

    /**
     * Checks if either the Shevery Manager or the Shizuku Manager/stub is installed.
     *
     * @param context Application context
     * @return {@code true} if a supported manager is present on device
     */
    public static boolean isManagerInstalled(@NonNull Context context) {
        return isSheveryManagerInstalled(context) || isShizukuManagerInstalled(context);
    }

    /**
     * Returns the package name of the active manager found installed on the device,
     * prioritizing Shevery Manager, then Shizuku Manager/stub.
     *
     * @param context Application context
     * @return package name of available manager, or {@code null} if neither is installed
     */
    @Nullable
    public static String getAvailableManagerPackageName(@NonNull Context context) {
        if (isSheveryManagerInstalled(context)) return SHEVERY_MANAGER_PACKAGE;
        if (isShizukuManagerInstalled(context)) return SHIZUKU_MANAGER_PACKAGE;
        return null;
    }

    /**
     * Launches the available privileged manager application (Shevery or Shizuku).
     *
     * @param context Context used to start activity
     * @return {@code true} if a launch intent was found and started successfully
     */
    public static boolean openManager(@NonNull Context context) {
        PackageManager pm = context.getPackageManager();
        String[] candidatePackages = new String[]{
                SHEVERY_MANAGER_PACKAGE,
                SHIZUKU_MANAGER_PACKAGE
        };

        for (String pkg : candidatePackages) {
            try {
                Intent intent = pm.getLaunchIntentForPackage(pkg);
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static boolean isPackageInstalled(@NonNull Context context, @NonNull String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Retrieves runtime information regarding the connected server daemon service.
     *
     * @return {@link ServiceInfo} if connected, or {@code null} if the binder is not alive
     */
    @Nullable
    public static ServiceInfo getServiceInfo() {
        if (!SheveryPrivilege.pingBinder()) {
            return null;
        }
        try {
            return new ServiceInfo(
                    SheveryPrivilege.getUid(),
                    SheveryPrivilege.getVersion(),
                    SheveryPrivilege.getServerPatchVersion(),
                    SheveryPrivilege.getSELinuxContext(),
                    SheveryPrivilege.getCapabilities()
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Encapsulates connection and environment metadata for the active server daemon.
     */
    public static final class ServiceInfo {

        public final int uid;
        public final int version;
        public final int patchVersion;
        @Nullable
        public final String seLinuxContext;
        public final int capabilities;

        public ServiceInfo(int uid, int version, int patchVersion, @Nullable String seLinuxContext, int capabilities) {
            this.uid = uid;
            this.version = version;
            this.patchVersion = patchVersion;
            this.seLinuxContext = seLinuxContext;
            this.capabilities = capabilities;
        }

        public boolean isRoot() {
            return uid == 0;
        }

        public boolean isAdb() {
            return uid == 2000;
        }

        public boolean hasCapability(int capabilityMask) {
            return (capabilities & capabilityMask) == capabilityMask;
        }

        @NonNull
        @Override
        public String toString() {
            return "ServiceInfo{" +
                    "uid=" + uid +
                    ", version=" + version +
                    ", patchVersion=" + patchVersion +
                    ", seLinuxContext='" + seLinuxContext + '\'' +
                    ", capabilities=0x" + Integer.toHexString(capabilities) +
                    ", isRoot=" + isRoot() +
                    ", isAdb=" + isAdb() +
                    '}';
        }
    }
}
