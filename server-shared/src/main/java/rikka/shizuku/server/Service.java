package rikka.shizuku.server;

import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.SystemProperties;
import android.system.Os;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hamondev.shevery.api.SheveryApiConstants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuApplication;
import moe.shizuku.server.IShizukuService;
import moe.shizuku.server.IShizukuServiceConnection;
import rikka.hidden.compat.PermissionManagerApis;
import rikka.rish.RishConfig;
import rikka.rish.RishService;
import rikka.shizuku.ShizukuApiConstants;
import rikka.shizuku.server.api.RemoteProcessHolder;
import rikka.shizuku.server.util.Logger;
import rikka.shizuku.server.util.OsUtils;
import rikka.shizuku.server.util.UserHandleCompat;

public abstract class Service<
        UserServiceMgr extends UserServiceManager,
        ClientMgr extends ClientManager<ConfigMgr>,
        ConfigMgr extends ConfigManager> extends IShizukuService.Stub {

    private static final String DEFAULT_PATH =
            "/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system_ext/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin";

    private final UserServiceMgr userServiceManager;
    private final ConfigMgr configManager;
    private final ClientMgr clientManager;
    private final RishService rishService;

    protected static final Logger LOGGER = new Logger("Service");

    public Service() {
        RishConfig.init(ShizukuApiConstants.BINDER_DESCRIPTOR, 30000);

        userServiceManager = onCreateUserServiceManager();
        configManager = onCreateConfigManager();
        clientManager = onCreateClientManager();
        rishService = new RishService() {

            @Override
            public void enforceCallingPermission(String func) {
                Service.this.enforceCallingCapability(func, SheveryApiConstants.CAP_SHELL_EXEC);
            }
        };
    }

    /**
     * Confines an untrusted incoming Bundle to the framework ClassLoader so unparceling
     * on API <= 32 cannot instantiate arbitrary classes from the server's classpath.
     * Adapted from Porter API ({@code UntrustedBundles.kt}).
     */
    protected static void confineToFramework(@Nullable Bundle bundle) {
        if (bundle != null) {
            bundle.setClassLoader(Bundle.class.getClassLoader());
        }
    }

    public abstract UserServiceMgr onCreateUserServiceManager();

    public abstract ClientMgr onCreateClientManager();

    public abstract ConfigMgr onCreateConfigManager();

    public final UserServiceMgr getUserServiceManager() {
        return userServiceManager;
    }

    public final ClientMgr getClientManager() {
        return clientManager;
    }

    public ConfigMgr getConfigManager() {
        return configManager;
    }

    public boolean checkCallerManagerPermission(String func, int callingUid, int callingPid) {
        return false;
    }

    public final void enforceManagerPermission(String func) {
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();

        if (callingPid == Os.getpid()) {
            return;
        }

        if (checkCallerManagerPermission(func, callingUid, callingPid)) {
            return;
        }

        String msg = "Permission Denial: " + func + " from pid="
                + callingPid
                + " is not manager";
        LOGGER.w(msg);
        throw new SecurityException(msg);
    }

    public boolean checkCallerPermission(String func, int callingUid, int callingPid, @Nullable ClientRecord clientRecord) {
        return false;
    }

    public final void enforceCallingPermission(String func) {
        enforceCallingCapability(func, 0);
    }

    public final void enforceCallingCapability(String func, int requiredCapability) {
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();

        if (callingUid == OsUtils.getUid()) {
            return;
        }

        ClientRecord clientRecord = clientManager.findClient(callingUid, callingPid);

        if (checkCallerPermission(func, callingUid, callingPid, clientRecord)) {
            return;
        }

        if (clientRecord == null) {
            String msg = "Permission Denial: " + func + " from pid="
                    + callingPid
                    + " is not an attached client";
            LOGGER.w(msg);
            throw new SecurityException(msg);
        }

        if (!clientRecord.allowed) {
            String msg = "Permission Denial: " + func + " from pid="
                    + callingPid
                    + " requires permission";
            LOGGER.w(msg);
            throw new SecurityException(msg);
        }

        if (requiredCapability != 0 && !clientRecord.hasCapability(requiredCapability)) {
            String msg = "Permission Denial: " + func + " from pid="
                    + callingPid
                    + " lacks capability 0x" + Integer.toHexString(requiredCapability);
            LOGGER.w(msg);
            throw new SecurityException(msg);
        }
    }

    public final void transactRemote(Parcel data, Parcel reply, int flags) throws RemoteException {
        enforceCallingCapability("transactRemote", SheveryApiConstants.CAP_BINDER_PROXY);

        IBinder targetBinder = data.readStrongBinder();
        if (targetBinder == null) {
            throw new IllegalArgumentException("targetBinder must not be null");
        }
        // Prevent self-binder privilege escalation: a local Binder of this server process would run
        // with the server's own PID/UID once clearCallingIdentity() is called below, bypassing
        // enforceManagerPermission(). Adapted from Porter API (PorterCore.kt).
        if (targetBinder instanceof Binder) {
            String msg = "Permission Denial: transactRemote from pid="
                    + Binder.getCallingPid()
                    + " targets a binder of the server process";
            LOGGER.w(msg);
            throw new SecurityException(msg);
        }

        int targetCode = data.readInt();
        int targetFlags;

        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        ClientRecord clientRecord = clientManager.findClient(callingUid, callingPid);

        if (clientRecord != null && clientRecord.apiVersion >= 13) {
            targetFlags = data.readInt();
        } else {
            targetFlags = flags;
        }

        String descriptor;
        try {
            descriptor = targetBinder.getInterfaceDescriptor();
        } catch (Throwable tr) {
            descriptor = "<unavailable>";
        }
        LOGGER.d("transact: uid=%d, descriptor=%s, code=%d", callingUid, descriptor, targetCode);

        Parcel newData = Parcel.obtain();
        try {
            try {
                newData.appendFrom(data, data.dataPosition(), data.dataAvail());
            } catch (Throwable tr) {
                LOGGER.w(tr, "appendFrom");
                return;
            }
            long id = Binder.clearCallingIdentity();
            try {
                targetBinder.transact(targetCode, newData, reply, targetFlags);
            } finally {
                Binder.restoreCallingIdentity(id);
            }
        } finally {
            newData.recycle();
        }
    }

    @Override
    public final int getVersion() {
        enforceCallingPermission("getVersion");
        return ShizukuApiConstants.SERVER_VERSION;
    }

    @Override
    public final int getUid() {
        enforceCallingPermission("getUid");
        return Os.getuid();
    }

    @Override
    public final int checkPermission(String permission) throws RemoteException {
        enforceCallingPermission("checkPermission");
        if (permission == null) {
            throw new NullPointerException("permission is null");
        }
        return PermissionManagerApis.checkPermission(permission, Os.getuid());
    }

    @Override
    public final String getSELinuxContext() {
        enforceCallingPermission("getSELinuxContext");

        try {
            return SELinux.getContext();
        } catch (Throwable tr) {
            throw new IllegalStateException(tr.getMessage());
        }
    }

    @Override
    public final String getSystemProperty(String name, String defaultValue) {
        enforceCallingCapability("getSystemProperty", SheveryApiConstants.CAP_SYSTEM_PROPS);
        if (name == null) {
            throw new NullPointerException("name is null");
        }

        try {
            return SystemProperties.get(name, defaultValue);
        } catch (Throwable tr) {
            throw new IllegalStateException(tr.getMessage());
        }
    }

    @Override
    public final void setSystemProperty(String name, String value) {
        enforceCallingCapability("setSystemProperty", SheveryApiConstants.CAP_SYSTEM_PROPS);
        if (name == null) {
            throw new NullPointerException("name is null");
        }

        try {
            SystemProperties.set(name, value);
        } catch (Throwable tr) {
            throw new IllegalStateException(tr.getMessage());
        }
    }

    @Override
    public final int removeUserService(IShizukuServiceConnection conn, Bundle options) {
        enforceCallingCapability("removeUserService", SheveryApiConstants.CAP_USER_SERVICE);
        confineToFramework(options);
        if (options == null) {
            throw new NullPointerException("options is null");
        }

        return userServiceManager.removeUserService(conn, options);
    }

    @Override
    public final int addUserService(IShizukuServiceConnection conn, Bundle options) {
        enforceCallingCapability("addUserService", SheveryApiConstants.CAP_USER_SERVICE);
        confineToFramework(options);
        if (conn == null) {
            throw new NullPointerException("connection is null");
        }
        if (options == null) {
            throw new NullPointerException("options is null");
        }

        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        int callingApiVersion;

        ClientRecord clientRecord = clientManager.findClient(callingUid, callingPid);
        if (clientRecord == null) {
            callingApiVersion = ShizukuApiConstants.SERVER_VERSION;
        } else {
            callingApiVersion = clientRecord.apiVersion;
        }
        return userServiceManager.addUserService(conn, options, callingApiVersion);
    }

    @Override
    public void attachUserService(IBinder binder, Bundle options) {
        int callingUid = Binder.getCallingUid();
        if (callingUid != OsUtils.getUid() && callingUid != 0 && callingUid != 2000) {
            enforceManagerPermission("attachUserService");
        }
        confineToFramework(options);
        userServiceManager.attachUserService(binder, options);
    }

    @Override
    public final boolean checkSelfPermission() {
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();

        if (callingUid == OsUtils.getUid() || callingPid == OsUtils.getPid()) {
            return true;
        }

        return clientManager.requireClient(callingUid, callingPid).allowed;
    }

    @Override
    public final void requestPermission(int requestCode) {
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        int userId = UserHandleCompat.getUserId(callingUid);

        if (callingUid == OsUtils.getUid() || callingPid == OsUtils.getPid()) {
            return;
        }

        ClientRecord clientRecord = clientManager.requireClient(callingUid, callingPid);

        if (clientRecord.allowed) {
            clientRecord.dispatchRequestPermissionResult(requestCode, true);
            return;
        }

        ConfigPackageEntry entry = configManager.find(callingUid);
        if (entry != null && entry.isDenied()) {
            clientRecord.dispatchRequestPermissionResult(requestCode, false);
            return;
        }

        showPermissionConfirmation(requestCode, clientRecord, callingUid, callingPid, userId);
    }

    public abstract void showPermissionConfirmation(
            int requestCode, @NonNull ClientRecord clientRecord, int callingUid, int callingPid, int userId);

    @Override
    public final boolean shouldShowRequestPermissionRationale() {
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();

        if (callingUid == OsUtils.getUid() || callingPid == OsUtils.getPid()) {
            return true;
        }

        clientManager.requireClient(callingUid, callingPid);

        ConfigPackageEntry entry = configManager.find(callingUid);
        return entry != null && entry.isDenied();
    }

    @Nullable
    private String[] normalizeEnvironment(@Nullable String[] env) {
        if (env == null) {
            return null;
        }
        Map<String, String> map = new LinkedHashMap<>(System.getenv());
        for (String entry : env) {
            if (entry == null) continue;
            int eq = entry.indexOf('=');
            if (eq > 0) {
                map.put(entry.substring(0, eq), entry.substring(eq + 1));
            }
        }
        String path = map.get("PATH");
        if (path == null || path.isEmpty()) {
            map.put("PATH", DEFAULT_PATH);
        }
        String[] result = new String[map.size()];
        int i = 0;
        for (Map.Entry<String, String> e : map.entrySet()) {
            result[i++] = e.getKey() + "=" + e.getValue();
        }
        return result;
    }

    @Override
    public final IRemoteProcess newProcess(String[] cmd, String[] env, String dir) {
        enforceCallingCapability("newProcess", SheveryApiConstants.CAP_SHELL_EXEC);
        if (cmd == null || cmd.length == 0) {
            throw new IllegalArgumentException("cmd must not be null or empty");
        }

        String[] finalEnv = normalizeEnvironment(env);
        File workingDir = dir != null ? new File(dir) : null;

        LOGGER.d("newProcess: uid=%d, cmd=%s, env=%s, dir=%s",
                Binder.getCallingUid(), Arrays.toString(cmd), Arrays.toString(finalEnv), dir);

        java.lang.Process process;
        try {
            process = Runtime.getRuntime().exec(cmd, finalEnv, workingDir);
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage());
        }

        ClientRecord clientRecord = clientManager.findClient(Binder.getCallingUid(), Binder.getCallingPid());
        IBinder token = clientRecord != null && clientRecord.client != null ? clientRecord.client.asBinder() : null;

        return new RemoteProcessHolder(process, token);
    }

    /**
     * Fast one-shot command execution over flat Parcel IPC with bounded output and timeout.
     * Adapted from Nightzuku API ({@code NightClient.execFast}).
     */
    protected final void execFast(@NonNull Parcel data, @Nullable Parcel reply) {
        enforceCallingCapability("execFast", SheveryApiConstants.CAP_SHELL_EXEC);

        String[] cmd = data.createStringArray();
        String[] env = data.createStringArray();
        String dir = data.readString();
        long timeoutMs = data.readLong();

        if (cmd == null || cmd.length == 0) {
            throw new IllegalArgumentException("cmd must not be null or empty");
        }
        if (timeoutMs <= 0) {
            timeoutMs = SheveryApiConstants.EXEC_FAST_DEFAULT_TIMEOUT_MS;
        }
        timeoutMs = Math.min(timeoutMs, SheveryApiConstants.EXEC_FAST_MAX_TIMEOUT_MS);

        String[] finalEnv = normalizeEnvironment(env);
        File workingDir = dir != null ? new File(dir) : null;

        java.lang.Process process;
        try {
            process = Runtime.getRuntime().exec(cmd, finalEnv, workingDir);
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage());
        }

        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
        }

        final ByteArrayOutputStream stdoutBuf = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderrBuf = new ByteArrayOutputStream();

        Thread outReader = new Thread(() -> drainBounded(process.getInputStream(), stdoutBuf), "shevery-exec-out");
        Thread errReader = new Thread(() -> drainBounded(process.getErrorStream(), stderrBuf), "shevery-exec-err");
        outReader.setDaemon(true);
        errReader.setDaemon(true);
        outReader.start();
        errReader.start();

        boolean timedOut = false;
        int exitCode = -1;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    timedOut = true;
                    process.destroyForcibly();
                } else {
                    exitCode = process.exitValue();
                }
            } else {
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
                while (true) {
                    try {
                        exitCode = process.exitValue();
                        break;
                    } catch (IllegalThreadStateException ex) {
                        if (System.nanoTime() >= deadline) {
                            timedOut = true;
                            process.destroy();
                            break;
                        }
                        Thread.sleep(25);
                    }
                }
            }
            if (timedOut) {
                // Force-close the pipes so readers blocked on child processes that inherited
                // stdout/stderr are released. Adapted from Porter API.
                try {
                    process.getInputStream().close();
                } catch (IOException ignored) {
                }
                try {
                    process.getErrorStream().close();
                } catch (IOException ignored) {
                }
            }
            outReader.join(1000);
            errReader.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroy();
            throw new IllegalStateException("Interrupted while waiting for process", e);
        }

        if (reply != null) {
            reply.writeNoException();
            reply.writeInt(exitCode);
            reply.writeInt(timedOut ? 1 : 0);
            reply.writeByteArray(stdoutBuf.toByteArray());
            reply.writeByteArray(stderrBuf.toByteArray());
        }
    }

    private static void drainBounded(@NonNull InputStream in, @NonNull ByteArrayOutputStream out) {
        byte[] buf = new byte[8192];
        int remaining = SheveryApiConstants.EXEC_FAST_MAX_OUTPUT_BYTES;
        try (InputStream stream = in) {
            int n;
            while ((n = stream.read(buf)) > 0) {
                int toWrite = Math.min(n, remaining);
                if (toWrite > 0) {
                    out.write(buf, 0, toWrite);
                    remaining -= toWrite;
                }
                if (remaining <= 0) {
                    break;
                }
            }
        } catch (IOException ignored) {
        }
    }

    @CallSuper
    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == ShizukuApiConstants.BINDER_TRANSACTION_transact) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            transactRemote(data, reply, flags);
            return true;
        } else if (code == SheveryApiConstants.BINDER_TRANSACTION_execFast) {
            data.enforceInterface(SheveryApiConstants.BINDER_DESCRIPTOR);
            execFast(data, reply);
            return true;
        } else if (code == 14 /* attachApplication <= v12 */) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            IBinder binder = data.readStrongBinder();
            String packageName = data.readString();
            Bundle args = new Bundle();
            args.putString(ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME, packageName);
            args.putInt(ShizukuApiConstants.ATTACH_APPLICATION_API_VERSION, -1);
            attachApplication(IShizukuApplication.Stub.asInterface(binder), args);
            if (reply != null) {
                reply.writeNoException();
            }
            return true;
        } else if (rishService.onTransact(code, data, reply, flags)) {
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }
}
