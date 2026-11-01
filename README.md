# Shevery-API

**Shevery-API** is the official, low-latency Privilege API for [Shevery](https://github.com/HmnDev-Tech/shevery). It provides direct access to privileged Binder transactions, `UserService` processes, and system properties under ADB (`UID 2000`) or Root (`UID 0`) without relying on the legacy Shizuku `API_V23` permission stack.

## Architecture & Permission Separation

| Channel | Permission | Owner Package | Provider Authority |
| :--- | :--- | :--- | :--- |
| **Shevery Native API** | `com.hamondev.shevery.permission.PRIVILEGED_API` | `com.hamondev.shevery` | `${applicationId}.shevery` |
| **Shizuku Legacy Stub** | `moe.shizuku.manager.permission.API_V23` | `moe.shizuku.privileged.api` (Stub) | `${applicationId}.shizuku` |

- **Fast-Pull Binder Acquisition (`SheveryPrivilege.connectFast(context)`)**: Unlike legacy Shizuku clients that passively wait for `IActivityManager` process/UID observers and retry backoffs, `SheveryProvider` and `SheveryPrivilege.connectFast(context)` pull the active server binder directly from `content://com.hamondev.shevery.shevery` (`METHOD_REQUEST_BINDER`) in a single IPC call on startup.
- **Lock-Free Callbacks**: Uses `CopyOnWriteArraySet` and `ConcurrentHashMap` registries instead of a single contended global monitor.
- **Isolated `API_V23` Compatibility**: All `moe.shizuku.manager.permission.API_V23` and `moe.shizuku.privileged.api.*` declarations are offloaded to the optional Shizuku Compatibility Stub (`shevery-stub.apk`), eliminating duplicate-permission conflicts with standard or Stealth builds of Shevery.

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
        android:exported="true"
        android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
</application>
```

### 3. Acquire Binder & Request Permission

```java
import android.content.pm.PackageManager;
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
