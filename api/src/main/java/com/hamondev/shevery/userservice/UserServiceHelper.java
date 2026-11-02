package com.hamondev.shevery.userservice;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import androidx.annotation.NonNull;

/**
 * Lifecycle and interrogation helpers for remote {@code UserService} binder instances.
 */
public final class UserServiceHelper {

    public static final int TRANSACTION_DESTROY = 0x00FFFFF1;
    public static final int TRANSACTION_IS_ALIVE = 0x00FFFFF2;
    public static final int TRANSACTION_GET_UID = 0x00FFFFF3;
    public static final int TRANSACTION_GET_PID = 0x00FFFFF4;

    private UserServiceHelper() {
    }

    /**
     * Signals the remote user-service to perform cleanup and terminate.
     *
     * @param binder Strong binder handle of the remote user service
     * @throws RemoteException If the remote IPC transaction fails
     */
    public static void destroy(@NonNull IBinder binder) throws RemoteException {
        transactVoid(binder, TRANSACTION_DESTROY);
    }

    /**
     * Checks if the remote user service process is currently active.
     *
     * @param binder Strong binder handle of the remote user service
     * @return {@code true} if the remote process reports alive
     * @throws RemoteException If the remote IPC transaction fails
     */
    public static boolean isAlive(@NonNull IBinder binder) throws RemoteException {
        return transactInt(binder, TRANSACTION_IS_ALIVE) != 0;
    }

    /**
     * Retrieves the Linux UID of the elevated user-service process (e.g. 0 for root, 2000 for adb).
     *
     * @param binder Strong binder handle of the remote user service
     * @return Remote process UID
     * @throws RemoteException If the remote IPC transaction fails
     */
    public static int getUid(@NonNull IBinder binder) throws RemoteException {
        return transactInt(binder, TRANSACTION_GET_UID);
    }

    /**
     * Retrieves the Linux PID of the elevated user-service process.
     *
     * @param binder Strong binder handle of the remote user service
     * @return Remote process PID
     * @throws RemoteException If the remote IPC transaction fails
     */
    public static int getPid(@NonNull IBinder binder) throws RemoteException {
        return transactInt(binder, TRANSACTION_GET_PID);
    }

    private static void transactVoid(@NonNull IBinder binder, int code) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            binder.transact(code, data, reply, 0);
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static int transactInt(@NonNull IBinder binder, int code) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            binder.transact(code, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
