package dev.icecam.app;

import android.app.Application;

public class IceCamApp extends Application {
    public static IceCamApp instance;

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        BinderStatePoller.get(this).start();
    }

    @Override public void onTerminate() {
        BinderStatePoller.get(this).stop();
        super.onTerminate();
    }
}
