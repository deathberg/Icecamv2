package dev.icecam.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background TX13 poller — mirrors original App.java 1 Hz heartbeat against vcplax.
 */
public final class BinderStatePoller {
    private static volatile BinderStatePoller instance;

    public static BinderStatePoller get(Context context) {
        Context app = context.getApplicationContext();
        BinderStatePoller local = instance;
        if (local == null) {
            synchronized (BinderStatePoller.class) {
                local = instance;
                if (local == null) {
                    local = new BinderStatePoller(app);
                    instance = local;
                }
            }
        }
        return local;
    }

    private final SharedPreferences prefs;
    private final AppLogger log;
    private final VliveBinderClient binder;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "icecam-tx13-poller"));
    private final AtomicBoolean started = new AtomicBoolean(false);

    private BinderStatePoller(Context context) {
        prefs = context.getSharedPreferences("app_config", Context.MODE_PRIVATE);
        log = new AppLogger(context);
        binder = new VliveBinderClient(log);
        binder.setPreferredService(RootBootstrap.FIXED_SERVICE_NAME);
    }

    public void start() {
        if (!started.compareAndSet(false, true)) return;
        scheduler.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
        log.log("poll", "TX13 poller started");
    }

    public void stop() {
        if (!started.compareAndSet(true, false)) return;
        scheduler.shutdownNow();
        binder.release();
        log.log("poll", "TX13 poller stopped");
    }

    private void tick() {
        try {
            if (!prefs.getBoolean("ReplacementActive", false)) return;
            binder.setPreferredService(RootBootstrap.FIXED_SERVICE_NAME);
            int[] counters = binder.pollState();
            if (counters.length >= 2) {
                prefs.edit()
                        .putInt("PollFrameIndex", counters[0])
                        .putInt("PollDecodedFrames", counters[1])
                        .putInt("PollQueueDepth", counters.length > 2 ? counters[2] : 0)
                        .putInt("PollCodecState", counters.length > 3 ? counters[3] : 0)
                        .putInt("PollNetworkTicks", counters.length > 4 ? counters[4] : 0)
                        .apply();
            }
        } catch (Throwable t) {
            log.log("poll", "TX13 tick failed: " + t);
        }
    }
}
