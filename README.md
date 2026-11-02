# Shevery-API

**Shevery-API** is the official, low-latency Privilege API for [Shevery](https://github.com/HmnDev-Tech/shevery). It provides direct access to privileged Binder transactions, one-shot bounded shell execution (`execFast`), `UserService` processes, interactive `rish` PTY sessions, and system properties under ADB (`UID 2000`) or Root (`UID 0`) without relying on the legacy Shizuku `API_V23` permission stack.

## Architecture & Permission Separation

| Channel | Permission | Owner Package | Provider Authority |
| :--- | :--- | :--- | :--- |
| **Shevery Native API** | `com.hamondev.shevery.permission.PRIVILEGED_API` | `com.hamondev.shevery` | `${applicationId}.shevery` |
| **Shizuku Legacy Stub** | `moe.shizuku.manager.permission.API_V23` | `moe.shizuku.privileged.api` (Stub) | `${applicationId}.shizuku` |

### Key Capabilities & Hardening

- **Fast-Pull Binder Acquisition (`SheveryPrivilege.connectFast(context)`)**: Unlike legacy Shizuku clients that passively wait for `IActivityManager` process/UID observers and retry backoffs, `SheveryProvider` and `SheveryPrivilege.connectFast(context)` pull the active server binder directly from `content://com.hamondev.shevery.shevery` (`METHOD_REQUEST_BINDER`) in a single IPC call on startup.
- **Zero-Stub Raw Parcel Codec (`LegacyParcelCodec`)**: Inspects raw `BUNDLE_MAGIC_NATIVE` (`0x4C444E42`) parcel bytes (including Android 13+ lazy parcelables) before `BaseBundle.unparcel()` is triggered, preventing `ClassNotFoundException` / `BadParcelableException` across isolated classloaders.
- **One-Shot Fast Execution (`SheveryPrivilege.execFast`)**: Executes short-lived commands over a single flat Parcel transaction (`BINDER_TRANSACTION_execFast`) with bounded output (`256 KB` max per stream) and configurable timeout (`30s` default) without spawning 3 separate `ParcelFileDescriptor` pipe transfer threads.
- **Fine-Grained Capability Bitmasks (`CAP_*`)**: Supports per-capability enforcement and inspection (`CAP_SHELL_EXEC`, `CAP_BINDER_PROXY`, `CAP_USER_SERVICE`, `CAP_SYSTEM_PROPS`).
- **Hardened Server & IPC Security**:
  - **Direct Process Execution**: Executes command arrays directly via `Runtime.exec(cmd, env, dir)` without shell interpolation (`sh -c`) or command-line concatenation.
  - **Self-Binder Privilege Escalation Guard**: Rejects `transactRemote` calls targeting local `Binder` instances of the server process itself so normal granted clients cannot invoke manager-only endpoints after `Binder.clearCallingIdentity()`.
  - **Untrusted Bundle Confinement**: Confines incoming server `Bundle` arguments to the framework `ClassLoader` (`Bundle.class.getClassLoader()`) prior to unparceling.
  - **Multi-User `UserService` Isolation & JNI Abort Protection**: Keys `UserServiceRecord` entries by `userId:packageName:tagOrClass`, caches `interfaceDescriptor` on binder attach to avoid synchronous round-trips or `writeInterfaceToken(null)` JNI aborts during `destroy()`, and initializes `ActivityThread$AppBindData` prior to `LoadedApk.makeApplication()`.
  - **Hardened `rish` PTY Lifecycle**: Fixes `stderr` pipe descriptor routing, closes PTY master descriptors (`ptmx`) on process exit, detaches native transfer pthreads (`pthread_detach`), monitors output pipe reader loss via `poll()`, terminates child process groups cleanly via `waitid` + `kill(-pid, SIGKILL)`, and reaps uncollected exited hosts.

## Integration Guide

### 1. Gradle Dependencies

```groovy
dependencies {
    implementation "com.hamondev.shevery.api:api:14.6.1"
    implementation "com.hamondev.shevery.api:provider:14.6.1"
}
```

### 2. Register `SheveryProvider` in `AndroidManifest.xml`

```xml
<uses-permission android:name="com.hamondev.shevery.permission.PRIVILEGED_API" />

<application>
    <provider
        android:name="com.hamondev.shevery.api.SheveryProvider"
        android:authorities="${applicationId}.shevery"
        android:multiprocess="false"
        android:enabled="true"
        android:exported="true" />
</application>
```

### 3. Acquire Binder, Check Capabilities & Execute Commands

```java
import android.content.pm.PackageManager;
import com.hamondev.shevery.api.SheveryApiConstants;
import com.hamondev.shevery.api.SheveryPrivilege;

public class MainActivity extends Activity {

    private static final int REQ_CODE_SHEVERY = 100;

    private final SheveryPrivilege.OnPermissionResultListener permListener =
            (requestCode, grantResult) -> {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    runPrivilegedTask();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Fast-pull binder immediately if not already attached
        SheveryPrivilege.connectFast(this);
        SheveryPrivilege.addPermissionResultListener(permListener);

        if (SheveryPrivilege.isGranted()) {
            runPrivilegedTask();
        } else {
            SheveryPrivilege.requestPermission(REQ_CODE_SHEVERY);
        }
    }

    private void runPrivilegedTask() {
        if (SheveryPrivilege.hasCapability(SheveryApiConstants.CAP_SHELL_EXEC)) {
            SheveryPrivilege.ExecResult res = SheveryPrivilege.execFast(new String[]{"id"});
            if (!res.timedOut && res.exitCode == 0) {
                String out = res.getOutputText();
            }
        }
    }

    @Override
    protected void onDestroy() {
        SheveryPrivilege.removePermissionResultListener(permListener);
        super.onDestroy();
    }
}
```

### 4. Wrap System Services (`SheveryBinderWrapper`)

```java
IBinder rawBinder = SheveryPrivilege.getSystemService("package");
IPackageManager pm = IPackageManager.Stub.asInterface(rawBinder);
```

## Credits & Acknowledgements

Shevery-API builds upon the Android privileged IPC ecosystem and incorporates architectural and security improvements from the following projects:

- **[kerneldroid / Nightzuku API](https://github.com/kerneldroid/nightzuku-api)** (`kerneldroid`):
  - Zero-stub raw `Parcel` inspection (`LegacyParcelCodec`) with Android 13+ lazy-parcelable support.
  - One-shot bounded command execution (`execFast` / `ExecResult`) and fine-grained capability bitmasks (`CAP_SHELL_EXEC`, `CAP_BINDER_PROXY`, `CAP_USER_SERVICE`, `CAP_SYSTEM_PROPS`).
  - `ActivityThread$AppBindData` reflection initialization in `UserService` for reliable `Application.onCreate()` execution under `app_process`.
  - Cross-API `IContentProviderUtils.callCompat` with `AttributionSource` support and `LinkageError` fallback.
  - Thread-safe `SystemServiceHelper` binder liveness validation (`pingBinder()`) and `RemoteProcessHolder` `unlinkTokenToDeath()` cleanup.
  - Client-side `RishTerminal` `stderr` descriptor fix, `setWindowSize` reply parcel fix, and pipe descriptor cleanup.
- **[d4rken-org / Porter API](https://github.com/d4rken-org/porter-api)** (`d4rken-org`):
  - `transactRemote` local server-binder privilege escalation check (`targetBinder instanceof Binder`).
  - Untrusted `Bundle` framework `ClassLoader` confinement (`confineToFramework`).
  - `UserServiceRecord` cached `interfaceDescriptor` lookup (preventing `writeInterfaceToken(null)` JNI aborts and wedged-binder round-trips on `destroy()`), `broadcastBinderDied()` notification on pre-attach failure, and multi-user (`userId`) service key isolation in `UserServiceManager`.
  - Native `rish` (`porsh`) PTY/process-group hardening: `O_CLOEXEC` / `FD_CLOEXEC` descriptor hygiene, `F_DUPFD_CLOEXEC` per-thread `ptmx` duplication, `poll()` output reader failure detection (`watch_out`), `waitid` + `kill(-pid, SIGKILL)` process-group termination, dual `stdout`/`stderr` drain synchronization (`atomic_int pending`), `routable(tty)` pipe routing, and uncollected exited host reaping in `RishService` / `RishHost`.
- **[RikkaApps / Shizuku-API](https://github.com/RikkaApps/Shizuku-API)** (`RikkaW` and contributors):
  - Original Shizuku AIDL interfaces, `UserService` concept, `rish` foundation, and compatibility layer.

## License

MIT License
