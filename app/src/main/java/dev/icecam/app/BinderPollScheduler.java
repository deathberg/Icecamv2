package dev.icecam.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.Arrays;
import java.util.Locale;

/**
 * Background TX13 poller matching original App.java 1 Hz heartbeat.
 * Updates SharedPreferences counters and backend playback status from TX15 when available.
 */
public final class BinderPollScheduler {
    private static final long POLL_INTERVAL_MS = 1000L;
    private static volatile BinderPollScheduler instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::pollOnce;
    private final Context app;
    private final AppLogger log;
    private final VliveBinderClient binder;
    private final SharedPreferences prefs;
    private int[] lastCounters = new int[0];
    private boolean running;

    private BinderPollScheduler(Context context) {
        app = context.getApplicationContext();
        log = new AppLogger(app);
        binder = new VliveBinderClient(log);
        binder.setPreferredService(RootBootstrap.FIXED_SERVICE_NAME);
        prefs = app.getSharedPreferences("app_config", Context.MODE_PRIVATE);
    }

    public static BinderPollScheduler get(Context context) {
        BinderPollScheduler local = instance;
        if (local == null) {
            synchronized (BinderPollScheduler.class) {
                local = instance;
                if (local == null) {
                    local = new BinderPollScheduler(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    public void start() {
        if (running) return;
        running = true;
        handler.post(tick);
        log.log("poll", "TX13 scheduler started");
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(tick);
        binder.release();
        log.log("poll", "TX13 scheduler stopped");
    }

    private void pollOnce() {
        if (!running) return;
        try {
            binder.setPreferredService(RootBootstrap.FIXED_SERVICE_NAME);
            int[] counters = binder.pollState();
            if (counters.length > 0 && !Arrays.equals(counters, lastCounters)) {
                SharedPreferences.Editor ed = prefs.edit();
                for (int i = 0; i < counters.length; i++) ed.putInt("PollCounter" + i, counters[i]);
                ed.putString("PollCounters", formatCounters(counters));
                ed.apply();
                log.log("poll", "TX13 " + formatCounters(counters));
                lastCounters = counters.clone();
            }

            if (binder.connected()) {
                int status = binder.getInt15();
                boolean playing = status == 5;
                if (playing != prefs.getBoolean("ReplacementActive", false)) {
                    prefs.edit()
                            .putBoolean("ReplacementActive", playing)
                            .putString("IceCamState", playing ? "REPLACEMENT_ACTIVE" : "IDLE")
                            .apply();
                }
            }
        } catch (Throwable t) {
            if (log != null) log.log("poll", "TX13 poll failed: " + t);
        } finally {
            if (running) handler.postDelayed(tick, POLL_INTERVAL_MS);
        }
    }

    private static String formatCounters(int[] counters) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < counters.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "c%d=%d", i, counters[i]));
        }
        return sb.toString();
    }
}
