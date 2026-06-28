package dev.icecam.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.Arrays;
import java.util.Locale;

/**
 * Background TX13/TX15 poller (~1 Hz). Updates poll counters only.
 * Does NOT flip {@code ReplacementActive} — that flag is owned by apply/restore paths.
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
    private int unchangedPolls;
    private boolean running;

    private BinderPollScheduler(Context context) {
        app = context.getApplicationContext();
        log = AppLogger.get(app);
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
        IceCamLog.i(log, "poll", "scheduler started interval=" + POLL_INTERVAL_MS + "ms");
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(tick);
        binder.release();
        IceCamLog.i(log, "poll", "scheduler stopped");
    }

    private void pollOnce() {
        if (!running) return;
        try {
            binder.setPreferredService(RootBootstrap.FIXED_SERVICE_NAME);
            int[] counters = binder.pollState();
            int tx15 = binder.connected() ? binder.getInt15() : -1;

            if (counters.length > 0) {
                if (Arrays.equals(counters, lastCounters)) unchangedPolls++;
                else {
                    unchangedPolls = 0;
                    SharedPreferences.Editor ed = prefs.edit();
                    for (int i = 0; i < counters.length; i++) ed.putInt("PollCounter" + i, counters[i]);
                    ed.putString("PollCounters", formatCounters(counters));
                    ed.putInt("PollTx15", tx15);
                    ed.apply();
                    IceCamLog.poll(log, counters, tx15);
                    lastCounters = counters.clone();
                }
                if (unchangedPolls > 0 && unchangedPolls % 15 == 0) {
                    IceCamLog.poll(log, counters, tx15);
                }
            }
        } catch (Throwable t) {
            IceCamLog.e(log, "poll", "failed: " + t);
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
