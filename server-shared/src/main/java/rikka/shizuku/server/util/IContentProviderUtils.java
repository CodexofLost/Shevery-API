package rikka.shizuku.server.util;

import android.content.AttributionSource;
import android.content.IContentProvider;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Unified cross-API {@link IContentProvider#call} compatibility helper shared by both
 * the privileged server and UserService starter.
 * <p>
 * Adapted from Nightzuku API ({@code rikka.shizuku.server.util.IContentProviderUtils}).
 */
public final class IContentProviderUtils {

    private IContentProviderUtils() {
    }

    @Nullable
    public static Bundle callCompat(
            @NonNull IContentProvider provider,
            @Nullable String callingPkg,
            @Nullable String authority,
            @Nullable String method,
            @Nullable String arg,
            @Nullable Bundle extras
    ) throws RemoteException {
        return callCompat(provider, callingPkg, authority, method, arg, extras, null);
    }

    @Nullable
    public static Bundle callCompat(
            @NonNull IContentProvider provider,
            @Nullable String callingPkg,
            @Nullable String authority,
            @Nullable String method,
            @Nullable String arg,
            @Nullable Bundle extras,
            @Nullable String attributeTag
    ) throws RemoteException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                AttributionSource source = new AttributionSource.Builder(OsUtils.getUid())
                        .setAttributionTag(attributeTag)
                        .setPackageName(callingPkg)
                        .build();
                return provider.call(source, authority, method, arg, extras);
            } catch (LinkageError ignored) {
                return provider.call(callingPkg, attributeTag, authority, method, arg, extras);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return provider.call(callingPkg, attributeTag, authority, method, arg, extras);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return provider.call(callingPkg, authority, method, arg, extras);
        } else {
            return provider.call(callingPkg, method, arg, extras);
        }
    }
}
