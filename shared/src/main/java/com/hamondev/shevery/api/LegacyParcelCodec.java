package com.hamondev.shevery.api;

import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import rikka.shizuku.ShizukuApiConstants;

/**
 * Zero-stub raw Parcel codec for Shevery and legacy Shizuku compatibility.
 * <p>
 * Adapted from Nightzuku API ({@code kerneldroid.nightzuku.api.LegacyParcelCodec}).
 * Constructs and parses Bundles containing {@code moe.shizuku.api.BinderContainer} or
 * direct {@link IBinder} entries at the Parcel byte level so neither server nor client
 * processes suffer {@code BadParcelableException} / {@code ClassNotFoundException} when
 * inspecting untrusted or cross-loader Bundles.
 */
public final class LegacyParcelCodec {

    public static final String LEGACY_BINDER_EXTRA_KEY = SheveryApiConstants.LEGACY_EXTRA_BINDER;
    public static final String SHEVERY_BINDER_EXTRA_KEY = SheveryApiConstants.EXTRA_BINDER;
    private static final String LEGACY_BINDER_CONTAINER_CLASS = "moe.shizuku.api.BinderContainer";
    public static final String DESCRIPTOR_ISHIZUKU_APPLICATION = "moe.shizuku.server.IShizukuApplication";

    private static final int BUNDLE_MAGIC_NATIVE = 0x4C444E42; // 'B' 'N' 'D' 'L'
    private static final int VAL_NULL = -1;
    private static final int VAL_STRING = 0;
    private static final int VAL_INTEGER = 1;
    private static final int VAL_PARCELABLE = 4;
    private static final int VAL_LONG = 6;
    private static final int VAL_BOOLEAN = 9;
    private static final int VAL_IBINDER = 15;

    public static final int TRANSACTION_BIND_APPLICATION = 2;
    public static final int TRANSACTION_DISPATCH_PERMISSION_RESULT = 3;

    private LegacyParcelCodec() {
    }

    @NonNull
    public static Bundle createLegacyBinderBundle(@NonNull IBinder binder, @Nullable String token) {
        Parcel body = Parcel.obtain();
        Parcel rawBundle = Parcel.obtain();
        try {
            int mapCount = token != null ? 3 : 2;
            body.writeInt(mapCount);

            body.writeString(SHEVERY_BINDER_EXTRA_KEY);
            body.writeInt(VAL_PARCELABLE);
            body.writeString(LEGACY_BINDER_CONTAINER_CLASS);
            body.writeStrongBinder(binder);

            body.writeString(LEGACY_BINDER_EXTRA_KEY);
            body.writeInt(VAL_PARCELABLE);
            body.writeString(LEGACY_BINDER_CONTAINER_CLASS);
            body.writeStrongBinder(binder);

            if (token != null) {
                body.writeString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN);
                body.writeInt(VAL_STRING);
                body.writeString(token);
            }

            int length = body.dataSize();
            rawBundle.writeInt(length);
            rawBundle.writeInt(BUNDLE_MAGIC_NATIVE);
            rawBundle.appendFrom(body, 0, length);
            rawBundle.setDataPosition(0);

            return Bundle.CREATOR.createFromParcel(rawBundle);
        } finally {
            body.recycle();
            rawBundle.recycle();
        }
    }

    @Nullable
    public static IBinder extractBinderFromBundle(@Nullable Bundle bundle) {
        if (bundle == null) {
            return null;
        }

        Parcel parcel = Parcel.obtain();
        try {
            bundle.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            int length = parcel.readInt();
            if (length > 0) {
                int magic = parcel.readInt();
                if (magic == BUNDLE_MAGIC_NATIVE) {
                    int bodyStart = parcel.dataPosition();
                    int bodyEnd = Math.min(bodyStart + length, parcel.dataSize());
                    int count = parcel.readInt();
                    for (int i = 0; i < count; i++) {
                        if (parcel.dataPosition() >= bodyEnd) {
                            break;
                        }
                        String key = parcel.readString();
                        int type = parcel.readInt();
                        boolean isTargetKey = SHEVERY_BINDER_EXTRA_KEY.equals(key)
                                || LEGACY_BINDER_EXTRA_KEY.equals(key);

                        if (isTargetKey && type == VAL_IBINDER) {
                            return parcel.readStrongBinder();
                        }
                        if (isTargetKey && type == VAL_PARCELABLE) {
                            int posBeforeClass = parcel.dataPosition();
                            String directClassName = parcel.readString();
                            if (LEGACY_BINDER_CONTAINER_CLASS.equals(directClassName)) {
                                return parcel.readStrongBinder();
                            }
                            parcel.setDataPosition(posBeforeClass);
                            int lazySize = parcel.readInt();
                            if (lazySize > 0 && parcel.dataPosition() + lazySize <= bodyEnd) {
                                String lazyClassName = parcel.readString();
                                if (LEGACY_BINDER_CONTAINER_CLASS.equals(lazyClassName)) {
                                    return parcel.readStrongBinder();
                                }
                            }
                            break;
                        }
                        switch (type) {
                            case VAL_NULL:
                                break;
                            case VAL_STRING:
                                parcel.readString();
                                break;
                            case VAL_INTEGER:
                            case VAL_BOOLEAN:
                                parcel.readInt();
                                break;
                            case VAL_LONG:
                                parcel.readLong();
                                break;
                            case VAL_IBINDER:
                                parcel.readStrongBinder();
                                break;
                            default:
                                i = count;
                                break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            parcel.recycle();
        }

        try {
            IBinder direct = bundle.getBinder(SHEVERY_BINDER_EXTRA_KEY);
            if (direct != null) {
                return direct;
            }
            return bundle.getBinder(LEGACY_BINDER_EXTRA_KEY);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
