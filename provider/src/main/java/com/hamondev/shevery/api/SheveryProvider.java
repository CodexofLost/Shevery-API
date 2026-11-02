package com.hamondev.shevery.api;

import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import moe.shizuku.api.BinderContainer;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * Native ContentProvider for the Shevery Privilege API ({@code ${applicationId}.shevery}).
 * <p>
 * Incorporates architectural enhancements for zero-stub compatibility:
 * <ul>
 *     <li>Proactive fast-pull binder acquisition on startup ({@link SheveryPrivilege#connectFast(Context)}).</li>
 *     <li>Zero-stub {@link LegacyParcelCodec} extraction across isolated classloaders.</li>
 *     <li>Low-latency server push delivery under {@link SheveryApiConstants#PERMISSION_PRIVILEGED_API}.</li>
 *     <li>Synchronous user-service attachment via {@link SheveryApiConstants#METHOD_SEND_USER_SERVICE}.</li>
 *     <li>Automatic synchronization between {@link SheveryPrivilege} and legacy {@link Shizuku} facades.</li>
 * </ul>
 */
public class SheveryProvider extends ContentProvider {

    private static final String TAG = "SheveryProvider";

    public static final String METHOD_SEND_BINDER = SheveryApiConstants.METHOD_SEND_BINDER;
    public static final String METHOD_GET_BINDER = SheveryApiConstants.METHOD_GET_BINDER;
    public static final String METHOD_REQUEST_BINDER = SheveryApiConstants.METHOD_REQUEST_BINDER;
    public static final String METHOD_SEND_USER_SERVICE = SheveryApiConstants.METHOD_SEND_USER_SERVICE;
    public static final String ACTION_BINDER_RECEIVED = SheveryApiConstants.ACTION_BINDER_RECEIVED;
    public static final String PERMISSION = SheveryApiConstants.PERMISSION_PRIVILEGED_API;
    public static final String MANAGER_APPLICATION_ID = SheveryApiConstants.MANAGER_APPLICATION_ID;

    private static volatile boolean enableMultiProcess = false;
    private static volatile boolean isProviderProcess = false;
    private static volatile BroadcastReceiver multiProcessReceiver;

    public static void enableMultiProcessSupport(boolean isProviderProcess) {
        SheveryProvider.isProviderProcess = isProviderProcess;
        SheveryProvider.enableMultiProcess = true;
    }

    public static synchronized void requestBinderForNonProviderProcess(@NonNull Context context) {
        if (isProviderProcess) {
            return;
        }
        if (multiProcessReceiver == null) {
            multiProcessReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    // Signal only: pull binder from our own provider
                    IBinder extracted = callOwnProvider(ctx);
                    if (extracted != null) {
                        dispatchBinderToFacades(extracted, ctx.getPackageName());
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_BINDER_RECEIVED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.getApplicationContext().registerReceiver(multiProcessReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.getApplicationContext().registerReceiver(multiProcessReceiver, filter);
            }
        }

        if (SheveryPrivilege.connectFast(context)) {
            return;
        }

        IBinder extracted = callOwnProvider(context);
        if (extracted != null) {
            dispatchBinderToFacades(extracted, context.getPackageName());
        }
    }

    @Nullable
    private static IBinder callOwnProvider(@NonNull Context context) {
        final String pkg = context.getPackageName();
        final String authority = pkg + SheveryApiConstants.PROVIDER_SUFFIX;
        ProviderInfo providerInfo = null;
        try {
            providerInfo = context.getPackageManager().resolveContentProvider(authority, 0);
        } catch (Throwable ignored) {
        }
        if (providerInfo != null && !pkg.equals(providerInfo.packageName)) {
            return null;
        }

        try {
            Bundle reply = context.getContentResolver().call(
                    Uri.parse("content://" + authority),
                    METHOD_GET_BINDER,
                    null,
                    new Bundle()
            );
            if (reply == null) {
                return null;
            }
            IBinder extracted = LegacyParcelCodec.extractBinderFromBundle(reply);
            if (extracted == null) {
                reply.setClassLoader(BinderContainer.class.getClassLoader());
                BinderContainer container = reply.getParcelable(SheveryApiConstants.EXTRA_BINDER);
                if (container == null) {
                    container = reply.getParcelable(SheveryApiConstants.LEGACY_EXTRA_BINDER);
                }
                if (container != null) {
                    extracted = container.binder;
                }
            }
            return extracted;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void dispatchBinderToFacades(@NonNull IBinder binder, @NonNull String packageName) {
        SheveryPrivilege.onBinderReceived(binder, packageName);
        try {
            Shizuku.onBinderReceived(binder, packageName);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        super.attachInfo(context, info);
        if (info.multiprocess) {
            throw new IllegalStateException("android:multiprocess must be false");
        }
        if (!info.exported) {
            throw new IllegalStateException("android:exported must be true");
        }
        isProviderProcess = true;
    }

    @Override
    public boolean onCreate() {
        final Context ctx = getContext();
        if (ctx != null && !MANAGER_APPLICATION_ID.equals(ctx.getPackageName())) {
            try {
                SheveryPrivilege.connectFast(ctx);
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        if (extras == null) {
            extras = new Bundle();
        }

        Bundle reply = new Bundle();
        switch (method) {
            case METHOD_SEND_BINDER: {
                handleSendBinder(extras);
                break;
            }
            case METHOD_GET_BINDER:
            case METHOD_REQUEST_BINDER: {
                if (!handleGetBinder(reply)) {
                    return null;
                }
                break;
            }
            case METHOD_SEND_USER_SERVICE: {
                return handleSendUserService(extras);
            }
            default:
                return null;
        }
        return reply;
    }

    private void handleSendBinder(@NonNull Bundle extras) {
        if (SheveryPrivilege.pingBinder()) {
            return;
        }
        IBinder receivedBinder = LegacyParcelCodec.extractBinderFromBundle(extras);
        if (receivedBinder == null) {
            extras.setClassLoader(BinderContainer.class.getClassLoader());
            BinderContainer container = extras.getParcelable(SheveryApiConstants.EXTRA_BINDER);
            if (container == null) {
                container = extras.getParcelable(SheveryApiConstants.LEGACY_EXTRA_BINDER);
            }
            if (container != null) {
                receivedBinder = container.binder;
            }
        }
        Context ctx = getContext();
        if (receivedBinder != null && ctx != null) {
            Log.d(TAG, "Native Shevery binder received for " + ctx.getPackageName());
            dispatchBinderToFacades(receivedBinder, ctx.getPackageName());

            if (enableMultiProcess) {
                Intent intent = new Intent(ACTION_BINDER_RECEIVED)
                        .setPackage(ctx.getPackageName());
                ctx.sendBroadcast(intent);
            }
        }
    }

    private boolean handleGetBinder(@NonNull Bundle reply) {
        IBinder binder = SheveryPrivilege.getBinder();
        if (binder == null || !binder.pingBinder()) {
            return false;
        }
        BinderContainer container = new BinderContainer(binder);
        reply.putParcelable(SheveryApiConstants.EXTRA_BINDER, container);
        reply.putParcelable(SheveryApiConstants.LEGACY_EXTRA_BINDER, container);
        return true;
    }

    @Nullable
    private Bundle handleSendUserService(@NonNull Bundle extras) {
        Log.i(TAG, "handleSendUserService called");
        IShizukuService service = null;
        try {
            service = SheveryPrivilege.requireService();
        } catch (Throwable ignored) {
        }
        if (service == null) {
            Log.e(TAG, "Shevery daemon service not available for user-service attach");
            return null;
        }

        extras.setClassLoader(BinderContainer.class.getClassLoader());
        IBinder userServiceBinder = LegacyParcelCodec.extractBinderFromBundle(extras);
        if (userServiceBinder == null) {
            BinderContainer container = extras.getParcelable(SheveryApiConstants.EXTRA_BINDER);
            if (container == null) {
                container = extras.getParcelable(SheveryApiConstants.LEGACY_EXTRA_BINDER);
            }
            if (container != null) {
                userServiceBinder = container.binder;
            }
        }

        if (userServiceBinder == null) {
            Log.e(TAG, "No valid IBinder in user service arguments");
            return null;
        }

        try {
            service.attachUserService(userServiceBinder, extras);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to attach user service", e);
            return null;
        }

        Bundle reply = new Bundle();
        IBinder activeBinder = SheveryPrivilege.getBinder();
        if (activeBinder != null) {
            BinderContainer container = new BinderContainer(activeBinder);
            reply.putParcelable(SheveryApiConstants.EXTRA_BINDER, container);
            reply.putParcelable(SheveryApiConstants.LEGACY_EXTRA_BINDER, container);
        }
        return reply;
    }

    @Nullable
    @Override
    public final Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public final String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public final Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public final int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public final int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }
}
