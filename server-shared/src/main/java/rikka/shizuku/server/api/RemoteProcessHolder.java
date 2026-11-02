package rikka.shizuku.server.api;

import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import moe.shizuku.server.IRemoteProcess;
import rikka.shizuku.server.util.Logger;
import rikka.shizuku.server.util.ParcelFileDescriptorUtil;

/**
 * Server-side {@link IRemoteProcess} implementation.
 * <p>
 * Incorporates synchronized stream creation, {@code unlinkTokenToDeath()} cleanup,
 * and interrupt-flag restoration from Nightzuku API ({@code RemoteProcessHolder.kt}),
 * while retaining cached {@code err} pipe allocation so concurrent callers do not
 * spawn competing transfer threads.
 */
public class RemoteProcessHolder extends IRemoteProcess.Stub {

    private static final Logger LOGGER = new Logger("RemoteProcessHolder");

    private final Process process;
    private final IBinder token;
    private IBinder.DeathRecipient deathRecipient;
    private ParcelFileDescriptor in;
    private ParcelFileDescriptor out;
    private ParcelFileDescriptor err;

    public RemoteProcessHolder(Process process, IBinder token) {
        this.process = process;
        this.token = token;

        if (token != null) {
            try {
                IBinder.DeathRecipient recipient = () -> {
                    try {
                        if (alive()) {
                            destroy();
                            LOGGER.i("destroy process because the owner is dead");
                        }
                    } catch (Throwable e) {
                        LOGGER.w(e, "failed to destroy process");
                    }
                };
                token.linkToDeath(recipient, 0);
                this.deathRecipient = recipient;
            } catch (Throwable e) {
                LOGGER.w(e, "linkToDeath");
            }
        }
    }

    private synchronized void unlinkTokenToDeath() {
        if (token != null && deathRecipient != null) {
            try {
                token.unlinkToDeath(deathRecipient, 0);
            } catch (Throwable ignored) {
            }
            deathRecipient = null;
        }
    }

    @Override
    public synchronized ParcelFileDescriptor getOutputStream() {
        if (out == null) {
            try {
                out = ParcelFileDescriptorUtil.pipeTo(process.getOutputStream());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }

    @Override
    public synchronized ParcelFileDescriptor getInputStream() {
        if (in == null) {
            try {
                in = ParcelFileDescriptorUtil.pipeFrom(process.getInputStream());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return in;
    }

    @Override
    public synchronized ParcelFileDescriptor getErrorStream() {
        if (err == null) {
            try {
                err = ParcelFileDescriptorUtil.pipeFrom(process.getErrorStream());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return err;
    }

    @Override
    public int waitFor() {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            unlinkTokenToDeath();
        }
    }

    @Override
    public int exitValue() {
        int code = process.exitValue();
        unlinkTokenToDeath();
        return code;
    }

    @Override
    public void destroy() {
        try {
            process.destroy();
        } finally {
            unlinkTokenToDeath();
        }
    }

    @Override
    public boolean alive() throws RemoteException {
        try {
            this.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    @Override
    public boolean waitForTimeout(long timeout, String unitName) throws RemoteException {
        TimeUnit unit = TimeUnit.valueOf(unitName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                boolean exited = process.waitFor(timeout, unit);
                if (exited) {
                    unlinkTokenToDeath();
                }
                return exited;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        long startTime = System.nanoTime();
        long rem = unit.toNanos(timeout);

        do {
            try {
                exitValue();
                return true;
            } catch (IllegalThreadStateException ex) {
                if (rem > 0) {
                    try {
                        Thread.sleep(Math.min(TimeUnit.NANOSECONDS.toMillis(rem) + 1, 100));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException();
                    }
                }
            }
            rem = unit.toNanos(timeout) - (System.nanoTime() - startTime);
        } while (rem > 0);
        return false;
    }
}
