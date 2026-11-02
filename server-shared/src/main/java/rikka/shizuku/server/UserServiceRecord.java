package rikka.shizuku.server;

import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteCallbackList;

import androidx.annotation.Nullable;

import java.util.UUID;

import moe.shizuku.server.IShizukuServiceConnection;
import rikka.shizuku.server.util.HandlerUtil;
import rikka.shizuku.server.util.Logger;

public abstract class UserServiceRecord {

    private Runnable startTimeoutCallback;

    private class ConnectionList extends RemoteCallbackList<IShizukuServiceConnection> {

        @Override
        public void onCallbackDied(IShizukuServiceConnection callback) {
            if (daemon || getRegisteredCallbackCount() != 0) {
                return;
            }

            LOGGER.v("Remove service record %s since it does not run as a daemon and all connections are gone", token);
            removeSelf();
        }
    }

    protected static final Logger LOGGER = new Logger("UserServiceRecord");

    private final IBinder.DeathRecipient deathRecipient;
    public final int versionCode;
    public String token;
    public volatile IBinder service;
    @Nullable
    private volatile String interfaceDescriptor;
    public final RemoteCallbackList<IShizukuServiceConnection> callbacks = new ConnectionList();
    public volatile boolean daemon;
    public volatile boolean starting;
    public volatile boolean pendingDestroy;
    public volatile boolean removed;
    public Runnable spawnRunnable;
    public int spawnAttempts;

    public UserServiceRecord(int versionCode, boolean daemon) {
        this.versionCode = versionCode;
        this.token = UUID.randomUUID().toString() + "-" + System.currentTimeMillis();
        this.deathRecipient = () -> {
            LOGGER.v("Binder for service record %s is dead", token);
            removeSelf();
        };
        this.daemon = daemon;
    }

    public void setStartingTimeout(long timeoutMillis) {
        if (starting) {
            LOGGER.w("Service record %s is already starting", token);
            return;
        }

        LOGGER.v("Set starting timeout for service record %s: %d", token, timeoutMillis);

        starting = true;
        startTimeoutCallback = () -> {
            if (!removed && starting) {
                LOGGER.w("Service record %s is not started in %d ms", token, timeoutMillis);
                removeSelf();
            }
        };
        HandlerUtil.getMainHandler().postDelayed(startTimeoutCallback, timeoutMillis);
    }

    public void markRemoved() {
        removed = true;
        starting = false;
        if (startTimeoutCallback != null) {
            HandlerUtil.getMainHandler().removeCallbacks(startTimeoutCallback);
        }
    }

    public void setDaemon(boolean daemon) {
        this.daemon = daemon;
    }

    public void setBinder(IBinder binder) {
        LOGGER.v("Binder received for service record %s", token);

        if (startTimeoutCallback != null) {
            HandlerUtil.getMainHandler().removeCallbacks(startTimeoutCallback);
        }
        starting = false;
        service = binder;

        // Cache descriptor once upon attach so destroy() never blocks on a synchronous
        // getInterfaceDescriptor() round-trip or passes null to writeInterfaceToken().
        // Adapted from Porter API (UserServiceRecord.kt).
        try {
            interfaceDescriptor = binder.getInterfaceDescriptor();
        } catch (Throwable tr) {
            interfaceDescriptor = null;
        }

        try {
            binder.linkToDeath(deathRecipient, 0);
        } catch (Throwable tr) {
            LOGGER.w("linkToDeath %s", token);
        }

        if (pendingDestroy || removed) {
            LOGGER.v("Service record %s attached after an unbind requested its removal - removing", token);
            removeSelf();
            return;
        }

        if (!daemon && callbacks.getRegisteredCallbackCount() == 0) {
            LOGGER.v("Service record %s attached with no connections left - removing", token);
            removeSelf();
            return;
        }

        broadcastBinderReceived();
    }

    public void broadcastBinderReceived() {
        LOGGER.v("Broadcast binder received for service record %s", token);

        int count = callbacks.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                callbacks.getBroadcastItem(i).connected(service);
            } catch (Throwable e) {
                LOGGER.w("Failed to call connected %s", token);
            }
        }
        callbacks.finishBroadcast();
    }

    public void broadcastBinderDied() {
        LOGGER.v("Broadcast binder died for service record %s", token);

        int count = callbacks.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                callbacks.getBroadcastItem(i).died();
            } catch (Throwable e) {
                LOGGER.w("Failed to call died %s", token);
            }
        }
        callbacks.finishBroadcast();
    }

    public abstract void removeSelf();

    public void destroy() {
        markRemoved();
        try {
            IBinder activeService = service;
            if (activeService != null) {
                try {
                    activeService.unlinkToDeath(deathRecipient, 0);
                } catch (Throwable ignored) {
                }
            }

            if (activeService != null && activeService.pingBinder()) {
                String descriptor = interfaceDescriptor;
                if (descriptor == null) {
                    try {
                        descriptor = activeService.getInterfaceDescriptor();
                    } catch (Throwable ignored) {
                    }
                }
                if (descriptor != null) {
                    Parcel data = Parcel.obtain();
                    Parcel reply = Parcel.obtain();
                    try {
                        data.writeInterfaceToken(descriptor);
                        activeService.transact(USER_SERVICE_TRANSACTION_destroy, data, reply, Binder.FLAG_ONEWAY);
                    } catch (Throwable e) {
                        LOGGER.w("Failed to call destroy %s", token);
                    } finally {
                        data.recycle();
                        reply.recycle();
                    }
                } else {
                    // Parcel.writeInterfaceToken(null) triggers a JNI abort; skip safely (from Porter API)
                    LOGGER.w("No interface descriptor for service record %s, skipping destroy transaction", token);
                }
            } else if (activeService == null) {
                // Notify waiting callers when a starting record fails or times out before attaching (from Porter API)
                broadcastBinderDied();
            }
        } finally {
            callbacks.kill();
        }
    }
}
