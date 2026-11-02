package com.hamondev.shevery.api;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Diagnostic and environment helper utilities for the Shevery Privilege API,
 * providing ergonomic status checks and manager navigation (modeled after Stellar-API).
 */
public final class SheveryHelper {

    private SheveryHelper() {
    }

    /**
     * Checks if either the Shevery Manager or the legacy Shizuku Stub/Manager is installed.
     *
     * @param context Application context
     * @return {@code true} if manager is present on device
     */
    public static boolean isManagerInstalled(@NonNull Context context) {
        PackageManager pm = context.getPackageManager();
        try {
            pm.getPackageInfo(SheveryApiConstants.MANAGER_APPLICATION_ID, 0);
            return true;
        } catch (Throwable ignored) {
            try {
                pm.getPackageInfo(SheveryApiConstants.SHIZUKU_STUB_APPLICATION_ID, 0);
                return true;
            } catch (Throwable ignored2) {
                return false;
            }
        }
    }

    /**
     * Launches the Shevery Manager app (or fallback Shizuku stub) if installed.
     *
     * @param context Context used to start activity
     * @return {@code true} if launch intent was found and started
     */
    public static boolean openManager(@NonNull Context context) {
        PackageManager pm = context.getPackageManager();
        try {
            Intent intent = pm.getLaunchIntentForPackage(SheveryApiConstants.MANAGER_APPLICATION_ID);
            if (intent == null) {
                intent = pm.getLaunchIntentForPackage(SheveryApiConstants.SHIZUKU_STUB_APPLICATION_ID);
            }
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Retrieves runtime information regarding the connected Shevery daemon service.
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
                    SheveryPrivilege.getSELinuxContext()
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

        public ServiceInfo(int uid, int version, int patchVersion, @Nullable String seLinuxContext) {
            this.uid = uid;
            this.version = version;
            this.patchVersion = patchVersion;
            this.seLinuxContext = seLinuxContext;
        }

        public boolean isRoot() {
            return uid == 0;
        }

        public boolean isAdb() {
            return uid == 2000;
        }

        @NonNull
        @Override
        public String toString() {
            return "ServiceInfo{" +
                    "uid=" + uid +
                    ", version=" + version +
                    ", patchVersion=" + patchVersion +
                    ", seLinuxContext='" + seLinuxContext + '\'' +
                    ", isRoot=" + isRoot() +
                    ", isAdb=" + isAdb() +
                    '}';
        }
    }
}
