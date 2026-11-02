package rikka.shizuku;

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

import com.hamondev.shevery.api.LegacyParcelCodec;
import com.hamondev.shevery.api.SheveryApiConstants;
import com.hamondev.shevery.api.SheveryPrivilege;

import moe.shizuku.api.BinderContainer;
import moe.shizuku.server.IShizukuService;
import rikka.sui.Sui;

public class ShizukuProvider extends ContentProvider {

    private static final String TAG = "ShizukuProvider";

    // For receive Binder from Shizuku
    public static final String METHOD_SEND_BINDER = "sendBinder";

    // For share Binder between processes
    public static final String METHOD_GET_BINDER = "getBinder";

    // For user service attachment
    public static final String METHOD_SEND_USER_SERVICE = "sendUserService";

    public static final String ACTION_BINDER_RECEIVED = "moe.shizuku.api.action.BINDER_RECEIVED";

    private static final String EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER";

    public static final String PERMISSION = "moe.shizuku.manager.permission.API_V23";

    public static final String MANAGER_APPLICATION_ID = "moe.shizuku.privileged.api";

    private static volatile boolean enableMultiProcess = false;

    private static volatile boolean isProviderProcess = false;

    private static volatile boolean enableSuiInitialization = true;

    public static void setIsProviderProcess(boolean isProviderProcess) {
        ShizukuProvider.isProviderProcess = isProviderProcess;
    }

    public static void enableMultiProcessSupport(boolean isProviderProcess) {
        Log.d(TAG, "Enable built-in multi-process support (from " + (isProviderProcess ? "provider process" : "non-provider process") + ")");

        ShizukuProvider.isProviderProcess = isProviderProcess;
        ShizukuProvider.enableMultiProcess = true;
    }

    public static void disableAutomaticSuiInitialization() {
        ShizukuProvider.enableSuiInitialization = false;
    }

    private static volatile BroadcastReceiver receiver;

    public static synchronized void requestBinderForNonProviderProcess(@NonNull Context context) {
        if (isProviderProcess) {
            return;
        }

        Log.d(TAG, "request binder in non-provider process");

        if (receiver == null) {
            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    IBinder extracted = callOwnProvider(ctx);
                    if (extracted != null) {
                        Log.i(TAG, "binder received from broadcast");
                        dispatchBinderToFacades(extracted, ctx.getPackageName());
                    }
                }
            };

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.getApplicationContext().registerReceiver(receiver, new IntentFilter(ACTION_BINDER_RECEIVED), Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.getApplicationContext().registerReceiver(receiver, new IntentFilter(ACTION_BINDER_RECEIVED));
            }
        }

        IBinder extracted = callOwnProvider(context);
        if (extracted != null) {
            Log.i(TAG, "Binder received from other process");
            dispatchBinderToFacades(extracted, context.getPackageName());
        }
    }

    @Nullable
    private static IBinder callOwnProvider(@NonNull Context context) {
        final String pkg = context.getPackageName();
        final String authority = pkg + ".shizuku";
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
                    ShizukuProvider.METHOD_GET_BINDER,
                    null,
                    new Bundle()
            );
            if (reply == null) {
                return null;
            }
            IBinder extracted = LegacyParcelCodec.extractBinderFromBundle(reply);
            if (extracted == null) {
                reply.setClassLoader(BinderContainer.class.getClassLoader());
                BinderContainer container = reply.getParcelable(EXTRA_BINDER);
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
        Shizuku.onBinderReceived(binder, packageName);
        try {
            SheveryPrivilege.onBinderReceived(binder, packageName);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        super.attachInfo(context, info);

        if (info.multiprocess)
            throw new IllegalStateException("android:multiprocess must be false");

        if (!info.exported)
            throw new IllegalStateException("android:exported must be true");

        isProviderProcess = true;
    }

    @Override
    public boolean onCreate() {
        if (enableSuiInitialization && !Sui.isSui()) {
            boolean result = Sui.init(getContext().getPackageName());
            Log.d(TAG, "Initialize Sui: " + result);
        }
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        if (Sui.isSui()) {
            Log.w(TAG, "Provider called when Sui is available. Are you using Shizuku and Sui at the same time?");
            return new Bundle();
        }

        if (extras == null) {
            extras = new Bundle();
        }

        Bundle reply = new Bundle();
        switch (method) {
            case METHOD_SEND_BINDER: {
                handleSendBinder(extras);
                break;
            }
            case METHOD_GET_BINDER: {
                if (!handleGetBinder(reply)) {
                    return null;
                }
                break;
            }
            case METHOD_SEND_USER_SERVICE: {
                return handleSendUserService(extras);
            }
        }
        return reply;
    }

    private void handleSendBinder(@NonNull Bundle extras) {
        if (Shizuku.pingBinder()) {
            Log.d(TAG, "sendBinder is called when already a living binder");
            return;
        }

        IBinder receivedBinder = LegacyParcelCodec.extractBinderFromBundle(extras);
        if (receivedBinder == null) {
            extras.setClassLoader(BinderContainer.class.getClassLoader());
            BinderContainer container = extras.getParcelable(EXTRA_BINDER);
            if (container != null) {
                receivedBinder = container.binder;
            }
        }
        if (receivedBinder != null) {
            Log.d(TAG, "binder received in ShizukuProvider");

            dispatchBinderToFacades(receivedBinder, getContext().getPackageName());

            if (enableMultiProcess) {
                Log.d(TAG, "broadcast binder");
                Intent intent = new Intent(ACTION_BINDER_RECEIVED)
                        .setPackage(getContext().getPackageName());
                getContext().sendBroadcast(intent);
            }
        }
    }

    private boolean handleGetBinder(@NonNull Bundle reply) {
        IBinder binder = Shizuku.getBinder();
        if (binder == null || !binder.pingBinder()) {
            binder = SheveryPrivilege.getBinder();
        }
        if (binder == null || !binder.pingBinder()) {
            return false;
        }

        reply.putParcelable(EXTRA_BINDER, new BinderContainer(binder));
        reply.putParcelable(SheveryApiConstants.EXTRA_BINDER, new BinderContainer(binder));
        return true;
    }

    @Nullable
    private Bundle handleSendUserService(@NonNull Bundle extras) {
        Log.i(TAG, "handleSendUserService called in ShizukuProvider");
        IShizukuService service = null;
        try {
            service = Shizuku.requireService();
        } catch (Throwable ignored) {
            try {
                service = SheveryPrivilege.requireService();
            } catch (Throwable ignored2) {
            }
        }
        if (service == null) {
            Log.e(TAG, "No service available for user service attach");
            return null;
        }

        extras.setClassLoader(BinderContainer.class.getClassLoader());
        IBinder userServiceBinder = LegacyParcelCodec.extractBinderFromBundle(extras);
        if (userServiceBinder == null) {
            BinderContainer container = extras.getParcelable(EXTRA_BINDER);
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
        IBinder activeBinder = Shizuku.getBinder();
        if (activeBinder != null) {
            reply.putParcelable(EXTRA_BINDER, new BinderContainer(activeBinder));
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
