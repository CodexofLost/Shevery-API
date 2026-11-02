package rikka.shizuku.server.util;

import android.os.Handler;

import java.util.Objects;

public class HandlerUtil {

    private static volatile Handler mainHandler;

    public static void setMainHandler(Handler mainHandler) {
        HandlerUtil.mainHandler = mainHandler;
    }

    public static Handler getMainHandler() {
        return Objects.requireNonNull(mainHandler, "Please call setMainHandler first");
    }
}
