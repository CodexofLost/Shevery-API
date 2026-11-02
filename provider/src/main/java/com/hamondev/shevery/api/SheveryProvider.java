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

/**
 * Native ContentProvider for the Shevery Privilege API ({@code ${applicationId}.shevery}).
 * <p>
 * Supports proactive fast-pull binder acquisition on startup ({@link SheveryPrivilege#connectFast(Context)}),
 * zero-stub {@link LegacyParcelCodec} extraction (adapted from Nightzuku API), and low-latency
 * server push delivery under {@link SheveryApiConstants#PERMISSION_PRIVILEGED_API}.
 */
public class SheveryProvider extends ContentProvider {

    private static final String TAG = "SheveryProvider";

    public static final String METHOD_SEND_BINDER = SheveryApiConstants.METHOD_SEND_BINDER;
    public static final String METHOD_GET_BINDER = SheveryApiConstants.METHOD_GET_BINDER;
    public static final String METHOD_REQUEST_BINDER = SheveryApiConstants.METHOD_REQUEST_BINDER;
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
                    // The broadcast is only a signal: never trust a binder shipped in its
                    // extras. Pull it from our own provider after verifying the authority
                    // still belongs to this application.
                    IBinder extracted = callOwnProvider(ctx);
                    if (extracted != null) {
                        SheveryPrivilege.onBinderReceived(extracted, ctx.getPackageName());
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
            SheveryPrivilege.onBinderReceived(extracted, context.getPackageName());
        }
    }

    @Nullable
    private static IBinder callOwnProvider(@NonNull Context context) {
        final String pkg = context.getPackageName();
        final String authority = pkg + SheveryApiConstants.PROVIDER_SUFFIX;
        ProviderInfo providerInfo = context.getPackageManager().resolveContentProvider(authority, 0);
        if (providerInfo == null || !pkg.equals(providerInfo.packageName)) {
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
                if (container != null) {
                    extracted = container.binder;
                }
            }
            return extracted;
        } catch (Throwable ignored) {
            return null;
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
            SheveryPrivilege.onBinderReceived(receivedBinder, ctx.getPackageName());

            if (enableMultiProcess) {
                // Signal only: the receiving process pulls the binder from our provider
                // instead of trusting an IBinder carried in an unprotected broadcast.
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
