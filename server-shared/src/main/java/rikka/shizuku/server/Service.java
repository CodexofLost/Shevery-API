package rikka.shizuku.server;

import android.os.Binder;
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

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

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
                Service.this.enforceCallingPermission(func);
            }
        };
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
                + Binder.getCallingPid()
                + " is not manager ";
        LOGGER.w(msg);
        throw new SecurityException(msg);
    }

    public boolean checkCallerPermission(String func, int callingUid, int callingPid, @Nullable ClientRecord clientRecord) {
        return false;
    }

    public final void enforceCallingPermission(String func) {
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
                    + Binder.getCallingPid()
                    + " is not an attached client";
            LOGGER.w(msg);
            throw new SecurityException(msg);
        }

        if (!clientRecord.allowed) {
            String msg = "Permission Denial: " + func + " from pid="
                    + Binder.getCallingPid()
                    + " requires permission";
            LOGGER.w(msg);
            throw new SecurityException(msg);
        }
    }

    public final void transactRemote(Parcel data, Parcel reply, int flags) throws RemoteException {
        enforceCallingPermission("transactRemote");

        IBinder targetBinder = data.readStrongBinder();
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

        LOGGER.d("transact: uid=%d, descriptor=%s, code=%d", Binder.getCallingUid(), targetBinder.getInterfaceDescriptor(), targetCode);
        Parcel newData = Parcel.obtain();
        try {
            newData.appendFrom(data, data.dataPosition(), data.dataAvail());
        } catch (Throwable tr) {
            LOGGER.w(tr, "appendFrom");
            return;
        }
        try {
            long id = Binder.clearCallingIdentity();
            targetBinder.transact(targetCode, newData, reply, targetFlags);
            Binder.restoreCallingIdentity(id);
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
        enforceCallingPermission("getSystemProperty");

        try {
            return SystemProperties.get(name, defaultValue);
        } catch (Throwable tr) {
            throw new IllegalStateException(tr.getMessage());
        }
    }

    @Override
    public final void setSystemProperty(String name, String value) {
        enforceCallingPermission("setSystemProperty");

        try {
            SystemProperties.set(name, value);
        } catch (Throwable tr) {
            throw new IllegalStateException(tr.getMessage());
        }
    }

    @Override
    public final int removeUserService(IShizukuServiceConnection conn, Bundle options) {
        enforceCallingPermission("removeUserService");

        return userServiceManager.removeUserService(conn, options);
    }

    @Override
    public final int addUserService(IShizukuServiceConnection conn, Bundle options) {
        enforceCallingPermission("addUserService");

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

    private static final String DEFAULT_PATH = "/data/local/tmp/shevery/bin:/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system_ext/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin:/sbin:/data/adb/apatch:/data/adb/ksu/bin";

    private String[] translateAdbCommand(String[] cmd) {
        if (cmd == null || cmd.length == 0) {
            return cmd;
        }

        String program = cmd[0];
        if (!"adb".equals(program) && !program.endsWith("/adb")) {
            return cmd;
        }

        int index = 1;
        while (index < cmd.length) {
            String arg = cmd[index];
            if ("-s".equals(arg) || "-H".equals(arg) || "-P".equals(arg) || "-L".equals(arg)) {
                index += 2;
            } else if ("-d".equals(arg) || "-e".equals(arg)) {
                index += 1;
            } else {
                break;
            }
        }

        if (index >= cmd.length) {
            return new String[]{"/system/bin/sh"};
        }

        String sub = cmd[index];
        if ("shell".equals(sub)) {
            index++;
            if (index >= cmd.length) {
                return new String[]{"/system/bin/sh"};
            }
            StringBuilder sb = new StringBuilder();
            for (int i = index; i < cmd.length; i++) {
                if (i > index) sb.append(' ');
                sb.append(cmd[i]);
            }
            return new String[]{"/system/bin/sh", "-c", sb.toString()};
        } else if ("devices".equals(sub)) {
            return new String[]{"/system/bin/sh", "-c", "echo 'List of devices attached\nshevery-device\tdevice'"};
        } else if ("version".equals(sub)) {
            return new String[]{"/system/bin/sh", "-c", "echo 'Android Debug Bridge version 1.0.41\nVersion shevery-server'"};
        } else if ("get-state".equals(sub) || "get-serialno".equals(sub)) {
            return new String[]{"/system/bin/sh", "-c", "echo 'shevery-device'"};
        } else if ("wait-for-device".equals(sub)) {
            return new String[]{"/system/bin/sh", "-c", "exit 0"};
        } else if ("install".equals(sub)) {
            StringBuilder sb = new StringBuilder("pm install");
            for (int i = index + 1; i < cmd.length; i++) {
                sb.append(' ').append(cmd[i]);
            }
            return new String[]{"/system/bin/sh", "-c", sb.toString()};
        } else if ("uninstall".equals(sub)) {
            StringBuilder sb = new StringBuilder("pm uninstall");
            for (int i = index + 1; i < cmd.length; i++) {
                sb.append(' ').append(cmd[i]);
            }
            return new String[]{"/system/bin/sh", "-c", sb.toString()};
        } else if ("push".equals(sub) || "pull".equals(sub)) {
            StringBuilder sb = new StringBuilder("cp -r");
            for (int i = index + 1; i < cmd.length; i++) {
                sb.append(' ').append(cmd[i]);
            }
            return new String[]{"/system/bin/sh", "-c", sb.toString()};
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = index; i < cmd.length; i++) {
                if (i > index) sb.append(' ');
                sb.append(cmd[i]);
            }
            return new String[]{"/system/bin/sh", "-c", sb.toString()};
        }
    }

    private String[] sanitizeEnvironment(String[] env) {
        Map<String, String> map = new LinkedHashMap<>();
        if (env == null) {
            map.putAll(System.getenv());
        } else {
            for (String entry : env) {
                if (entry == null) continue;
                int eq = entry.indexOf('=');
                if (eq > 0) {
                    map.put(entry.substring(0, eq), entry.substring(eq + 1));
                }
            }
        }
        String path = map.get("PATH");
        if (path == null || path.isEmpty()) {
            map.put("PATH", DEFAULT_PATH);
        } else if (!path.contains("/data/local/tmp/shevery/bin")) {
            map.put("PATH", "/data/local/tmp/shevery/bin:" + path);
        }
        if (!map.containsKey("AXERON")) {
            map.put("AXERON", "true");
        }
        if (!map.containsKey("AXERONVER")) {
            map.put("AXERONVER", "1.0.0");
        }
        if (!map.containsKey("ASH_STANDALONE")) {
            map.put("ASH_STANDALONE", "1");
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
        enforceCallingPermission("newProcess");

        String[] finalCmd = translateAdbCommand(cmd);
        String[] finalEnv = sanitizeEnvironment(env);
        File workingDir = null;
        if (dir != null) {
            File d = new File(dir);
            if (d.isDirectory() && d.canRead() && d.canExecute()) {
                workingDir = d;
            } else {
                workingDir = new File("/data/local/tmp");
            }
        }

        LOGGER.d("newProcess: uid=%d, cmd=%s, env=%s, dir=%s", Binder.getCallingUid(), Arrays.toString(finalCmd), Arrays.toString(finalEnv), workingDir);

        java.lang.Process process;
        try {
            process = Runtime.getRuntime().exec(finalCmd, finalEnv, workingDir);
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage());
        }

        ClientRecord clientRecord = clientManager.findClient(Binder.getCallingUid(), Binder.getCallingPid());
        IBinder token = clientRecord != null ? clientRecord.client.asBinder() : null;

        return new RemoteProcessHolder(process, token);
    }

    @CallSuper
    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == ShizukuApiConstants.BINDER_TRANSACTION_transact) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            transactRemote(data, reply, flags);
            return true;
        } else if (code == 14 /* attachApplication <= v12 */) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            IBinder binder = data.readStrongBinder();
            String packageName = data.readString();
            Bundle args = new Bundle();
            args.putString(ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME, packageName);
            args.putInt(ShizukuApiConstants.ATTACH_APPLICATION_API_VERSION, -1);
            attachApplication(IShizukuApplication.Stub.asInterface(binder), args);
            reply.writeNoException();
            return true;
        } else if (rishService.onTransact(code, data, reply, flags)) {
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }
}
