package dev.icecam.app;

import android.util.Log;

import java.util.Locale;

/** Unified IceCam logging: logcat tag {@value #TAG} + optional file ring via {@link AppLogger}. */
public final class IceCamLog {
    public static final String TAG = "IceCam";

    private IceCamLog() {}

    public static void i(AppLogger file, String subtag, String msg) {
        Log.i(TAG, "[" + subtag + "] " + msg);
        if (file != null) file.log(subtag, msg);
    }

    public static void w(AppLogger file, String subtag, String msg) {
        Log.w(TAG, "[" + subtag + "] " + msg);
        if (file != null) file.log(subtag, "WARN " + msg);
    }

    public static void e(AppLogger file, String subtag, String msg) {
        Log.e(TAG, "[" + subtag + "] " + msg);
        if (file != null) file.log(subtag, "ERROR " + msg);
    }

    public static void marker(AppLogger file, String action, String detail) {
        String msg = "MARKER action=" + action + (detail == null || detail.isEmpty() ? "" : " " + detail);
        Log.i(TAG, msg);
        if (file != null) file.log("marker", msg);
    }

    public static void tx(AppLogger file, int code, String name, String send, int result, long ms) {
        String msg = String.format(Locale.US, "TX%d %s send={%s} -> %d (%dms)", code, name, send, result, ms);
        Log.i(TAG, msg);
        if (file != null) file.log("tx", msg);
    }

    public static void poll(AppLogger file, int[] counters, int tx15) {
        if (counters == null || counters.length == 0) return;
        StringBuilder sb = new StringBuilder("TX13");
        for (int i = 0; i < counters.length; i++) {
            sb.append(String.format(Locale.US, " c%d=%d", i, counters[i]));
        }
        sb.append(" TX15=").append(tx15);
        Log.d(TAG, sb.toString());
        if (file != null) file.log("poll", sb.toString());
    }

    public static String txName(int code) {
        switch (code) {
            case 11: return "PLAY_SOURCE";
            case 12: return "STATUS";
            case 13: return "POLL_STATE";
            case 14: return "SET_MODE";
            case 15: return "GET_STATUS";
            case 16: return "AUTO_ROTATE";
            case 17: return "LOOP";
            case 18: return "ANGLE";
            case 19: return "MIRROR";
            case 22: return "SEEK_RANGE";
            case 24: return "TRANSFORM";
            case 25: return "HARD_RECOVERY";
            default: return "TX" + code;
        }
    }
}
