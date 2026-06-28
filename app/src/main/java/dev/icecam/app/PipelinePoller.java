package dev.icecam.app;

import android.content.Context;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background TX13 poller — mirrors original App.java 1 Hz pollState heartbeat.
 * Updates runtime diagnostics when pipeline counters change.
 */
public final class PipelinePoller {
    private static volatile PipelinePoller instance;
    private final AppLogger log;
    private final VliveBinderClient binder;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private int[] lastCounters = new int[5];
    private Thread worker;

    public static PipelinePoller get(Context context) {
        Context app = context.getApplicationContext();
        PipelinePoller local = instance;
        if (local == null) {
            synchronized (PipelinePoller.class) {
                local = instance;
                if (local == null) instance = local = new PipelinePoller(app);
            }
        }
        return local;
    }

    private PipelinePoller(Context context) {
        log = new AppLogger(context);
        binder = new VliveBinderClient(log);
        binder.setPreferredService(RootBootstrap.FIXED_SERVICE_NAME);
    }

    public synchronized void start() {
        if (running.get()) return;
        running.set(true);
        worker = new Thread(this::loop, "icecam-tx13-poller");
        worker.setDaemon(true);
        worker.start();
    }

    public synchronized void stop() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    private void loop() {
        while (running.get()) {
            try {
                binder.setPreferredService(RootBootstrap.FIXED_SERVICE_NAME);
                if (binder.connected()) {
                    int[] counters = binder.pollState();
                    if (!Arrays.equals(counters, lastCounters)) {
                        log.log("poll", String.format(java.util.Locale.US,
                                "TX13 counters=[%d,%d,%d,%d,%d]",
                                counters[0], counters[1], counters[2], counters[3], counters[4]));
                        lastCounters = counters.clone();
                    }
                }
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                log.log("poll", "TX13 poller error: " + t);
                sleepQuiet(1500L);
            }
        }
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
