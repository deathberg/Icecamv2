package com.icecam.logspy;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Builds a structured system snapshot for AI context at export time.
 * Uses org.json from the Android platform SDK (no external deps).
 */
public final class SystemMetadata {

    private static final String[] PROCESS_KEYWORDS = {"icecam", "libvc", "float", "tx"};

    private SystemMetadata() {
    }

    /** Human-readable header block prepended to exports. */
    public static String buildTextHeader(Context context) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("=== ICECAM DEBUG METADATA ===\n");
        sb.append("os_display: ").append(Build.DISPLAY).append('\n');
        sb.append("os_release: ").append(Build.VERSION.RELEASE).append('\n');
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("sdk: ").append(Build.VERSION.SDK_INT).append('\n');

        appendMemory(context, sb);
        appendProcesses(context, sb);

        sb.append("=== LOG ENTRIES (NDJSON) ===\n");
        return sb.toString();
    }

    /** JSON metadata object (also embedded in export file). */
    public static String buildJsonHeader(Context context) {
        try {
            JSONObject root = new JSONObject();
            root.put("type", "metadata");
            root.put("ts", System.currentTimeMillis());

            JSONObject os = new JSONObject();
            os.put("display", Build.DISPLAY);
            os.put("release", Build.VERSION.RELEASE);
            os.put("sdk", Build.VERSION.SDK_INT);
            os.put("manufacturer", Build.MANUFACTURER);
            os.put("model", Build.MODEL);
            root.put("os", os);

            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                JSONObject mem = new JSONObject();
                mem.put("avail_mb", mi.availMem / (1024 * 1024));
                mem.put("total_mb", mi.totalMem / (1024 * 1024));
                mem.put("low_memory", mi.lowMemory);
                mem.put("threshold_mb", mi.threshold / (1024 * 1024));
                root.put("memory", mem);

                JSONArray procs = new JSONArray();
                List<ActivityManager.RunningAppProcessInfo> running = am.getRunningAppProcesses();
                if (running != null) {
                    for (ActivityManager.RunningAppProcessInfo p : running) {
                        if (p.processName != null && matchesProcessKeyword(p.processName)) {
                            JSONObject proc = new JSONObject();
                            proc.put("name", p.processName);
                            proc.put("pid", p.pid);
                            proc.put("uid", p.uid);
                            proc.put("importance", p.importance);
                            procs.put(proc);
                        }
                    }
                }
                root.put("icecam_processes", procs);
            }

            return root.toString();
        } catch (Exception e) {
            return "{\"type\":\"metadata\",\"error\":\"" + StructuredLog.jsonEscape(e.getMessage()) + "\"}";
        }
    }

    private static void appendMemory(Context context, StringBuilder sb) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            sb.append("memory: unavailable\n");
            return;
        }
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        sb.append("mem_avail_mb: ").append(mi.availMem / (1024 * 1024)).append('\n');
        sb.append("mem_total_mb: ").append(mi.totalMem / (1024 * 1024)).append('\n');
        sb.append("mem_low: ").append(mi.lowMemory).append('\n');
        sb.append("mem_threshold_mb: ").append(mi.threshold / (1024 * 1024)).append('\n');
    }

    private static void appendProcesses(Context context, StringBuilder sb) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        sb.append("icecam_processes:\n");
        if (am == null) {
            sb.append("  (unavailable)\n");
            return;
        }
        List<ActivityManager.RunningAppProcessInfo> running = am.getRunningAppProcesses();
        if (running == null || running.isEmpty()) {
            sb.append("  (none / permission denied)\n");
            return;
        }
        boolean any = false;
        for (ActivityManager.RunningAppProcessInfo p : running) {
            if (p.processName != null && matchesProcessKeyword(p.processName)) {
                any = true;
                sb.append("  - pid=").append(p.pid)
                        .append(" uid=").append(p.uid)
                        .append(" imp=").append(p.importance)
                        .append(" name=").append(p.processName)
                        .append('\n');
            }
        }
        if (!any) {
            sb.append("  (no matching processes — daemon may have died)\n");
        }
    }

    private static boolean matchesProcessKeyword(String processName) {
        String lower = processName.toLowerCase();
        for (String kw : PROCESS_KEYWORDS) {
            if (lower.contains(kw)) {
                return true;
            }
        }
        return false;
    }
}
