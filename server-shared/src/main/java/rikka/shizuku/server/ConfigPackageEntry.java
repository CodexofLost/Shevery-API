package rikka.shizuku.server;

import com.hamondev.shevery.api.SheveryApiConstants;

import rikka.shizuku.server.util.Logger;

public abstract class ConfigPackageEntry {

    protected static final Logger LOGGER = new Logger("ConfigPackageEntry");

    public ConfigPackageEntry() {
    }

    public abstract boolean isAllowed();

    public abstract boolean isDenied();

    /**
     * Returns fine-grained capability bitmask for this package entry.
     * Defaults to {@link SheveryApiConstants#DEFAULT_CAPABILITIES} when allowed.
     */
    public int getCapabilities() {
        return isAllowed() ? SheveryApiConstants.DEFAULT_CAPABILITIES : 0;
    }
}
