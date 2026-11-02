package rikka.shizuku.server;

import static rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED;

import android.os.Bundle;

import com.hamondev.shevery.api.SheveryApiConstants;

import moe.shizuku.server.IShizukuApplication;
import rikka.shizuku.server.util.Logger;

public class ClientRecord {

    protected static final Logger LOGGER = new Logger("ClientRecord");

    public final int uid;
    public final int pid;
    public volatile IShizukuApplication client;
    public final String packageName;
    public final int apiVersion;
    public volatile boolean allowed;
    public volatile int capabilities;

    public ClientRecord(int uid, int pid, IShizukuApplication client, String packageName, int apiVersion) {
        this.uid = uid;
        this.pid = pid;
        this.client = client;
        this.packageName = packageName;
        this.allowed = false;
        this.capabilities = 0;
        this.apiVersion = apiVersion;
    }

    public boolean hasCapability(int capabilityFlag) {
        return allowed && (capabilities & capabilityFlag) != 0;
    }

    public void dispatchRequestPermissionResult(int requestCode, boolean allowed) {
        dispatchRequestPermissionResult(
                requestCode,
                allowed,
                allowed ? (capabilities != 0 ? capabilities : SheveryApiConstants.DEFAULT_CAPABILITIES) : 0
        );
    }

    public void dispatchRequestPermissionResult(int requestCode, boolean allowed, int capabilitiesMask) {
        this.allowed = allowed;
        this.capabilities = allowed ? capabilitiesMask : 0;
        Bundle reply = new Bundle();
        reply.putBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, allowed);
        reply.putInt(SheveryApiConstants.REQUEST_PERMISSION_REPLY_CAPABILITIES, this.capabilities);
        try {
            client.dispatchRequestPermissionResult(requestCode, reply);
        } catch (Throwable e) {
            LOGGER.w(e, "dispatchRequestPermissionResult failed for client (uid=%d, pid=%d, package=%s)", uid, pid, packageName);
        }
    }
}
