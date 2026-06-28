package com.icecam.logspy;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IceCam-Log-Spy — minimal logcat monitor and black-box recorder.
 *
 * <p>READ_LOGS must be granted via adb after install:
 * <pre>adb shell pm grant com.icecam.logspy android.permission.READ_LOGS</pre>
 */
public class MainActivity extends Activity {

    private static final int MAX_UI_LINES = 800;
    private static final long UI_FLUSH_MS = 150;

    private TextView tvLog;
    private TextView tvStatus;
    private ScrollView scrollLog;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final List<String> pendingLines = new ArrayList<>();
    private final StringBuilder screenBuffer = new StringBuilder(64 * 1024);
    private int screenLineCount = 0;
    private boolean flushScheduled = false;

    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (LogCaptureService.ACTION_LOG_LINE.equals(intent.getAction())) {
                String line = intent.getStringExtra(LogCaptureService.EXTRA_LINE);
                if (line != null) {
                    enqueueLine(line);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvLog = findViewById(R.id.tv_log);
        tvStatus = findViewById(R.id.tv_status);
        scrollLog = findViewById(R.id.scroll_log);

        Button btnDump = findViewById(R.id.btn_dump);
        Button btnShare = findViewById(R.id.btn_share);
        Button btnClear = findViewById(R.id.btn_clear);

        btnDump.setOnClickListener(v -> dumpToClipboard());
        btnShare.setOnClickListener(v -> shareLogFile());
        btnClear.setOnClickListener(v -> clearScreen());

        updateStatus();

        Intent serviceIntent = new Intent(this, LogCaptureService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(LogCaptureService.ACTION_LOG_LINE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(logReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(logReceiver);
    }

    private void enqueueLine(String line) {
        synchronized (pendingLines) {
            pendingLines.add(line);
        }
        scheduleFlush();
    }

    private void scheduleFlush() {
        if (flushScheduled) {
            return;
        }
        flushScheduled = true;
        uiHandler.postDelayed(this::flushPendingLines, UI_FLUSH_MS);
    }

    private void flushPendingLines() {
        flushScheduled = false;
        List<String> batch;
        synchronized (pendingLines) {
            if (pendingLines.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(pendingLines);
            pendingLines.clear();
        }

        for (String line : batch) {
            screenBuffer.append(line).append('\n');
            screenLineCount++;
        }

        while (screenLineCount > MAX_UI_LINES) {
            int nl = screenBuffer.indexOf("\n");
            if (nl < 0) {
                break;
            }
            screenBuffer.delete(0, nl + 1);
            screenLineCount--;
        }

        tvLog.setText(screenBuffer.toString());
        scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
    }

    private void clearScreen() {
        screenBuffer.setLength(0);
        screenLineCount = 0;
        tvLog.setText("");
    }

    private void updateStatus() {
        File f = new File(LogCaptureService.LOG_FILE_PATH);
        long kb = f.exists() ? f.length() / 1024 : 0;
        tvStatus.setText("file: " + LogCaptureService.LOG_FILE_PATH + "  (" + kb + " KB)");
    }

    private String readLogFileContents() {
        File file = new File(LogCaptureService.LOG_FILE_PATH);
        if (!file.exists()) {
            return "";
        }
        StringBuilder sb = new StringBuilder((int) Math.min(file.length(), 512 * 1024));
        try (BufferedReader br = new BufferedReader(new FileReader(file), 8192)) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            return "ERROR reading log file: " + e.getMessage();
        }
        return sb.toString();
    }

    private void dumpToClipboard() {
        String text = readLogFileContents();
        if (TextUtils.isEmpty(text)) {
            toast("Log file empty or missing");
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("icecam_debug.log", text));
            toast("Copied " + text.length() + " chars to clipboard");
        }
        updateStatus();
    }

    private void shareLogFile() {
        File file = new File(LogCaptureService.LOG_FILE_PATH);
        if (!file.exists()) {
            toast("Log file not found");
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "icecam_debug.log");
        send.putExtra(Intent.EXTRA_TEXT, readLogFileContents());
        startActivity(Intent.createChooser(send, "Share log"));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // -------------------------------------------------------------------------
    // LogcatReader — ProcessBuilder-based capture (fast raw stream, no logd API)
    // -------------------------------------------------------------------------

    /**
     * Reads logcat via {@link ProcessBuilder}, filters by tag keywords, auto-restarts on crash.
     */
    public static final class LogcatReader implements Runnable {

        /** Tag must contain one of these substrings (case-insensitive except TX). */
        private static final String[] TAG_KEYWORDS = {"IceCam", "libvc", "float", "TX"};

        /**
         * threadtime line: MM-DD HH:MM:SS.mmm  PID  TID LEVEL TAG: message
         */
        private static final Pattern THREADTIME = Pattern.compile(
                "^\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+\\d+\\s+\\d+\\s+."
                        + "\\s+([^:]+):\\s.*");

        public interface Listener {
            void onLogLine(String line);
        }

        private final Listener listener;
        private volatile boolean running = true;

        public LogcatReader(Listener listener) {
            this.listener = listener;
        }

        public void stop() {
            running = false;
        }

        @Override
        public void run() {
            while (running) {
                Process process = null;
                try {
                    process = new ProcessBuilder("logcat", "-v", "threadtime")
                            .redirectErrorStream(true)
                            .start();

                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(process.getInputStream()), 16384)) {
                        String line;
                        while (running && (line = reader.readLine()) != null) {
                            if (matchesFilter(line)) {
                                listener.onLogLine(line);
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // logcat process died — loop restarts below
                } finally {
                    if (process != null) {
                        process.destroy();
                    }
                }

                if (running) {
                    sleepQuiet(400);
                }
            }
        }

        static boolean matchesFilter(String line) {
            if (line == null || line.isEmpty()) {
                return false;
            }
            Matcher m = THREADTIME.matcher(line);
            if (!m.matches()) {
                return false;
            }
            String tag = m.group(1);
            return tagContainsKeyword(tag);
        }

        static boolean tagContainsKeyword(String tag) {
            if (tag == null) {
                return false;
            }
            String lower = tag.toLowerCase();
            for (String kw : TAG_KEYWORDS) {
                if (kw.equals("TX")) {
                    if (tag.contains("TX")) {
                        return true;
                    }
                } else if (lower.contains(kw.toLowerCase())) {
                    return true;
                }
            }
            return false;
        }

        private static void sleepQuiet(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
