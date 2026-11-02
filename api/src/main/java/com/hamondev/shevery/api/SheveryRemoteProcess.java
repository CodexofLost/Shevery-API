package com.hamondev.shevery.api;

import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import moe.shizuku.server.IRemoteProcess;

/**
 * Remote process wrapper for the native Shevery Privilege API.
 */
public final class SheveryRemoteProcess extends Process implements Parcelable {

    private static final Set<SheveryRemoteProcess> CACHE = Collections.synchronizedSet(new ArraySet<>());
    private static final String TAG = "SheveryRemoteProcess";

    private volatile IRemoteProcess remote;
    private OutputStream os;
    private InputStream is;
    private InputStream err;

    SheveryRemoteProcess(IRemoteProcess remote) {
        this.remote = remote;
        try {
            this.remote.asBinder().linkToDeath(() -> {
                this.remote = null;
                Log.v(TAG, "remote process is dead");
                CACHE.remove(SheveryRemoteProcess.this);
            }, 0);
        } catch (RemoteException e) {
            Log.e(TAG, "linkToDeath", e);
        }
        CACHE.add(this);
    }

    private SheveryRemoteProcess(Parcel in) {
        this.remote = IRemoteProcess.Stub.asInterface(in.readStrongBinder());
    }

    @NonNull
    private IRemoteProcess requireRemote() {
        IRemoteProcess current = remote;
        if (current == null) {
            throw new IllegalStateException("Remote process binder is dead");
        }
        return current;
    }

    @Override
    public synchronized OutputStream getOutputStream() {
        if (os == null) {
            try {
                os = new ParcelFileDescriptor.AutoCloseOutputStream(requireRemote().getOutputStream());
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }
        return os;
    }

    @Override
    public synchronized InputStream getInputStream() {
        if (is == null) {
            try {
                is = new ParcelFileDescriptor.AutoCloseInputStream(requireRemote().getInputStream());
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }
        return is;
    }

    @Override
    public synchronized InputStream getErrorStream() {
        if (err == null) {
            try {
                err = new ParcelFileDescriptor.AutoCloseInputStream(requireRemote().getErrorStream());
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }
        return err;
    }

    @Override
    public int waitFor() throws InterruptedException {
        try {
            return requireRemote().waitFor();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public int exitValue() {
        try {
            return requireRemote().exitValue();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void destroy() {
        try {
            requireRemote().destroy();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean alive() {
        try {
            return requireRemote().alive();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean waitForTimeout(long timeout, TimeUnit unit) throws InterruptedException {
        try {
            return requireRemote().waitForTimeout(timeout, unit.toString());
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public IBinder asBinder() {
        return requireRemote().asBinder();
    }

    public static final Creator<SheveryRemoteProcess> CREATOR = new Creator<SheveryRemoteProcess>() {
        @Override
        public SheveryRemoteProcess createFromParcel(Parcel in) {
            return new SheveryRemoteProcess(in);
        }

        @Override
        public SheveryRemoteProcess[] newArray(int size) {
            return new SheveryRemoteProcess[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        IRemoteProcess current = remote;
        dest.writeStrongBinder(current != null ? current.asBinder() : null);
    }
}
