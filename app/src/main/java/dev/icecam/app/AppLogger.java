package dev.icecam.app;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public final class AppLogger {
    public interface Listener { void onLogChanged(String text); }

    private static final int MAX = 48000;
    private static final int EXPORT_MAX_LINES = 180;
    private static final long PROCESS_START_MS = SystemClock.elapsedRealtime();
    private static final AtomicLong SEQ = new AtomicLong(1L);
    private final StringBuilder buffer = new StringBuilder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File file;
    private Listener listener;

    public AppLogger(android.content.Context ctx) {
        file = new File(ctx.getExternalFilesDir(null), "icecam-runtime.log");
        log("logger", "file=" + file.getAbsolutePath());
    }

    public void setListener(Listener l) { listener = l; if (l != null) l.onLogChanged(buffer.toString()); }
    public File file() { return file; }
    public String text() { return buffer.toString(); }

    /** Recent lines for export — skips noisy root service-list spam. */
    public String exportRing() {
        synchronized (buffer) {
            String[] lines = buffer.toString().split("\n");
            StringBuilder out = new StringBuilder();
            int kept = 0;
            for (int i = lines.length - 1; i >= 0 && kept < EXPORT_MAX_LINES; i--) {
                String line = lines[i];
                if (line.length() == 0) continue;
                if (isNoisyRootLine(line)) continue;
                out.insert(0, line + '\n');
                kept++;
            }
            if (kept == 0) return buffer.toString();
            return out.toString();
        }
    }

    private static boolean isNoisyRootLine(String line) {
        if (!line.contains("[root]")) return false;
        return line.contains("media.") || line.contains("miui.") || line.contains("android.")
                || line.contains("vendor.") || line.contains("Found ") && line.contains("services");
    }

    public void log(String tag, String msg) {
        long id = SEQ.getAndIncrement();
        long up = SystemClock.elapsedRealtime() - PROCESS_START_MS;
        Runtime rt = Runtime.getRuntime();
        long usedKb = (rt.totalMemory() - rt.freeMemory()) / 1024L;
        long maxKb = rt.maxMemory() / 1024L;
        String line = String.format(Locale.US,
                "%s #%05d +%07dms [%s] {%s mem=%d/%dKB} %s\n",
                new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()),
                id, up, tag, Thread.currentThread().getName(), usedKb, maxKb,
                String.valueOf(msg).replace('\r', ' '));
        synchronized (buffer) {
            buffer.insert(0, line);
            if (buffer.length() > MAX) buffer.setLength(MAX);
            try (FileOutputStream out = new FileOutputStream(file, true)) { out.write(line.getBytes("UTF-8")); } catch (Throwable ignored) {}
        }
        main.post(() -> { if (listener != null) listener.onLogChanged(buffer.toString()); });
    }

    public void logDivider(String tag, String title) {
        log(tag, "---------------- " + title + " ----------------");
    }

    public void logBlock(String tag, String block) {
        if (block == null || block.length() == 0) { log(tag, "<empty>"); return; }
        String[] lines = block.split("\\n");
        int limit = "root".equals(tag) ? 48 : 120;
        int n = Math.min(lines.length, limit);
        for (int i = 0; i < n; i++) {
            String l = lines[i].trim();
            if (l.length() > 0) log(tag, l);
        }
        if (lines.length > limit) log(tag, "... truncated " + (lines.length - limit) + " more lines");
    }
}
