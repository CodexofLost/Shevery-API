package com.hamondev.shevery.api;

import androidx.annotation.RestrictTo;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP_PREFIX;

/**
 * Constants for the native Shevery Privilege API and the Shizuku Stub compatibility layer.
 * <p>
 * Native Shevery clients use {@link #PERMISSION_PRIVILEGED_API} and {@link #PROVIDER_SUFFIX},
 * while legacy {@code moe.shizuku.manager.permission.API_V23} requests are isolated in the
 * Shizuku Stub ({@link #SHIZUKU_STUB_APPLICATION_ID}).
 */
public final class SheveryApiConstants {

    private SheveryApiConstants() {
    }

    public static final int SERVER_VERSION = 14;
    public static final int SERVER_PATCH_VERSION = 6;

    // Binder interface & custom transactions
    public static final String BINDER_DESCRIPTOR = "moe.shizuku.server.IShizukuService";
    public static final int BINDER_TRANSACTION_transact = 1;
    public static final int BINDER_TRANSACTION_execFast = 20001;

    // Bounds for fast one-shot command execution (adapted from Nightzuku API)
    public static final int EXEC_FAST_MAX_OUTPUT_BYTES = 256 * 1024;
    public static final long EXEC_FAST_DEFAULT_TIMEOUT_MS = 30_000L;

    // Fine-grained capability bitmasks stored in upper bits of ConfigManager flags (adapted from Nightzuku API)
    public static final int CAP_SHELL_EXEC = 1 << 8;
    public static final int CAP_BINDER_PROXY = 1 << 9;
    public static final int CAP_USER_SERVICE = 1 << 10;
    public static final int CAP_SYSTEM_PROPS = 1 << 11;
    public static final int MASK_CAPABILITIES =
            CAP_SHELL_EXEC | CAP_BINDER_PROXY | CAP_USER_SERVICE | CAP_SYSTEM_PROPS;
    public static final int DEFAULT_CAPABILITIES = MASK_CAPABILITIES;

    // Package identifiers
    public static final String MANAGER_APPLICATION_ID = "com.hamondev.shevery";
    public static final String SHIZUKU_STUB_APPLICATION_ID = "moe.shizuku.privileged.api";

    // Native Shevery permissions (owned by com.hamondev.shevery)
    public static final String PERMISSION_GROUP_API = "com.hamondev.shevery.permission-group.API";
    public static final String PERMISSION_PRIVILEGED_API = "com.hamondev.shevery.permission.PRIVILEGED_API";
    public static final String PERMISSION_MANAGER = "com.hamondev.shevery.permission.MANAGER";

    // Legacy Shizuku permissions (offloaded to Shizuku Stub moe.shizuku.privileged.api)
    public static final String LEGACY_SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23";
    public static final String LEGACY_SHIZUKU_PERMISSION_V22 = "moe.shizuku.manager.permission.API";
    public static final String LEGACY_SHIZUKU_MANAGER_PERMISSION = "moe.shizuku.manager.permission.MANAGER";

    // Client metadata markers
    public static final String META_DATA_CLIENT_SUPPORT = "com.hamondev.shevery.client.PRIVILEGED_API_SUPPORT";
    public static final String LEGACY_META_DATA_CLIENT_SUPPORT = "moe.shizuku.client.V3_SUPPORT";

    // ContentProvider authorities & methods
    public static final String PROVIDER_SUFFIX = ".shevery";
    public static final String LEGACY_PROVIDER_SUFFIX = ".shizuku";
    public static final String MANAGER_PROVIDER_AUTHORITY = MANAGER_APPLICATION_ID + PROVIDER_SUFFIX;
    public static final String MANAGER_LEGACY_PROVIDER_AUTHORITY = MANAGER_APPLICATION_ID + LEGACY_PROVIDER_SUFFIX;
    public static final String MANAGER_CONNECTOR_AUTHORITY = MANAGER_APPLICATION_ID + ".connector";

    public static final String METHOD_SEND_BINDER = "sendBinder";
    public static final String METHOD_GET_BINDER = "getBinder";
    public static final String METHOD_REQUEST_BINDER = "requestBinder";
    public static final String METHOD_SEND_USER_SERVICE = "sendUserService";

    // Extras & broadcast actions
    public static final String EXTRA_BINDER = "com.hamondev.shevery.intent.extra.BINDER";
    public static final String LEGACY_EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER";
    public static final String EXTRA_PERMISSION_CHANNEL = "com.hamondev.shevery.intent.extra.PERMISSION_CHANNEL";
    public static final String EXTRA_REQUESTED_PERMISSION = "com.hamondev.shevery.intent.extra.REQUESTED_PERMISSION";

    public static final String ACTION_BINDER_RECEIVED = "com.hamondev.shevery.api.action.BINDER_RECEIVED";
    public static final String ACTION_REQUEST_PERMISSION = "com.hamondev.shevery.intent.action.REQUEST_PERMISSION";
    public static final String ACTION_REQUEST_BINDER = "com.hamondev.shevery.intent.action.REQUEST_BINDER";

    // Attach application channel & capability negotiation
    public static final String ATTACH_APPLICATION_CLIENT_CHANNEL = "shevery:attach-client-channel";
    public static final String BIND_APPLICATION_CAPABILITIES = "shevery:attach-reply-capabilities";
    public static final String REQUEST_PERMISSION_REPLY_CAPABILITIES = "shevery:request-permission-reply-capabilities";
    public static final String CHANNEL_SHEVERY_NATIVE = "shevery_native";
    public static final String CHANNEL_SHIZUKU_STUB = "shizuku_stub";

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static final String USER_SERVICE_ARG_TOKEN = "shizuku:user-service-arg-token";
}
